package io.github.abreulucass.atlas.stock;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.abreulucass.atlas.access.CurrentUser;
import io.github.abreulucass.atlas.access.Organizations;
import io.github.abreulucass.atlas.access.TenantContext;
import io.github.abreulucass.atlas.catalog.ProductView;
import io.github.abreulucass.atlas.catalog.Products;
import io.github.abreulucass.atlas.company.Companies;
import io.github.abreulucass.atlas.company.CompanyView;
import io.github.abreulucass.atlas.stock.internal.BalanceRepository;
import io.github.abreulucass.atlas.stock.internal.IdempotencyKey;
import io.github.abreulucass.atlas.stock.internal.MovementRepository;
import io.github.abreulucass.atlas.stock.internal.MovementRequest;
import io.github.abreulucass.atlas.stock.internal.StockIds;
import io.github.abreulucass.atlas.warehouse.WarehouseView;
import io.github.abreulucass.atlas.warehouse.Warehouses;

/**
 * Stock movements of the warehouses of the organization in the tenant context. Ids, key and body arrive as text and
 * are parsed after the organization check, so a malformed one never jumps the precedence.
 */
@Service
public class Movements {

	private final MovementRepository movements;

	private final BalanceRepository balances;

	private final Organizations organizations;

	private final Companies companies;

	private final Warehouses warehouses;

	private final Products products;

	Movements(MovementRepository movements, BalanceRepository balances, Organizations organizations,
			Companies companies, Warehouses warehouses, Products products) {
		this.movements = movements;
		this.balances = balances;
		this.organizations = organizations;
		this.companies = companies;
		this.warehouses = warehouses;
		this.products = products;
	}

	/** Visibility is enforced by {@code Companies.find} and {@code Warehouses.find}, which answer 404 when hidden. */
	@Transactional
	public MovementView record(String companyId, String warehouseId, @Nullable String key, @Nullable String body) {
		UUID tenant = TenantContext.require();
		organizations.requireWritable(tenant);
		UUID company = id(companyId, "Company");
		UUID warehouse = id(warehouseId, "Warehouse");
		CompanyView companyView = companies.find(company);
		WarehouseView warehouseView = warehouses.find(company, warehouse);
		String idempotencyKey = IdempotencyKey.parse(key);
		MovementRequest request = MovementRequest.parse(body);
		// a replay answers before any state check, so a retry after the product was archived still gets its result
		movements.lockKey(tenant, idempotencyKey);
		var replay = movements.findByKey(tenant, idempotencyKey);
		if (replay.isPresent()) {
			if (!sameRequest(replay.get(), warehouse, request)) {
				throw new IdempotencyKeyReusedException();
			}
			return replay.get();
		}
		ProductView product = products.find(request.productId());
		if (!product.fractional() && request.delta().scale() > 0) {
			throw new InvalidMovementException("quantity must be a whole number for unit " + product.unit());
		}
		// shortcut: an archive racing this read lets one movement land on a just-archived resource, harmless since archive only freezes new use; lock the rows if that changes
		if (companyView.status() == io.github.abreulucass.atlas.company.Status.ARCHIVED) {
			throw new StockArchivedException("company");
		}
		if (warehouseView.status() == io.github.abreulucass.atlas.warehouse.Status.ARCHIVED) {
			throw new StockArchivedException("warehouse");
		}
		if (product.status() == io.github.abreulucass.atlas.catalog.Status.ARCHIVED) {
			throw new StockArchivedException("product");
		}
		BigDecimal balanceAfter = balances
			.apply(tenant, company, warehouse, product.id(), product.sku(), product.unit().name(), request.delta())
			.orElseThrow(InsufficientBalanceException::new);
		return movements.insert(tenant, company, warehouse, request, product.id(), balanceAfter, idempotencyKey,
				CurrentUser.id());
	}

	/** Same warehouse, type, product, numerically equal quantity and note; an unparseable product id differs. */
	private static boolean sameRequest(MovementView stored, UUID warehouse, MovementRequest request) {
		return stored.warehouseId().equals(warehouse) && stored.type() == request.type()
				&& stored.productId().equals(StockIds.parse(request.productId()))
				&& stored.quantity().compareTo(request.delta()) == 0 && Objects.equals(stored.note(), request.note());
	}

	private static UUID id(String text, String what) {
		UUID id = StockIds.parse(text);
		if (id == null) {
			throw new StockNotFoundException(what + " not found: " + text);
		}
		return id;
	}

}
