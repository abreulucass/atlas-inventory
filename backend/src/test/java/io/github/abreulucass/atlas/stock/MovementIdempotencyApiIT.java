package io.github.abreulucass.atlas.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import com.jayway.jsonpath.JsonPath;

class MovementIdempotencyApiIT extends StockITSupport {

	String key() {
		return UUID.randomUUID().toString();
	}

	String sendOk(Session as, String path, String key, String json) throws Exception {
		return send(as, path, key, json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
	}

	@Test
	void sameKeySameRequestReplaysOriginal() throws Exception {
		Scene s = scene();
		Session admin = joinAs(s.owner(), "ADMIN");
		Session member = memberWithGrant(s.owner(), "warehouseId", s.warehouse());
		Session stranger = joinAs(s.owner(), "MEMBER");
		String key = key();
		String json = move("IN", s.product(), 10, "first");
		String original = sendOk(s.owner(), s.path(), key, json);
		// the live balance moves on with another key; a replay must still answer the original snapshot
		sendOk(s.owner(), s.path(), key(), move("IN", s.product(), 5, null));
		Ledger before = ledger(s.owner().organizationId());
		assertThat(balanceOf(s.owner().organizationId(), s.warehouse(), s.product())).isEqualByComparingTo("15");

		// the note is stripped before comparison, so a padded note is the same request
		for (Session as : List.of(s.owner(), admin, member)) {
			assertThat(sendOk(as, s.path(), key, json)).isEqualTo(original);
			assertThat(sendOk(as, s.path(), key, move("IN", s.product(), 10, "  first  "))).isEqualTo(original);
		}
		assertThat(number(original, "$.balanceAfter")).isEqualByComparingTo("10");
		// the path warehouse is resolved before the key: a member who does not see it learns nothing
		send(stranger, s.path(), key, json).andExpect(status().isNotFound());
		assertThat(ledger(s.owner().organizationId())).isEqualTo(before);
	}

	@Test
	void sameKeyDifferentRequestReturns422() throws Exception {
		Scene s = scene();
		UUID otherProduct = createProduct(s.owner(), "OTHER", "UN");
		UUID otherWarehouse = createWarehouse(s.owner(), s.company());
		Session admin = joinAs(s.owner(), "ADMIN");
		String key = key();
		sendOk(s.owner(), s.path(), key, move("IN", s.product(), 10, "first"));
		String otherPath = "/api/companies/" + s.company() + "/warehouses/" + otherWarehouse + "/movements";
		Ledger before = ledger(s.owner().organizationId());

		send(s.owner(), s.path(), key, move("IN", s.product(), 11, "first")).andExpect(status().isUnprocessableContent());
		send(admin, s.path(), key, move("OUT", s.product(), 10, "first")).andExpect(status().isUnprocessableContent());
		send(s.owner(), s.path(), key, move("ADJUSTMENT", s.product(), 10, "first"))
			.andExpect(status().isUnprocessableContent());
		send(s.owner(), s.path(), key, move("IN", otherProduct, 10, "first")).andExpect(status().isUnprocessableContent());
		send(s.owner(), s.path(), key, move("IN", s.product(), 10, "second")).andExpect(status().isUnprocessableContent());
		send(s.owner(), s.path(), key, move("IN", s.product(), 10, null)).andExpect(status().isUnprocessableContent());
		send(s.owner(), otherPath, key, move("IN", s.product(), 10, "first")).andExpect(status().isUnprocessableContent());
		// a product id that is not a UUID is a different request, not a 404: the key answers first
		send(s.owner(), s.path(), key, move("IN", "nope", 10, "first")).andExpect(status().isUnprocessableContent());
		assertThat(ledger(s.owner().organizationId())).isEqualTo(before);
	}

	@Test
	void replayAndKeyReuseAnswerBeforeLaterRules() throws Exception {
		Scene s = scene();
		String key = key();
		sendOk(s.owner(), s.path(), key(), move("IN", s.product(), 10, null));
		String original = sendOk(s.owner(), s.path(), key, move("OUT", s.product(), 5, null));
		sendOk(s.owner(), s.path(), key(), move("OUT", s.product(), 5, null));
		// from here the product is archived and the balance is 0: a new OUT would be refused twice over
		archive(s.owner(), "/api/products/" + s.product());
		Ledger before = ledger(s.owner().organizationId());

		// a replay beats 404 product lookup, 400 whole quantity, 409 archived and 409 insufficient: its own result
		assertThat(sendOk(s.owner(), s.path(), key, move("OUT", s.product(), 5, null))).isEqualTo(original);
		// 400 body is checked before the key is consulted
		send(s.owner(), s.path(), key, "{\"type\": \"OUT\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value(not(containsString("different request"))));
		// key reuse (422) beats 404 product, 400 whole quantity, 409 archived and 409 insufficient
		for (String json : List.of(move("OUT", UUID.randomUUID(), 5, null), move("OUT", s.product(), "5.5", null),
				move("OUT", s.product(), 6, null), move("IN", s.product(), 5, null))) {
			send(s.owner(), s.path(), key, json).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.detail").value(containsString("different request")));
		}
		assertThat(ledger(s.owner().organizationId())).isEqualTo(before);
	}

	@Test
	void concurrentSameKeyRecordsOnce() throws Exception {
		Scene s = scene();
		String key = key();
		String json = move("IN", s.product(), 10, null);
		MockHttpServletResponse first;
		MockHttpServletResponse second;
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<MockHttpServletResponse> a;
			Future<MockHttpServletResponse> b;
			// the test holds the key's lock, so both requests queue behind it and the winner is decided after release;
			// the lock is released before the pool is awaited, on every exit (L-006)
			try (Connection held = ownerTransaction()) {
				try (PreparedStatement lock = held
					.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
					lock.setString(1, s.owner().organizationId() + ":" + key);
					lock.execute();
				}
				a = pool.submit(() -> send(s.owner(), s.path(), key, json).andReturn().getResponse());
				b = pool.submit(() -> send(s.owner(), s.path(), key, json).andReturn().getResponse());
				awaitLockWaiters(held, 2);
				held.rollback();
			}
			first = a.get(30, TimeUnit.SECONDS);
			second = b.get(30, TimeUnit.SECONDS);
		}

		assertThat(first.getStatus()).isEqualTo(201);
		assertThat(second.getStatus()).isEqualTo(201);
		assertThat((String) JsonPath.read(first.getContentAsString(), "$.id"))
			.isEqualTo(JsonPath.read(second.getContentAsString(), "$.id"));
		Ledger ledger = ledger(s.owner().organizationId());
		assertThat(ledger.movements()).isEqualTo(1);
		assertThat((java.math.BigDecimal) ledger.balances().get(0).get("quantity")).isEqualByComparingTo("10");
	}

	@Test
	void failedRequestDoesNotReserveKey() throws Exception {
		Scene s = scene();
		String key = key();
		Ledger before = ledger(s.owner().organizationId());

		send(s.owner(), s.path(), key, "{\"type\": \"IN\"}").andExpect(status().isBadRequest());
		send(s.owner(), s.path(), key, move("IN", UUID.randomUUID(), 5, null)).andExpect(status().isNotFound());
		send(s.owner(), s.path(), key, move("OUT", s.product(), 5, null)).andExpect(status().isConflict());
		assertThat(ledger(s.owner().organizationId())).isEqualTo(before);

		String body = sendOk(s.owner(), s.path(), key, move("IN", s.product(), 5, null));
		assertThat(number(body, "$.balanceAfter")).isEqualByComparingTo("5");
		assertThat(ledger(s.owner().organizationId()).movements()).isEqualTo(1);
	}

	@Test
	void keyOfAnotherOrganizationIsIndependent() throws Exception {
		Scene a = scene();
		Scene b = scene();
		String key = key();

		String first = sendOk(a.owner(), a.path(), key, move("IN", a.product(), 3, null));
		String second = sendOk(b.owner(), b.path(), key, move("IN", b.product(), 3, null));

		assertThat((String) JsonPath.read(first, "$.id")).isNotEqualTo(JsonPath.read(second, "$.id"));
		assertThat(ledger(a.owner().organizationId()).movements()).isEqualTo(1);
		assertThat(ledger(b.owner().organizationId()).movements()).isEqualTo(1);
	}

	@Test
	void replayAfterProductArchivedReturnsOriginal() throws Exception {
		Scene s = scene();
		Session admin = joinAs(s.owner(), "ADMIN");
		String key = key();
		String json = move("IN", s.product(), 7, null);
		String original = sendOk(s.owner(), s.path(), key, json);
		sendOk(s.owner(), s.path(), key(), move("IN", s.product(), 2, null));
		archive(s.owner(), "/api/products/" + s.product());
		Ledger before = ledger(s.owner().organizationId());

		for (Session as : List.of(s.owner(), admin)) {
			assertThat(sendOk(as, s.path(), key, json)).isEqualTo(original);
		}
		// a new key on the archived product is still refused
		send(s.owner(), s.path(), key(), json).andExpect(status().isConflict());
		assertThat(ledger(s.owner().organizationId())).isEqualTo(before);
	}

	@Test
	void numericallyEqualQuantityReplays() throws Exception {
		Scene s = scene();
		UUID kg = createProduct(s.owner(), "KG1", "KG");
		String key = key();
		String original = sendOk(s.owner(), s.path(), key, move("IN", s.product(), "10.0", null));
		String keyKg = key();
		String originalKg = sendOk(s.owner(), s.path(), keyKg, move("IN", kg, "2.50", null));
		Ledger before = ledger(s.owner().organizationId());

		String replay = sendOk(s.owner(), s.path(), key, move("IN", s.product(), 10, null));
		String replayKg = sendOk(s.owner(), s.path(), keyKg, move("IN", kg, "2.5", null));

		assertThat((String) JsonPath.read(replay, "$.id")).isEqualTo(JsonPath.read(original, "$.id"));
		assertThat((String) JsonPath.read(replayKg, "$.id")).isEqualTo(JsonPath.read(originalKg, "$.id"));
		assertThat(ledger(s.owner().organizationId())).isEqualTo(before);
	}

}
