package io.github.abreulucass.atlas.stock.internal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
