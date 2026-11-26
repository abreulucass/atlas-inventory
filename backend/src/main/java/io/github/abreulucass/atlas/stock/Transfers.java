package io.github.abreulucass.atlas.stock;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.abreulucass.atlas.access.CurrentUser;
import io.github.abreulucass.atlas.access.Organizations;
import io.github.abreulucass.atlas.access.Permissions;
import io.github.abreulucass.atlas.access.TenantContext;
import io.github.abreulucass.atlas.catalog.ProductView;
import io.github.abreulucass.atlas.catalog.Products;
import io.github.abreulucass.atlas.company.Companies;
import io.github.abreulucass.atlas.company.CompanyView;
import io.github.abreulucass.atlas.stock.internal.BalanceRepository;
import io.github.abreulucass.atlas.stock.internal.BalanceRepository.Slot;
import io.github.abreulucass.atlas.stock.internal.IdempotencyKey;
import io.github.abreulucass.atlas.stock.internal.MovementRepository;
import io.github.abreulucass.atlas.stock.internal.StockIds;
import io.github.abreulucass.atlas.stock.internal.TransferRepository;
import io.github.abreulucass.atlas.stock.internal.TransferRequest;
import io.github.abreulucass.atlas.warehouse.WarehouseView;
import io.github.abreulucass.atlas.warehouse.Warehouses;
import org.jspecify.annotations.Nullable;

/**
 * Atomic transfers of one or more products between two warehouses of the organization in the tenant context. Key,
 * body and ids arrive as text and are parsed after the organization check, so a malformed one never jumps the
 * precedence. Everything runs in one transaction: a failure anywhere leaves no row behind.
 */
@Service
public class Transfers {

	/** Postgres orders uuids as unsigned bytes; {@code UUID.compareTo} is signed, so it would lock in another order. */
	private static final Comparator<UUID> DB_ORDER = (a, b) -> {
		int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
		return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
	};

	private final TransferRepository transfers;

	private final MovementRepository movements;

	private final BalanceRepository balances;

	private final Organizations organizations;

	private final Companies companies;

	private final Warehouses warehouses;

	private final Products products;

	private final Permissions permissions;

	Transfers(TransferRepository transfers, MovementRepository movements, BalanceRepository balances,
			Organizations organizations, Companies companies, Warehouses warehouses, Products products,
			Permissions permissions) {
		this.permissions = permissions;
		this.transfers = transfers;
		this.movements = movements;
		this.balances = balances;
		this.organizations = organizations;
		this.companies = companies;
		this.warehouses = warehouses;
		this.products = products;
	}

