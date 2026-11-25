package io.github.abreulucass.atlas.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import io.github.abreulucass.atlas.TestcontainersConfiguration;
import io.github.abreulucass.atlas.access.AuthFixture.Session;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RefreshIT {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate app;

	@Autowired
	JwtDecoder decoder;

	@Value("${spring.datasource.url}")
	String url;

	@Value("${spring.flyway.user}")
	String ownerUser;

	@Value("${spring.flyway.password}")
	String ownerPassword;

	AuthFixture fixture() {
		return new AuthFixture(mvc, app);
	}

	static byte[] sha256(String token) throws Exception {
		return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
	}

	int liveTokens(UUID userId) {
		return app.queryForObject("select count(*) from refresh_tokens where user_id = ? and used_at is null "
				+ "and revoked_at is null", Integer.class, userId);
	}

	@Test
	void rotatesTokens() throws Exception {
		AuthFixture f = fixture();
		Session ana = f.register();

		String body = f.refresh(ana.refreshToken()).andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.expiresIn").value(900))
			.andReturn()
			.getResponse()
			.getContentAsString();

		String newRefresh = JsonPath.read(body, "$.refreshToken");
		assertThat(newRefresh).isNotEqualTo(ana.refreshToken());
		assertThat(decoder.decode(JsonPath.<String>read(body, "$.accessToken")).<String>getClaim("org"))
			.isEqualTo(ana.organizationId().toString());
		f.refresh(ana.refreshToken()).andExpect(status().isUnauthorized());
	}

	@Test
	void reuseRevokesWholeChain() throws Exception {
		AuthFixture f = fixture();
		Session ana = f.register();
		String second = JsonPath.read(
				f.refresh(ana.refreshToken()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$.refreshToken");
		String third = JsonPath.read(
				f.refresh(second).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$.refreshToken");
		assertThat(liveTokens(ana.userId())).isEqualTo(1);

		f.refresh(ana.refreshToken()).andExpect(status().isUnauthorized());

		assertThat(liveTokens(ana.userId())).isZero();
		f.refresh(third).andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsUnknownExpiredOrRevokedToken() throws Exception {
		AuthFixture f = fixture();
		Session expired = f.register();
		Session revoked = f.register();
		app.update("update refresh_tokens set expires_at = now() - interval '1 second' where user_id = ?",
				expired.userId());
		app.update("update refresh_tokens set revoked_at = now() where user_id = ?", revoked.userId());

		f.refresh("unknown-token-" + UUID.randomUUID()).andExpect(status().isUnauthorized());
		f.refresh(expired.refreshToken()).andExpect(status().isUnauthorized());
		f.refresh(revoked.refreshToken()).andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsWhenMembershipGone() throws Exception {
		AuthFixture f = fixture();
		Session ana = f.register();
		UUID other = UUID.randomUUID();
		app.update("insert into organizations (id, name, slug) values (?, 'Other', ?)", other, "other-" + ana.id());
		app.update("insert into memberships (id, user_id, organization_id, role, created_at) "
				+ "values (?, ?, ?, 'ADMIN', now())", UUID.randomUUID(), ana.userId(), other);
		app.update("delete from memberships where user_id = ? and organization_id = ?", ana.userId(),
				ana.organizationId());

		f.refresh(ana.refreshToken()).andExpect(status().isUnauthorized());
		assertThat(app.queryForObject("select count(*) from memberships where user_id = ?", Integer.class,
				ana.userId())).isEqualTo(1);
	}

	@Test
	void concurrentRefreshAcceptsAtMostOne() throws Exception {
		AuthFixture f = fixture();
		Session ana = f.register();
		int threads = 6;
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				Callable<Integer> call = () -> {
					start.await();
					return f.refresh(ana.refreshToken()).andReturn().getResponse().getStatus();
				};
				results.add(pool.submit(call));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> r : results) {
				statuses.add(r.get());
			}

			assertThat(statuses).filteredOn(s -> s == 200).hasSizeLessThanOrEqualTo(1);
			assertThat(statuses).filteredOn(s -> s != 200).allMatch(s -> s == 401);
		}
	}

	@Test
	void reuseRaceLeavesNoLiveDescendant() throws Exception {
		for (boolean rotateFirst : new boolean[] { true, false }) {
			AuthFixture f = fixture();
			Session ana = f.register();
			String a = ana.refreshToken();
			String b = JsonPath.read(
					f.refresh(a).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
					"$.refreshToken");
			UUID family = app.queryForObject("select family_id from refresh_tokens where user_id = ? limit 1",
					UUID.class, ana.userId());
			Callable<MockHttpServletResponse> rotate = () -> f.refresh(b).andReturn().getResponse();
			Callable<MockHttpServletResponse> reuse = () -> f.refresh(a).andReturn().getResponse();

			MockHttpServletResponse rotateResult;
			MockHttpServletResponse reuseResult;
			try (Connection held = DriverManager.getConnection(url, ownerUser, ownerPassword);
					ExecutorService pool = Executors.newFixedThreadPool(2)) {
				// the test holds the family lock, so both requests queue behind it in a known order
				held.setAutoCommit(false);
				try (PreparedStatement lock = held.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
					lock.setString(1, family.toString());
					lock.execute();
				}
				Future<MockHttpServletResponse> first = pool.submit(rotateFirst ? rotate : reuse);
				awaitLockWaiters(1);
				Future<MockHttpServletResponse> second = pool.submit(rotateFirst ? reuse : rotate);
				awaitLockWaiters(2);
				held.rollback();
				rotateResult = (rotateFirst ? first : second).get(30, TimeUnit.SECONDS);
				reuseResult = (rotateFirst ? second : first).get(30, TimeUnit.SECONDS);
			}

			assertThat(reuseResult.getStatus()).as("reuse of A, rotateFirst=%s", rotateFirst).isEqualTo(401);
			List<String> tokens = new ArrayList<>(List.of(a, b));
			if (rotateResult.getStatus() == 200) {
				tokens.add(JsonPath.read(rotateResult.getContentAsString(), "$.refreshToken"));
			}
			if (rotateFirst) {
				assertThat(rotateResult.getStatus()).as("rotation of B queued first").isEqualTo(200);
			}
			// reuse first: its rotate attempt and its revocation take the lock separately, so B may still rotate
			// in between (200) or not (401); either way the chain must end dead
			assertThat(liveTokens(ana.userId())).as("live tokens, rotateFirst=%s", rotateFirst).isZero();
			for (String token : tokens) {
				f.refresh(token).andExpect(status().isUnauthorized());
			}
		}
	}

	// A waiter that never shows up means the operation does not take the per-family lock.
	void awaitLockWaiters(int expected) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		int waiting = 0;
		while (System.nanoTime() < deadline) {
			waiting = app.queryForObject("select count(*) from pg_locks where locktype = 'advisory' and not granted",
					Integer.class);
			if (waiting == expected) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("expected %d requests waiting for the family lock, found %d".formatted(expected, waiting));
	}

	@Test
	void storesOnlyTokenHash() throws Exception {
		AuthFixture f = fixture();
		Session ana = f.register();

		assertThat(app.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class,
				(Object) sha256(ana.refreshToken()))).isEqualTo(1);
		assertThat(app.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class,
				(Object) ana.refreshToken().getBytes(StandardCharsets.UTF_8))).isZero();
	}

}
