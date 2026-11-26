package io.github.abreulucass.atlas.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import com.jayway.jsonpath.JsonPath;

/**
 * Interleavings are forced, never raced: the test holds a lock on the balance, the requests queue behind it one by one
 * (checked on {@code pg_locks}) and the release lets them run (L-006).
 */
class MovementConcurrencyIT extends StockITSupport {

	interface Lock {

		void take(Connection held, Scene scene) throws Exception;

	}

	/** Holds the existing balance row. */
	static final Lock ROW = (held, s) -> execute(held, s,
			"select 1 from stock_balances where tenant_id = ?::uuid and warehouse_id = ?::uuid and product_id = ?::uuid for update");

	/** Holds an uncommitted first row of the pair at quantity 0; rolled back, it leaves the requests to create the row. */
	static final Lock FIRST_ROW = (held, s) -> execute(held, s, """
			insert into stock_balances (tenant_id, company_id, warehouse_id, product_id, sku, unit, quantity)
			select ?::uuid, company_id, ?::uuid, id, sku, unit, 0
			from (select ?::uuid as id, 'SKU' as sku, 'UN' as unit, (select company_id from warehouses where id = ?::uuid) as company_id) p""");

	static void execute(Connection held, Scene s, String sql) throws Exception {
		try (PreparedStatement tenant = held.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
			tenant.setString(1, s.owner().organizationId().toString());
			tenant.execute();
		}
		try (PreparedStatement statement = held.prepareStatement(sql)) {
			if (sql.startsWith("select")) {
				statement.setString(1, s.owner().organizationId().toString());
				statement.setString(2, s.warehouse().toString());
				statement.setString(3, s.product().toString());
			}
			else {
				statement.setString(1, s.owner().organizationId().toString());
				statement.setString(2, s.warehouse().toString());
				statement.setString(3, s.product().toString());
				statement.setString(4, s.warehouse().toString());
			}
			statement.execute();
		}
	}

