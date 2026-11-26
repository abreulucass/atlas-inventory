package io.github.abreulucass.atlas.stock;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.abreulucass.atlas.access.Permissions;
import io.github.abreulucass.atlas.access.TenantContext;
import io.github.abreulucass.atlas.catalog.ProductView;
import io.github.abreulucass.atlas.catalog.Products;
import io.github.abreulucass.atlas.company.Companies;
import io.github.abreulucass.atlas.stock.internal.BalanceRepository;
import io.github.abreulucass.atlas.stock.internal.BalanceRepository.Row;
import io.github.abreulucass.atlas.stock.internal.PageParams;
import io.github.abreulucass.atlas.stock.internal.StockIds;
import io.github.abreulucass.atlas.warehouse.Warehouses;

/** Balances of the warehouses visible to the caller in the tenant context. */
@Service
public class Balances {

	private final BalanceRepository balances;

	private final Companies companies;

	private final Warehouses warehouses;

	private final Products products;

	private final Permissions permissions;

	Balances(BalanceRepository balances, Companies companies, Warehouses warehouses, Products products,
			Permissions permissions) {
		this.permissions = permissions;
		this.balances = balances;
		this.companies = companies;
		this.warehouses = warehouses;
		this.products = products;
	}

	/** Every product that ever moved there (zero included) by SKU; archived warehouses and companies stay readable. */
	@Transactional(readOnly = true)
	public BalancePage ofWarehouse(String companyId, String warehouseId, @Nullable String page, @Nullable String size) {
		UUID tenant = TenantContext.require();
		UUID company = id(companyId, "Company");
		UUID warehouse = id(warehouseId, "Warehouse");
		companies.find(company);
		warehouses.find(company, warehouse);
		PageParams paging = PageParams.parse(page, size);
		long totalItems = balances.count(tenant, warehouse);
		long offset = paging.offset();
		List<Row> rows = offset < 0 ? List.of() : balances.page(tenant, warehouse, paging.size(), offset);
		Map<UUID, ProductView> names = products.findAll(rows.stream().map(Row::productId).toList())
			.stream()
			.collect(Collectors.toMap(ProductView::id, Function.identity()));
		List<BalanceView> items = rows.stream()
			.map(r -> new BalanceView(r.productId(), r.sku(), names.get(r.productId()).name(), r.unit(), r.quantity(),
					r.updatedAt()))
			.toList();
		return new BalancePage(items, paging.page(), paging.size(), totalItems, paging.totalPages(totalItems));
	}

	/** Over the warehouses the caller sees; {@code total} never counts the others. */
	@Transactional(readOnly = true)
	public ProductStock ofProduct(String productId) {
		UUID tenant = TenantContext.require();
		UUID product = products.find(productId).id();
		var visibility = permissions.visibility();
		// shortcut: one warehouse lookup per row, fine for a product in a few warehouses; batch by company if not
		var visible = balances.ofProduct(tenant, product)
			.stream()
			.filter(r -> visibility.seesWarehouse(r.companyId(), r.warehouseId()))
			.map(r -> Map.entry(warehouses.find(r.companyId(), r.warehouseId()).code(),
					new ProductStock.WarehouseStock(r.companyId(), r.warehouseId(), r.quantity())))
			.sorted(Comparator
				.comparing((Map.Entry<String, ProductStock.WarehouseStock> e) -> e.getValue().companyId().toString())
				.thenComparing(Map.Entry::getKey))
			.map(Map.Entry::getValue)
			.toList();
		return new ProductStock(product, visible.stream().map(ProductStock.WarehouseStock::quantity).reduce(BigDecimal.ZERO, BigDecimal::add),
				visible);
	}

	private static UUID id(String text, String what) {
		UUID id = StockIds.parse(text);
		if (id == null) {
			throw new StockNotFoundException(what + " not found: " + text);
		}
		return id;
	}

}
