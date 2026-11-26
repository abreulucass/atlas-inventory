package io.github.abreulucass.atlas.stock.internal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.abreulucass.atlas.stock.MovementType;
import io.github.abreulucass.atlas.stock.MovementView;

@Repository
public class MovementRepository {

	private final JdbcClient jdbc;

	MovementRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Serializes requests with the same key until the transaction ends, so the second one finds the first one's
	 * movement. The key is hashed to 64 bits: a collision only makes two keys wait for each other.
	 */
	public void lockKey(UUID tenant, String key) {
		jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:k, 0))").param("k", tenant + ":" + key).query(rs -> {
		});
	}

	public Optional<MovementView> findByKey(UUID tenant, String key) {
		return jdbc.sql("""
				select id, warehouse_id, product_id, type, quantity, balance_after, note, created_by, created_at
				from stock_movements where tenant_id = :t and idempotency_key = :key""")
			.param("t", tenant)
			.param("key", key)
			.query((rs, i) -> new MovementView(rs.getObject("id", UUID.class), rs.getObject("warehouse_id", UUID.class),
					rs.getObject("product_id", UUID.class), MovementType.valueOf(rs.getString("type")),
					rs.getBigDecimal("quantity"), rs.getBigDecimal("balance_after"), rs.getString("note"),
					rs.getObject("created_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
			.optional();
	}

	/** Inserts the movement for {@code tenant} (from {@code TenantContext}) and returns it as stored. */
	public MovementView insert(UUID tenant, UUID companyId, UUID warehouseId, MovementRequest request, UUID productId,
			BigDecimal balanceAfter, String key, UUID user) {
		UUID id = UUID.randomUUID();
		OffsetDateTime createdAt = jdbc.sql("""
				insert into stock_movements (id, tenant_id, company_id, warehouse_id, product_id, type, quantity,
				                             balance_after, note, idempotency_key, created_by)
				values (:id, :t, :c, :w, :p, :type, :q, :after, :note, :key, :user)
				returning created_at""")
			.param("id", id)
			.param("t", tenant)
			.param("c", companyId)
			.param("w", warehouseId)
			.param("p", productId)
			.param("type", request.type().name())
			.param("q", request.delta())
			.param("after", balanceAfter)
			.param("note", request.note())
			.param("key", key)
			.param("user", user)
			.query(OffsetDateTime.class)
			.single();
		return new MovementView(id, warehouseId, productId, request.type(), request.delta(), balanceAfter,
				request.note(), user, createdAt.toInstant());
	}

}
