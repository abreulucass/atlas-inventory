package io.github.abreulucass.atlas.stock.internal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.abreulucass.atlas.stock.TransferView;

@Repository
public class TransferRepository {

	private final JdbcClient jdbc;

	TransferRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Inserts the transfer and its items for {@code tenant} (from {@code TenantContext}); returns it as stored. */
	public TransferView insert(UUID tenant, UUID fromCompany, UUID from, UUID toCompany, UUID to, @Nullable String note,
			String key, UUID user, List<TransferRequest.Item> items, List<UUID> products) {
		UUID id = UUID.randomUUID();
		OffsetDateTime createdAt = jdbc.sql("""
				insert into stock_transfers (id, tenant_id, from_company_id, from_warehouse_id, to_company_id,
				                             to_warehouse_id, note, idempotency_key, created_by)
				values (:id, :t, :fc, :from, :tc, :to, :note, :key, :user)
				returning created_at""")
			.param("id", id)
			.param("t", tenant)
			.param("fc", fromCompany)
			.param("from", from)
			.param("tc", toCompany)
			.param("to", to)
			.param("note", note)
			.param("key", key)
			.param("user", user)
			.query(OffsetDateTime.class)
			.single();
		var views = new java.util.ArrayList<TransferView.Item>();
		for (int i = 0; i < items.size(); i++) {
			BigDecimal quantity = items.get(i).quantity();
			jdbc.sql("""
					insert into stock_transfer_items (tenant_id, transfer_id, line, product_id, quantity)
					values (:t, :id, :line, :p, :q)""")
				.param("t", tenant)
				.param("id", id)
				.param("line", i + 1)
				.param("p", products.get(i))
				.param("q", quantity)
				.update();
			// scale 3 like the stored column, so a replay answers the very same JSON as the original
			views.add(new TransferView.Item(products.get(i), quantity.setScale(3)));
		}
		return new TransferView(id, from, to, note, List.copyOf(views), user, createdAt.toInstant());
	}

	private record Header(UUID id, UUID from, UUID to, @Nullable String note, UUID createdBy, OffsetDateTime createdAt) {
	}

	/** A stored transfer with the companies of its two warehouses, for visibility checks. */
	public record Found(TransferView view, UUID fromCompanyId, UUID toCompanyId) {
	}

	public Optional<Found> findById(UUID tenant, UUID id) {
		return jdbc.sql("""
				select from_company_id, to_company_id from stock_transfers where tenant_id = :t and id = :id""")
			.param("t", tenant)
			.param("id", id)
			.query((rs, i) -> new UUID[] { rs.getObject("from_company_id", UUID.class),
					rs.getObject("to_company_id", UUID.class) })
			.optional()
			.flatMap(c -> load(tenant, Optional.of(header(tenant, id))).map(v -> new Found(v, c[0], c[1])));
	}

	private Header header(UUID tenant, UUID id) {
		return jdbc.sql("""
				select id, from_warehouse_id, to_warehouse_id, note, created_by, created_at from stock_transfers
				where tenant_id = :t and id = :id""")
			.param("t", tenant)
			.param("id", id)
			.query(HEADER)
			.single();
	}

	private static final org.springframework.jdbc.core.RowMapper<Header> HEADER = (rs, i) -> new Header(
			rs.getObject("id", UUID.class), rs.getObject("from_warehouse_id", UUID.class),
			rs.getObject("to_warehouse_id", UUID.class), rs.getString("note"), rs.getObject("created_by", UUID.class),
			rs.getObject("created_at", OffsetDateTime.class));

	/** The transfer recorded with this idempotency key in the tenant, if any. */
	public Optional<TransferView> findByKey(UUID tenant, String key) {
		return load(tenant, jdbc.sql("""
				select id, from_warehouse_id, to_warehouse_id, note, created_by, created_at from stock_transfers
				where tenant_id = :t and idempotency_key = :key""")
			.param("t", tenant)
			.param("key", key)
			.query(HEADER)
			.optional());
	}

	private Optional<TransferView> load(UUID tenant, Optional<Header> header) {
		return header.map(h -> new TransferView(h.id(), h.from(), h.to(), h.note(),
				jdbc.sql("select product_id, quantity from stock_transfer_items where tenant_id = :t and transfer_id = :id order by line")
					.param("t", tenant)
					.param("id", h.id())
					.query((rs, i) -> new TransferView.Item(rs.getObject("product_id", UUID.class),
							rs.getBigDecimal("quantity")))
					.list(),
				h.createdBy(), h.createdAt().toInstant()));
	}

}
