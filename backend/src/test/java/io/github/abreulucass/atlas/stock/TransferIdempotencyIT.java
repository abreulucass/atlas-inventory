package io.github.abreulucass.atlas.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import com.jayway.jsonpath.JsonPath;

class TransferIdempotencyIT extends TransferITSupport {

	String sendOk(Session as, String key, String json) throws Exception {
		return transfer(as, key, json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
	}

	@Test
	void sameKeySameRequestReplaysOriginal() throws Exception {
		TScene s = tscene();
		Session admin = joinAs(s.owner(), "ADMIN");
		Session member = memberWith(s, "warehouseId", s.w1(), s.w2());
		Session stranger = joinAs(s.owner(), "MEMBER");
		String key = key();
		String json = transferBody(s.w1(), s.w2(), "first", s.p1(), 4, s.p2(), 5);
		String original = sendOk(s.owner(), key, json);
		// the live balances move on with another key; a replay must still answer the original body
		transferOk(s.owner(), transferBody(s.w1(), s.w2(), null, s.p1(), 1));
		Snapshot before = snapshot(s.tenant());

		for (Session as : List.of(s.owner(), admin, member)) {
			assertThat(sendOk(as, key, json)).isEqualTo(original);
			// the note is stripped before comparison, so a padded note is the same request
			assertThat(sendOk(as, key, transferBody(s.w1(), s.w2(), "  first  ", s.p1(), 4, s.p2(), 5)))
				.isEqualTo(original);
		}
		assertThat(snapshot(s.tenant())).isEqualTo(before);
		assertThat(before.transfers()).hasSize(2);
		// the warehouses are resolved before the key: a member who does not see them learns nothing
		transfer(stranger, key, json).andExpect(status().isNotFound());
		assertThat(snapshot(s.tenant())).isEqualTo(before);
	}

	@Test
	void reorderedItemsAreSameRequest() throws Exception {
		TScene s = tscene();
		String key = key();
		String original = sendOk(s.owner(), key, transferBody(s.w1(), s.w2(), null, s.p1(), 4, s.p2(), 5));
		Snapshot before = snapshot(s.tenant());

		for (Session as : List.of(s.owner(), joinAs(s.owner(), "ADMIN"), memberWith(s, "warehouseId", s.w1(), s.w2()))) {
			String replay = sendOk(as, key, transferBody(s.w1(), s.w2(), null, s.p2(), "5.0", s.p1(), "4.00"));
			assertThat(replay).isEqualTo(original);
			// items stay in the original order
			assertThat((String) JsonPath.read(replay, "$.items[0].productId")).isEqualTo(s.p1().toString());
		}
		assertThat(snapshot(s.tenant())).isEqualTo(before);
	}

	@Test
	void sameKeyDifferentRequestIs422() throws Exception {
		TScene s = tscene();
		String key = key();
		sendOk(s.owner(), key, transferBody(s.w1(), s.w2(), "a", s.p1(), 4, s.p2(), 3));
		Snapshot before = snapshot(s.tenant());
		UUID p3 = createProduct(s.owner(), sku(), "UN");
		stock(s, s.w1(), p3, 1);
		before = snapshot(s.tenant());

		for (Session as : List.of(s.owner(), joinAs(s.owner(), "ADMIN"), memberWith(s, "warehouseId", s.w1(), s.w2(), s.w3()))) {
			for (String other : List.of(transferBody(s.w1(), s.w3(), "a", s.p1(), 4, s.p2(), 3),
					transferBody(s.w2(), s.w1(), "a", s.p1(), 4, s.p2(), 3),
					transferBody(s.w1(), s.w2(), "a", s.p1(), 5, s.p2(), 3),
					transferBody(s.w1(), s.w2(), "b", s.p1(), 4, s.p2(), 3),
					transferBody(s.w1(), s.w2(), null, s.p1(), 4, s.p2(), 3),
					transferBody(s.w1(), s.w2(), "a", s.p1(), 4, s.p2(), 3, p3, 1),
					transferBody(s.w1(), s.w2(), "a", s.p1(), 4),
					transferBody(s.w1(), s.w2(), "a", s.p1(), 4, p3, 3))) {
				transfer(as, key, other).andExpect(status().isUnprocessableContent())
					.andExpect(jsonPath("$.detail").value(containsString("Idempotency-Key")));
				assertThat(snapshot(s.tenant())).isEqualTo(before);
			}
		}
	}

	@Test
	void keyNamespaceIsSharedWithMovements() throws Exception {
		TScene s = tscene();
		String path = s.movements(s.companyA(), s.w1());
		String movementKey = key();
		String transferKey = key();
		send(s.owner(), path, movementKey, move("IN", s.p1(), 1, null)).andExpect(status().isCreated());
		sendOk(s.owner(), transferKey, transferBody(s.w1(), s.w2(), null, s.p1(), 1));
		Snapshot before = snapshot(s.tenant());

		for (Session as : List.of(s.owner(), joinAs(s.owner(), "ADMIN"),
				memberWith(s, "warehouseId", s.w1(), s.w2()))) {
			// a movement's key on a transfer, and a transfer's key on a movement (same request or not)
			transfer(as, movementKey, transferBody(s.w1(), s.w2(), null, s.p1(), 1))
				.andExpect(status().isUnprocessableContent());
			send(as, path, transferKey, move("IN", s.p1(), 1, null)).andExpect(status().isUnprocessableContent());
			send(as, path, transferKey, move("OUT", s.p1(), 1, null)).andExpect(status().isUnprocessableContent());
			assertThat(snapshot(s.tenant())).isEqualTo(before);
		}
	}

	@Test
	void concurrentSameKeyRecordsOnce() throws Exception {
		TScene s = tscene();
		String key = key();
		String json = transferBody(s.w1(), s.w2(), null, s.p1(), 4, s.p2(), 5);
		List<MockHttpServletResponse> responses = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
			// the advisory lock every request takes first is held from a test connection, so both queue behind it
			try (Connection held = ownerTransaction()) {
				try (var statement = held.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
					statement.setString(1, s.tenant() + ":" + key);
					statement.execute();
				}
				for (Session as : List.of(s.owner(), joinAs(s.owner(), "ADMIN"))) {
					futures.add(pool.submit(() -> transfer(as, key, json).andReturn().getResponse()));
					awaitLockWaiters(held, futures.size());
				}
				held.rollback();
			}
			for (Future<MockHttpServletResponse> future : futures) {
				responses.add(future.get(30, TimeUnit.SECONDS));
			}
		}

		assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsOnly(201);
		assertThat(JsonPath.<String>read(responses.get(0).getContentAsString(), "$.id"))
			.isEqualTo(JsonPath.<String>read(responses.get(1).getContentAsString(), "$.id"));
		Snapshot after = snapshot(s.tenant());
		assertThat(after.transfers()).hasSize(1);
		assertThat(after.items()).hasSize(2);
		assertThat(quantity(after, s.w1(), s.p1())).isEqualByComparingTo("6");
		assertThat(quantity(after, s.w2(), s.p1())).isEqualByComparingTo("4");
		assertThat(quantity(after, s.w2(), s.p2())).isEqualByComparingTo("5");
		// the two IN seed movements plus one OUT and one IN per item, once
		assertThat(after.ledger().entries().stream().filter(e -> e.get("transfer_id") != null)).hasSize(4);
	}