	/**
	 * Sends the movements in order, each queued behind the held lock before the next starts; then rolls the lock back.
	 * The held transaction is released before the executor is awaited, on every exit, so a failed wait cannot leave
	 * requests blocked on it (L-006).
	 */
	List<MockHttpServletResponse> underLock(Scene s, Lock lock, String... bodies) throws Exception {
		List<MockHttpServletResponse> responses = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(bodies.length)) {
			List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
			try (Connection held = ownerTransaction()) {
				lock.take(held, s);
				for (String body : bodies) {
					futures.add(pool.submit(() -> send(s.owner(), s.path(), UUID.randomUUID().toString(), body)
						.andReturn()
						.getResponse()));
					awaitLockWaiters(held, futures.size());
				}
				held.rollback();
			}
			for (Future<MockHttpServletResponse> future : futures) {
				responses.add(future.get(30, TimeUnit.SECONDS));
			}
		}
		return responses;
	}

	BigDecimal balance(Scene s) {
		return (BigDecimal) ledger(s.owner().organizationId()).balances().get(0).get("quantity");
	}

	@Test
	void concurrentOutsNeverOversell() throws Exception {
		Scene s = scene();
		record(s.owner(), s.path(), move("IN", s.product(), 5, null));

		List<MockHttpServletResponse> responses = underLock(s, ROW, move("OUT", s.product(), 5, null),
				move("OUT", s.product(), 5, null));

		assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 409);
		assertThat(balance(s)).isEqualByComparingTo("0");
		// the IN and the one OUT that fit
		assertThat(ledger(s.owner().organizationId()).movements()).isEqualTo(2);
	}

	@Test
	void concurrentInsLoseNoUpdate() throws Exception {
		Scene s = scene();
		String seed = record(s.owner(), s.path(), move("IN", s.product(), 10, null));

		List<MockHttpServletResponse> responses = underLock(s, ROW, move("IN", s.product(), 3, null),
				move("IN", s.product(), 4, null));

		assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsOnly(201);
		assertThat(balance(s)).isEqualByComparingTo("17");
		assertThat(responses.stream()
			.map(r -> number(content(r), "$.balanceAfter").doubleValue())
			.toList()).containsExactlyInAnyOrder(13.0, 17.0);
		// the ledger holds the seed and both increments, with the ids and balances the responses reported
		for (MockHttpServletResponse response : responses) {
			String body = content(response);
			boolean three = number(body, "$.quantity").compareTo(BigDecimal.valueOf(3)) == 0;
			assertPersisted(s, s.product(), body, "IN", three ? "3" : "4", number(body, "$.balanceAfter").toPlainString(),
					s.owner().userId(), null);
		}
		assertThat(ledger(s.owner().organizationId()).entries()).extracting(e -> e.get("id").toString())
			.containsExactlyInAnyOrder((String) JsonPath.read(seed, "$.id"),
					(String) JsonPath.read(content(responses.get(0)), "$.id"),
					(String) JsonPath.read(content(responses.get(1)), "$.id"));
	}

	@Test
	void concurrentFirstMovementsShareOneRow() throws Exception {
		Scene s = scene();

		List<MockHttpServletResponse> responses = underLock(s, FIRST_ROW, move("IN", s.product(), 3, null),
				move("IN", s.product(), 4, null));

		assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsOnly(201);
		List<Map<String, Object>> balances = ledger(s.owner().organizationId()).balances();
		assertThat(balances).hasSize(1);
		assertThat((BigDecimal) balances.get(0).get("quantity")).isEqualByComparingTo("7");
		// the blocker's placeholder row (sku 'SKU') was rolled back: this row was created by the two requests
		assertThat(balances.get(0)).containsEntry("product_id", s.product()).doesNotContainEntry("sku", "SKU");
		assertThat(ledger(s.owner().organizationId()).movements()).isEqualTo(2);
	}

	@Test
	void balanceEqualsSumOfMovements() throws Exception {
		Scene s = scene();
		UUID second = createProduct(s.owner(), "SECOND", "UN");
		record(s.owner(), s.path(), move("IN", s.product(), 5, null));
		record(s.owner(), s.path(), move("IN", second, 5, null));

		// whichever order the queue drains in, the invariant must hold; the OUTs may or may not both fit
		underLock(s, ROW, move("OUT", s.product(), 3, null), move("OUT", s.product(), 3, null),
				move("IN", s.product(), 2, null), move("ADJUSTMENT", s.product(), -1, "recount"));
		underLock(s, ROW, move("IN", s.product(), 3, null), move("OUT", s.product(), 3, null));

		List<Map<String, Object>> mismatches = sumMismatches(s.owner().organizationId());
		assertThat(mismatches).isEmpty();
		assertThat(balance(s)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
	}

	/** Balance rows whose quantity differs from the sum of their movements' signed quantities. */
	List<Map<String, Object>> sumMismatches(UUID tenant) {
		var connection = new org.springframework.jdbc.datasource.SingleConnectionDataSource(url, ownerUser,
				ownerPassword, true);
		try {
			var owner = new org.springframework.jdbc.core.JdbcTemplate(connection);
			owner.queryForObject("select set_config('app.tenant_id', ?, false)", String.class, tenant.toString());
			assertThat(owner.queryForObject("select count(*) from stock_balances", Integer.class)).isPositive();
			return owner.queryForList("""
					select b.warehouse_id, b.product_id, b.quantity, coalesce(sum(m.quantity), 0) as total
					from stock_balances b
					left join stock_movements m on m.tenant_id = b.tenant_id and m.warehouse_id = b.warehouse_id
					     and m.product_id = b.product_id
					group by b.warehouse_id, b.product_id, b.quantity
					having b.quantity <> coalesce(sum(m.quantity), 0)""");
		}
		finally {
			connection.destroy();
		}
	}

	static String content(MockHttpServletResponse response) {
		try {
			return response.getContentAsString();
		}
		catch (java.io.UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

}
