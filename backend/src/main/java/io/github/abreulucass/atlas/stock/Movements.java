package io.github.abreulucass.atlas.stock;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
import io.github.abreulucass.atlas.stock.internal.PageParams;
import io.github.abreulucass.atlas.stock.internal.MovementRequest;
import io.github.abreulucass.atlas.stock.internal.StockIds;
import io.github.abreulucass.atlas.stock.internal.TransferRepository;
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

	private final TransferRepository transfers;

	private final Organizations organizations;

	private final Companies companies;

	private final Warehouses warehouses;

	private final Products products;

	Movements(MovementRepository movements, BalanceRepository balances, TransferRepository transfers,
			Organizations organizations,
			Companies companies, Warehouses warehouses, Products products) {
		this.movements = movements;
		this.balances = balances;
		this.transfers = transfers;
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
		// the key namespace is shared with transfers
		if (transfers.findByKey(tenant, idempotencyKey).isPresent()) {
			throw new IdempotencyKeyReusedException();
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

	@Transactional(readOnly = true)
	public MovementView find(String companyId, String warehouseId, String id) {
		UUID tenant = TenantContext.require();
		UUID warehouse = visibleWarehouse(companyId, warehouseId);
		UUID movement = StockIds.parse(id);
		return (movement == null ? Optional.<MovementView>empty() : movements.findById(tenant, warehouse, movement))
			.orElseThrow(() -> new StockNotFoundException("Movement not found: " + id));
	}

	/** Newest first; archived warehouses and companies stay readable. Parameters arrive as text, any bad one is 400. */
	@Transactional(readOnly = true)
	public MovementPage list(String companyId, String warehouseId, @Nullable String page, @Nullable String size,
			@Nullable String productId, @Nullable String type) {
		UUID tenant = TenantContext.require();
		UUID warehouse = visibleWarehouse(companyId, warehouseId);
		PageParams paging = PageParams.parse(page, size);
		UUID product = null;
		if (productId != null && (product = StockIds.parse(productId)) == null) {
			throw new InvalidMovementException("productId must be a UUID");
		}
		MovementType movementType = null;
		if (type != null) {
			try {
				movementType = MovementType.valueOf(type);
			}
			catch (IllegalArgumentException e) {
				throw new InvalidMovementException("type must be IN, OUT, ADJUSTMENT, TRANSFER_OUT or TRANSFER_IN");
			}
		}
		long totalItems = movements.count(tenant, warehouse, product, movementType);
		long offset = paging.offset();
		var items = offset < 0 ? List.<MovementView>of()
				: movements.page(tenant, warehouse, product, movementType, paging.size(), offset);
		return new MovementPage(items, paging.page(), paging.size(), totalItems, paging.totalPages(totalItems));
	}

	/** 404 for a malformed, unknown or invisible company or warehouse. */
	private UUID visibleWarehouse(String companyId, String warehouseId) {
		UUID company = id(companyId, "Company");
		UUID warehouse = id(warehouseId, "Warehouse");
		companies.find(company);
		warehouses.find(company, warehouse);
		return warehouse;
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