	java.math.BigDecimal quantity(Snapshot snapshot, UUID warehouse, UUID product) {
		return snapshot.ledger()
			.balances()
			.stream()
			.filter(b -> b.get("warehouse_id").equals(warehouse) && b.get("product_id").equals(product))
			.map(b -> (java.math.BigDecimal) b.get("quantity"))
			.findFirst()
			.orElseThrow();
	}

	@Test
	void failedRequestDoesNotReserveKey() throws Exception {
		TScene s = tscene();
		stock(s, s.w1(), s.p1(), 50);
		UUID unknown = UUID.randomUUID();
		String good = transferBody(s.w1(), s.w2(), null, s.p1(), 1);

		for (Session as : List.of(s.owner(), joinAs(s.owner(), "ADMIN"), memberWith(s, "warehouseId", s.w1(), s.w2()))) {
			String[][] failures = {
					{ "400", "{\"fromWarehouseId\": \"%s\"}".formatted(s.w1()) },
					{ "404", transferBody(unknown, s.w2(), null, s.p1(), 1) },
					{ "404", transferBody(s.w1(), s.w2(), null, unknown, 1) },
					{ "409", transferBody(s.w1(), s.w2(), null, s.p1(), 99) } };
			for (String[] failure : failures) {
				String key = key();
				Snapshot before = snapshot(s.tenant());
				transfer(as, key, failure[1]).andExpect(status().is(Integer.parseInt(failure[0])));
				assertThat(snapshot(s.tenant())).isEqualTo(before);

				String body = sendOk(as, key, good);

				assertThat(snapshot(s.tenant()).transfers()).hasSize(before.transfers().size() + 1);
				assertThat(sendOk(as, key, good)).isEqualTo(body);
			}
		}
	}

	@Test
	void replayAfterArchiveReturnsOriginal() throws Exception {
		TScene s = tscene();
		String key = key();
		String json = transferBody(s.w1(), s.w2(), "n", s.p1(), 4);
		String original = sendOk(s.owner(), key, json);
		Session admin = joinAs(s.owner(), "ADMIN");
		Session member = memberWith(s, "warehouseId", s.w1(), s.w2());
		archive(s.owner(), "/api/products/" + s.p1());
		archive(s.owner(), "/api/companies/" + s.companyA() + "/warehouses/" + s.w2());
		archive(s.owner(), "/api/companies/" + s.companyA() + "/warehouses/" + s.w1());
		archive(s.owner(), "/api/companies/" + s.companyA());
		Snapshot before = snapshot(s.tenant());

		for (Session as : List.of(s.owner(), admin, member)) {
			assertThat(sendOk(as, key, json)).isEqualTo(original);
		}
		assertThat(snapshot(s.tenant())).isEqualTo(before);
		// a new key on the same archived resources is refused
		transfer(s.owner(), key(), json).andExpect(status().isConflict());
	}

}