	@Transactional
	public TransferView record(@Nullable String key, @Nullable String body) {
		UUID tenant = TenantContext.require();
		organizations.requireWritable(tenant);
		String idempotencyKey = IdempotencyKey.parse(key);
		TransferRequest request = TransferRequest.parse(body);
		// Warehouses.find answers 404 for an unknown, foreign or (for a MEMBER) ungranted warehouse
		WarehouseView from = warehouses.find(id(request.fromWarehouseId(), "Warehouse"));
		WarehouseView to = warehouses.find(id(request.toWarehouseId(), "Warehouse"));

		// a replay answers before any state check, so a retry after an archive still gets its result
		movements.lockKey(tenant, idempotencyKey);
		var replay = transfers.findByKey(tenant, idempotencyKey);
		if (replay.isPresent()) {
			if (!sameRequest(replay.get(), from.id(), to.id(), request)) {
				throw new IdempotencyKeyReusedException();
			}
			return replay.get();
		}
		// the key namespace is shared with movements
		if (movements.findByKey(tenant, idempotencyKey).isPresent()) {
			throw new IdempotencyKeyReusedException();
		}

		List<UUID> productIds = new ArrayList<>();
		for (TransferRequest.Item item : request.items()) {
			productIds.add(id(item.productId(), "Product"));
		}
		Map<UUID, ProductView> found = new HashMap<>();
		products.findAll(productIds).forEach(p -> found.put(p.id(), p));
		if (!found.keySet().containsAll(productIds)) {
			throw new StockNotFoundException("Product not found");
		}
		for (int i = 0; i < productIds.size(); i++) {
			ProductView product = found.get(productIds.get(i));
			if (!product.fractional() && request.items().get(i).quantity().scale() > 0) {
				throw new InvalidMovementException("quantity must be a whole number for unit " + product.unit());
			}
		}
		// shortcut: an archive racing these reads lets one transfer land on a just-archived resource, harmless since archive only freezes new use; lock the rows if that changes
		CompanyView fromCompany = companies.find(from.companyId());
		CompanyView toCompany = companies.find(to.companyId());
		if (fromCompany.status() == io.github.abreulucass.atlas.company.Status.ARCHIVED
				|| toCompany.status() == io.github.abreulucass.atlas.company.Status.ARCHIVED) {
			throw new StockArchivedException("company");
		}
		if (from.status() == io.github.abreulucass.atlas.warehouse.Status.ARCHIVED
				|| to.status() == io.github.abreulucass.atlas.warehouse.Status.ARCHIVED) {
			throw new StockArchivedException("warehouse");
		}
		for (UUID product : productIds) {
			if (found.get(product).status() == io.github.abreulucass.atlas.catalog.Status.ARCHIVED) {
				throw new StockArchivedException("product");
			}
		}

		List<Slot> slots = new ArrayList<>();
		for (UUID product : productIds) {
			slots.add(new Slot(from.id(), product));
			slots.add(new Slot(to.id(), product));
		}
		balances.lockSlots(tenant, slots);
		Map<Slot, BigDecimal> locked = balances.lockForTransfer(tenant, from.id(), to.id(), productIds);
		for (int i = 0; i < productIds.size(); i++) {
			BigDecimal available = locked.getOrDefault(new Slot(from.id(), productIds.get(i)), BigDecimal.ZERO);
			if (available.compareTo(request.items().get(i).quantity()) < 0) {
				throw new InsufficientBalanceException(productIds.get(i));
			}
		}

		UUID user = CurrentUser.id();
		TransferView view = transfers.insert(tenant, from.companyId(), from.id(), to.companyId(), to.id(),
				request.note(), idempotencyKey, user, request.items(), productIds);
		// the same (warehouse, product) order as lockForTransfer, so new destination rows are created in one order too
		List<Integer> order = new ArrayList<>(java.util.stream.IntStream.range(0, productIds.size()).boxed().toList());
		order.sort(Comparator.comparing((Integer i) -> productIds.get(i), DB_ORDER));
		boolean fromFirst = DB_ORDER.compare(from.id(), to.id()) < 0;
		for (WarehouseView side : fromFirst ? List.of(from, to) : List.of(to, from)) {
			boolean out = side == from;
			for (int i : order) {
				ProductView product = found.get(productIds.get(i));
				BigDecimal delta = out ? request.items().get(i).quantity().negate() : request.items().get(i).quantity();
				BigDecimal after = balances
					.apply(tenant, side.companyId(), side.id(), product.id(), product.sku(), product.unit().name(), delta)
					.orElseThrow(() -> new InsufficientBalanceException(product.id()));
				movements.insertTransfer(tenant, side.companyId(), side.id(), product.id(),
						out ? MovementType.TRANSFER_OUT : MovementType.TRANSFER_IN, delta, after, request.note(),
						view.id(), user);
			}
		}
		return view;
	}

	/** 404 when malformed, unknown, or when the caller sees neither warehouse; archived resources stay readable. */
	@Transactional(readOnly = true)
	public TransferView find(String id) {
		UUID tenant = TenantContext.require();
		UUID transfer = StockIds.parse(id);
		var visibility = permissions.visibility();
		return (transfer == null ? java.util.Optional.<TransferRepository.Found>empty()
				: transfers.findById(tenant, transfer))
			.filter(f -> visibility.seesWarehouse(f.fromCompanyId(), f.view().fromWarehouseId())
					|| visibility.seesWarehouse(f.toCompanyId(), f.view().toWarehouseId()))
			.map(TransferRepository.Found::view)
			.orElseThrow(() -> new StockNotFoundException("Transfer not found: " + id));
	}

	/** Same warehouses, note and set of (product, numerically equal quantity), in any item order. */
	private static boolean sameRequest(TransferView stored, UUID from, UUID to, TransferRequest request) {
		if (!stored.fromWarehouseId().equals(from) || !stored.toWarehouseId().equals(to)
				|| !Objects.equals(stored.note(), request.note()) || stored.items().size() != request.items().size()) {
			return false;
		}
		Map<UUID, BigDecimal> quantities = new HashMap<>();
		stored.items().forEach(i -> quantities.put(i.productId(), i.quantity()));
		// items are unique by product, so equal size plus every request item found means equal sets
		return request.items().stream().allMatch(item -> {
			UUID product = StockIds.parse(item.productId());
			return product != null && quantities.containsKey(product)
					&& quantities.get(product).compareTo(item.quantity()) == 0;
		});
	}

	private static UUID id(String text, String what) {
		UUID id = StockIds.parse(text);
		if (id == null) {
			throw new StockNotFoundException(what + " not found: " + text);
		}
		return id;
	}

}
