package io.github.abreulucass.atlas.stock.internal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Balance writes as single statements: the row lock each takes serializes movements on one (warehouse, product). */
@Repository
public class BalanceRepository {

	private final JdbcClient jdbc;

	BalanceRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public record Row(UUID productId, String sku, String unit, BigDecimal quantity, java.time.Instant updatedAt) {
	}

	public record Stock(UUID companyId, UUID warehouseId, BigDecimal quantity) {
	}

	public List<Stock> ofProduct(UUID tenant, UUID productId) {
		return jdbc.sql("select company_id, warehouse_id, quantity from stock_balances where tenant_id = :t and product_id = :p")
			.param("t", tenant)
			.param("p", productId)
			.query((rs, i) -> new Stock(rs.getObject("company_id", UUID.class), rs.getObject("warehouse_id", UUID.class),
					rs.getBigDecimal("quantity")))
			.list();
	}

	public long count(UUID tenant, UUID warehouseId) {
		return jdbc.sql("select count(*) from stock_balances where tenant_id = :t and warehouse_id = :w")
			.param("t", tenant)
			.param("w", warehouseId)
			.query(Long.class)
			.single();
	}

	public List<Row> page(UUID tenant, UUID warehouseId, int size, long offset) {
		return jdbc.sql("""
				select product_id, sku, unit, quantity, updated_at from stock_balances
				where tenant_id = :t and warehouse_id = :w order by sku limit :size offset :offset""")
			.param("t", tenant)
			.param("w", warehouseId)
			.param("size", size)
			.param("offset", offset)
			.query((rs, i) -> new Row(rs.getObject("product_id", UUID.class), rs.getString("sku"),
					rs.getString("unit"), rs.getBigDecimal("quantity"),
					rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
			.list();
	}

	public record Slot(UUID warehouseId, UUID productId) {
	}

	/**
	 * Takes a transaction advisory lock per distinct (warehouse, product) slot, present or not, in ascending key order
	 * (signed, as PostgreSQL orders bigint). Call before any balance read or write of a multi-slot operation: a row
	 * snapshot cannot order slots that do not exist yet, and the key order can.
	 */
	public void lockSlots(UUID tenant, java.util.Collection<Slot> slots) {
		var keys = new java.util.TreeSet<Long>();
		for (Slot slot : new java.util.HashSet<>(slots)) {
			keys.add(jdbc.sql("select hashtextextended('stock-slot:' || :t || ':' || :w || ':' || :p, 0)")
				.param("t", tenant.toString())
				.param("w", slot.warehouseId().toString())
				.param("p", slot.productId().toString())
				.query(Long.class)
				.single());
		}
		// shortcut: a 64-bit hash collision between a slot key and an idempotency-key lock could in theory cross-wait; use a two-int key space if that matters
		for (long key : keys) {
			jdbc.sql("select pg_advisory_xact_lock(:k)").param("k", key).query(rs -> {
			});
		}
	}

	/**
	 * Locks the existing balance rows of the products in both warehouses in one statement ordered by (warehouse,
	 * product), and returns their quantities; a missing row is absent from the map. Every path that locks several
	 * balance rows must use this order, or two transactions can deadlock.
	 */
	public java.util.Map<Slot, BigDecimal> lockForTransfer(UUID tenant, UUID from, UUID to,
			java.util.Collection<UUID> products) {
		var locked = new java.util.HashMap<Slot, BigDecimal>();
		jdbc.sql("""
				select warehouse_id, product_id, quantity from stock_balances
				where tenant_id = :t and warehouse_id in (:w) and product_id in (:p)
				order by warehouse_id, product_id for update""")
			.param("t", tenant)
			.param("w", List.of(from, to))
			.param("p", products)
			.query((rs, i) -> locked.put(new Slot(rs.getObject("warehouse_id", UUID.class),
					rs.getObject("product_id", UUID.class)), rs.getBigDecimal("quantity")))
			.list();
		return locked;
	}

	/**
	 * Adds {@code delta} to the balance and returns the new quantity; empty when a negative delta would take it below
	 * zero (a missing row counts as zero). Call inside the tenant transaction; {@code tenant} comes from
	 * {@code TenantContext}.
	 */
	public Optional<BigDecimal> apply(UUID tenant, UUID companyId, UUID warehouseId, UUID productId, String sku,
			String unit, BigDecimal delta) {
		if (delta.signum() > 0) {
			// ON CONFLICT lets concurrent first movements converge on one row
			return jdbc.sql("""
					insert into stock_balances (tenant_id, company_id, warehouse_id, product_id, sku, unit, quantity)
					values (:t, :c, :w, :p, :sku, :unit, :d)
					on conflict (tenant_id, warehouse_id, product_id)
					do update set quantity = stock_balances.quantity + excluded.quantity, updated_at = clock_timestamp()
					returning quantity""")
				.param("t", tenant)
				.param("c", companyId)
				.param("w", warehouseId)
				.param("p", productId)
				.param("sku", sku)
				.param("unit", unit)
				.param("d", delta)
				.query(BigDecimal.class)
				.optional();
		}
		return jdbc.sql("""
				update stock_balances set quantity = quantity + :d, updated_at = clock_timestamp()
				where tenant_id = :t and warehouse_id = :w and product_id = :p and quantity + :d >= 0
				returning quantity""")
			.param("d", delta)
			.param("t", tenant)
			.param("w", warehouseId)
			.param("p", productId)
			.query(BigDecimal.class)
			.optional();
	}

}
