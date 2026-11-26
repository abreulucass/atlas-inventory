package io.github.abreulucass.atlas.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Interleavings are forced, never raced: the test holds a lock, the requests queue behind it one by one (checked on
 * {@code pg_locks} through {@code pg_blocking_pids}) and the release lets them run (L-002, L-006).
 */
class TransferConcurrencyIT extends TransferITSupport {

	interface Lock {

		void take(Connection held, TScene scene) throws Exception;

	}

	static void inTenant(Connection held, TScene s) throws Exception {
		try (PreparedStatement tenant = held.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
			tenant.setString(1, s.tenant().toString());
			tenant.execute();
		}
	}

	/** Holds the existing balance row of the pair. */
	static Lock row(UUID warehouse, UUID product) {
		return (held, s) -> {
			inTenant(held, s);
			try (PreparedStatement statement = held.prepareStatement("""
					select 1 from stock_balances
					where tenant_id = ?::uuid and warehouse_id = ?::uuid and product_id = ?::uuid for update""")) {
				statement.setString(1, s.tenant().toString());
				statement.setString(2, warehouse.toString());
				statement.setString(3, product.toString());
				statement.execute();
			}
		};
	}

	/** Holds an uncommitted first row of the pair; rolled back, it leaves the requests to create the row. */
	static Lock firstRow(UUID company, UUID warehouse, UUID product) {
		return (held, s) -> {
			inTenant(held, s);
			try (PreparedStatement statement = held.prepareStatement("""
					insert into stock_balances (tenant_id, company_id, warehouse_id, product_id, sku, unit, quantity)
					values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'HELD', 'UN', 0)""")) {
				statement.setString(1, s.tenant().toString());
				statement.setString(2, company.toString());
				statement.setString(3, warehouse.toString());
				statement.setString(4, product.toString());
				statement.execute();
			}
		};
	}

	/** A request to send; the session is fixed so the call is repeatable. */
	interface Call extends Callable<MockHttpServletResponse> {
	}

	Call transferCall(TScene s, UUID from, UUID to, Object... items) {
		return () -> transfer(s.owner(), key(), transferBody(from, to, null, items)).andReturn().getResponse();
	}

	Call movementCall(TScene s, UUID company, UUID warehouse, String type, UUID product, int quantity) {
		return () -> send(s.owner(), s.movements(company, warehouse), key(), move(type, product, quantity, null))
			.andReturn()
			.getResponse();
	}

