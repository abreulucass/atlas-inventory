package io.github.abreulucass.atlas.stock;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Two companies, three warehouses and two stocked products for the transfer ITs, plus ledger-and-transfer snapshots. */
abstract class TransferITSupport extends StockITSupport {

	/** W1 and W2 belong to {@code companyA}, W3 to {@code companyB}; W1 holds 10 of p1 and 5 of p2. */
	record TScene(Session owner, UUID companyA, UUID w1, UUID w2, UUID companyB, UUID w3, UUID p1, UUID p2) {

		UUID tenant() {
			return owner.organizationId();
		}

		String movements(UUID company, UUID warehouse) {
			return "/api/companies/" + company + "/warehouses/" + warehouse + "/movements";
		}

	}

	/** Every persisted balance row, movement, transfer and transfer item of a tenant. */
	record Snapshot(Ledger ledger, List<Map<String, Object>> transfers, List<Map<String, Object>> items) {
	}

	TScene tscene() throws Exception {
		Session owner = register();
		UUID companyA = createCompany(owner);
		UUID companyB = createCompany(owner);
		TScene s = new TScene(owner, companyA, createWarehouse(owner, companyA), createWarehouse(owner, companyA),
				companyB, createWarehouse(owner, companyB), createProduct(owner, sku(), "UN"),
				createProduct(owner, sku(), "UN"));
		stock(s, s.w1(), s.p1(), 10);
		stock(s, s.w1(), s.p2(), 5);
		return s;
	}

	/** OWNER, ADMIN and a MEMBER whose grants show every warehouse of the scene, so both ends of any transfer. */
	List<Session> writers(TScene s) throws Exception {
		return List.of(s.owner(), joinAs(s.owner(), "ADMIN"), memberWith(s, "warehouseId", s.w1(), s.w2(), s.w3()));
	}

	static String sku() {
		return "P" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
	}

	UUID companyOf(TScene s, UUID warehouse) {
		return warehouse.equals(s.w3()) ? s.companyB() : s.companyA();
	}

	/** Puts {@code quantity} of the product in the warehouse through a plain IN movement. */
	void stock(TScene s, UUID warehouse, UUID product, Object quantity) throws Exception {
		record(s.owner(), s.movements(companyOf(s, warehouse), warehouse), move("IN", product, quantity, null));
	}

	/** A transfer body; {@code items} alternate product id and quantity literal. */
	static String transferBody(Object from, Object to, String note, Object... items) {
		StringBuilder list = new StringBuilder();
		for (int i = 0; i < items.length; i += 2) {
			list.append(i == 0 ? "" : ",")
				.append("{\"productId\": \"%s\", \"quantity\": %s}".formatted(items[i], items[i + 1]));
		}
		return "{\"fromWarehouseId\": \"%s\", \"toWarehouseId\": \"%s\", %s\"items\": [%s]}".formatted(from, to,
				note == null ? "" : "\"note\": \"" + note + "\", ", list);
	}

	ResultActions transfer(Session as, String key, String json) throws Exception {
		var request = post("/api/transfers").header("Authorization", as.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json);
		if (key != null) {
			request.header("Idempotency-Key", key);
		}
		return mvc.perform(request);
	}

	/** Sends with a fresh key and expects {@code 201}; returns the body. */
	String transferOk(Session as, String json) throws Exception {
		return transfer(as, UUID.randomUUID().toString(), json).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	static String key() {
		return UUID.randomUUID().toString();
	}

	Snapshot snapshot(UUID tenant) {
		SingleConnectionDataSource connection = new SingleConnectionDataSource(url, ownerUser, ownerPassword, true);
		try {
			JdbcTemplate owner = new JdbcTemplate(connection);
			owner.queryForObject("select set_config('app.tenant_id', ?, false)", String.class, tenant.toString());
			return new Snapshot(
					new Ledger(owner.queryForList("select * from stock_balances order by warehouse_id, product_id"),
							owner.queryForList("select * from stock_movements order by created_at, id")),
					owner.queryForList("select * from stock_transfers order by created_at, id"),
					owner.queryForList("select * from stock_transfer_items order by transfer_id, line"));
		}
		finally {
			connection.destroy();
		}
	}

	/** A MEMBER of the scene's organization holding the given grants ({@code companyId} or {@code warehouseId}). */
	Session memberWith(TScene s, String kind, UUID... targets) throws Exception {
		Session member = memberWithGrant(s.owner(), kind, targets[0]);
		for (int i = 1; i < targets.length; i++) {
			mvc.perform(post("/api/members/" + member.userId() + "/access").header("Authorization", s.owner().bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"%s\": \"%s\"}".formatted(kind, targets[i])))
				.andExpect(status().isCreated());
		}
		return member;
	}

}
