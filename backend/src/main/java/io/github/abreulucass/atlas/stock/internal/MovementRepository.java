package io.github.abreulucass.atlas.stock.internal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;

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

	private static final String COLUMNS = "id, warehouse_id, product_id, type, quantity, balance_after, note, created_by, created_at";

	private static final RowMapper<MovementView> VIEW = (rs, i) -> new MovementView(rs.getObject("id", UUID.class),
			rs.getObject("warehouse_id", UUID.class), rs.getObject("product_id", UUID.class),
			MovementType.valueOf(rs.getString("type")), rs.getBigDecimal("quantity"), rs.getBigDecimal("balance_after"),
			rs.getString("note"), rs.getObject("created_by", UUID.class),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());

	public Optional<MovementView> findByKey(UUID tenant, String key) {
		return jdbc.sql("select " + COLUMNS + " from stock_movements where tenant_id = :t and idempotency_key = :key")
			.param("t", tenant)
			.param("key", key)
			.query(VIEW)
			.optional();
	}

	public Optional<MovementView> findById(UUID tenant, UUID warehouseId, UUID id) {
		return jdbc
			.sql("select " + COLUMNS + " from stock_movements where tenant_id = :t and warehouse_id = :w and id = :id")
			.param("t", tenant)
			.param("w", warehouseId)
			.param("id", id)
			.query(VIEW)
			.optional();
	}

	public long count(UUID tenant, UUID warehouseId, @Nullable UUID productId, @Nullable MovementType type) {
		return jdbc.sql("select count(*) from stock_movements where " + FILTER)
			.param("t", tenant)
			.param("w", warehouseId)
			.param("p", productId, java.sql.Types.OTHER)
			.param("type", type == null ? null : type.name(), java.sql.Types.VARCHAR)
			.query(Long.class)
			.single();
	}

	/** Newest first, ties by id descending. */
	public List<MovementView> page(UUID tenant, UUID warehouseId, @Nullable UUID productId, @Nullable MovementType type,
			int size, long offset) {
		return jdbc.sql("select " + COLUMNS + " from stock_movements where " + FILTER
				+ " order by created_at desc, id desc limit :size offset :offset")
			.param("t", tenant)
			.param("w", warehouseId)
			.param("p", productId, java.sql.Types.OTHER)
			.param("type", type == null ? null : type.name(), java.sql.Types.VARCHAR)
			.param("size", size)
			.param("offset", offset)
			.query(VIEW)
			.list();
	}

	private static final String FILTER = "tenant_id = :t and warehouse_id = :w and (cast(:p as uuid) is null or product_id = :p)"
			+ " and (cast(:type as varchar) is null or type = :type)";

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
		// scale 3 like the stored column, so a replay answers the very same JSON as the original
		return new MovementView(id, warehouseId, productId, request.type(), request.delta().setScale(3), balanceAfter,
				request.note(), user, createdAt.toInstant());
	}

}
