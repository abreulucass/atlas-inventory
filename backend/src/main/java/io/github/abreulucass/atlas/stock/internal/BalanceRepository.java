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