	/**
	 * Sends the calls in order, each queued behind the held lock before the next starts; then rolls the lock back. The
	 * held transaction is released before the executor is awaited, on every exit, so a failed wait cannot leave
	 * requests blocked on it (L-006).
	 */
	List<MockHttpServletResponse> underLock(TScene s, Lock lock, Call... calls) throws Exception {
		List<MockHttpServletResponse> responses = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(calls.length)) {
			List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
			try (Connection held = ownerTransaction()) {
				lock.take(held, s);
				for (Call call : calls) {
					futures.add(pool.submit(call));
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

	BigDecimal quantity(TScene s, UUID warehouse, UUID product) {
		return snapshot(s.tenant()).ledger()
			.balances()
			.stream()
			.filter(b -> b.get("warehouse_id").equals(warehouse) && b.get("product_id").equals(product))
			.map(b -> (BigDecimal) b.get("quantity"))
			.findFirst()
			.orElseThrow();
	}

	static List<Integer> statuses(List<MockHttpServletResponse> responses) {
		return responses.stream().map(MockHttpServletResponse::getStatus).toList();
	}

	/** The warehouse that Postgres orders last (uuid compares as unsigned bytes). */
	static UUID higher(UUID a, UUID b) {
		int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
		high = high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
		return high > 0 ? a : b;
	}

	@Test
	void transferAndOutOnSameBalanceOneWins() throws Exception {
		TScene s = tscene();
		UUID p3 = createProduct(s.owner(), sku(), "UN");
		stock(s, s.w1(), p3, 5);

		List<MockHttpServletResponse> responses = underLock(s, row(s.w1(), p3),
				transferCall(s, s.w1(), s.w2(), p3, 5), movementCall(s, s.companyA(), s.w1(), "OUT", p3, 5));

		assertThat(statuses(responses)).containsExactlyInAnyOrder(201, 409);
		Snapshot after = snapshot(s.tenant());
		boolean transferWon = responses.get(0).getStatus() == 201;
		assertThat(quantity(s, s.w1(), p3)).isEqualByComparingTo("0");
		if (transferWon) {
			assertThat(after.transfers()).hasSize(1);
			assertThat(quantity(s, s.w2(), p3)).isEqualByComparingTo("5");
		}
		else {
			// the OUT won: the transfer left no row at all, not even a destination balance
			assertThat(after.transfers()).isEmpty();
			assertThat(after.items()).isEmpty();
			assertThat(after.ledger().balances()).noneMatch(b -> b.get("warehouse_id").equals(s.w2()));
		}
		// the seed IN plus the one request that fit (an OUT, or an OUT and an IN of the transfer)
		assertThat(after.ledger().entries().stream().filter(e -> e.get("product_id").equals(p3)))
			.hasSize(transferWon ? 3 : 2);
	}

	@Test
	void oppositeTransfersBothCommitWithoutDeadlock() throws Exception {
		TScene s = tscene();
		stock(s, s.w2(), s.p1(), 10);
		stock(s, s.w2(), s.p2(), 10);
		stock(s, s.w1(), s.p2(), 5);
		// the held row is the one Postgres orders last of the four: each request locks its prefix and then queues
		UUID last = higher(s.w1(), s.w2());

		List<MockHttpServletResponse> responses = underLock(s, row(last, s.p2()),
				transferCall(s, s.w1(), s.w2(), s.p1(), 3, s.p2(), 2),
				transferCall(s, s.w2(), s.w1(), s.p1(), 4, s.p2(), 1));

		assertThat(statuses(responses)).containsOnly(201);
		// w1 p1: 10 - 3 + 4; w1 p2: 10 - 2 + 1; w2 p1: 10 + 3 - 4; w2 p2: 10 + 2 - 1
		assertThat(quantity(s, s.w1(), s.p1())).isEqualByComparingTo("11");
		assertThat(quantity(s, s.w1(), s.p2())).isEqualByComparingTo("9");
		assertThat(quantity(s, s.w2(), s.p1())).isEqualByComparingTo("9");
		assertThat(quantity(s, s.w2(), s.p2())).isEqualByComparingTo("11");
		assertThat(snapshot(s.tenant()).transfers()).hasSize(2);
	}

	@Test
	void twoTransfersIntoSameDestinationBothApply() throws Exception {
		TScene s = tscene();
		stock(s, s.w2(), s.p1(), 6);
		stock(s, s.w3(), s.p1(), 2);

		// an existing destination row, then a destination row that does not exist yet
		List<MockHttpServletResponse> existing = underLock(s, row(s.w3(), s.p1()),
				transferCall(s, s.w1(), s.w3(), s.p1(), 3), transferCall(s, s.w2(), s.w3(), s.p1(), 4));
		List<MockHttpServletResponse> fresh = underLock(s, firstRow(s.companyB(), s.w3(), s.p2()),
				transferCall(s, s.w1(), s.w3(), s.p2(), 2), transferCall(s, s.w1(), s.w3(), s.p2(), 3));

		assertThat(statuses(existing)).containsOnly(201);
		assertThat(statuses(fresh)).containsOnly(201);
		assertThat(quantity(s, s.w3(), s.p1())).isEqualByComparingTo("9");
		assertThat(quantity(s, s.w3(), s.p2())).isEqualByComparingTo("5");
		assertThat(quantity(s, s.w1(), s.p1())).isEqualByComparingTo("7");
		assertThat(quantity(s, s.w2(), s.p1())).isEqualByComparingTo("2");
		assertThat(quantity(s, s.w1(), s.p2())).isEqualByComparingTo("0");
		assertThat(snapshot(s.tenant()).ledger().balances()).filteredOn(b -> b.get("warehouse_id").equals(s.w3()))
			.hasSize(2)
			.noneMatch(b -> "HELD".equals(b.get("sku")));
	}

	/** A mix of transfers and movements, queued behind one held row, run twice. */
	void mixedRuns(TScene s) throws Exception {
		stock(s, s.w2(), s.p1(), 6);
		stock(s, s.w2(), s.p2(), 6);
		for (int round = 0; round < 2; round++) {
			underLock(s, row(higher(s.w1(), s.w2()), s.p1()), transferCall(s, s.w1(), s.w2(), s.p1(), 3, s.p2(), 1),
					transferCall(s, s.w2(), s.w1(), s.p1(), 2, s.p2(), 2),
					movementCall(s, s.companyA(), s.w1(), "OUT", s.p1(), 4),
					movementCall(s, s.companyA(), s.w2(), "IN", s.p1(), 1),
					transferCall(s, s.w1(), s.w3(), s.p2(), 1, s.p1(), 1));
		}
	}

	@Test
	void balancesEqualSumOfMovements() throws Exception {
		TScene s = tscene();

		mixedRuns(s);

		assertThat(snapshot(s.tenant()).transfers()).isNotEmpty();
		List<Map<String, Object>> mismatches = owner(s, """
				select b.warehouse_id, b.product_id, b.quantity, coalesce(sum(m.quantity), 0) as total
				from stock_balances b
				left join stock_movements m on m.tenant_id = b.tenant_id and m.warehouse_id = b.warehouse_id
				     and m.product_id = b.product_id
				group by b.warehouse_id, b.product_id, b.quantity
				having b.quantity <> coalesce(sum(m.quantity), 0)""");
		assertThat(mismatches).isEmpty();
		assertThat(snapshot(s.tenant()).ledger().balances()).hasSizeGreaterThan(3)
			.allSatisfy(b -> assertThat((BigDecimal) b.get("quantity")).isGreaterThanOrEqualTo(BigDecimal.ZERO));
	}

	@Test
	void transferMovementsSumToZeroPerProduct() throws Exception {
		TScene s = tscene();

		mixedRuns(s);

		assertThat(owner(s, """
				select transfer_id, product_id, sum(quantity) as total from stock_movements
				where transfer_id is not null group by transfer_id, product_id having sum(quantity) <> 0""")).isEmpty();
		// each product of each transfer has exactly its OUT and its IN
		assertThat(owner(s, """
				select transfer_id, product_id from stock_movements where transfer_id is not null
				group by transfer_id, product_id having count(*) <> 2""")).isEmpty();
		assertThat(owner(s, "select distinct transfer_id from stock_movements where transfer_id is not null"))
			.hasSize(snapshot(s.tenant()).transfers().size())
			.isNotEmpty();
	}

	List<Map<String, Object>> owner(TScene s, String sql) {
		SingleConnectionDataSource connection = new SingleConnectionDataSource(url, ownerUser, ownerPassword, true);
		try {
			JdbcTemplate owner = new JdbcTemplate(connection);
			owner.queryForObject("select set_config('app.tenant_id', ?, false)", String.class, s.tenant().toString());
			return owner.queryForList(sql);
		}
		finally {
			connection.destroy();
		}
	}

}
