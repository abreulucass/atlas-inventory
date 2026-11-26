package io.github.abreulucass.atlas.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.abreulucass.atlas.access.Organizations;
import io.github.abreulucass.atlas.access.Permissions;
import io.github.abreulucass.atlas.access.TenantContext;
import io.github.abreulucass.atlas.catalog.internal.CatalogIds;
import io.github.abreulucass.atlas.catalog.internal.Product;
import io.github.abreulucass.atlas.catalog.internal.ProductRepository;
import io.github.abreulucass.atlas.catalog.internal.Sku;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Products of the organization in the tenant context. Ids and bodies arrive as text and are parsed after the role and
 * organization checks, so a malformed one never jumps the precedence.
 */
@Service
public class Products {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final int MAX_NAME = 200;

	private final ProductRepository repository;

	private final Categories categories;

	private final Organizations organizations;

	private final Permissions permissions;

	Products(ProductRepository repository, Categories categories, Organizations organizations,
			Permissions permissions) {
		this.repository = repository;
		this.categories = categories;
		this.organizations = organizations;
		this.permissions = permissions;
	}

	@Transactional
	public UUID create(@Nullable String body) {
		requireWritable();
		JsonNode json = readBody(body);
		String sku = Sku.parse(text(json, "sku"));
		String name = name(text(json, "name"));
		Unit unit = Unit.parse(text(json, "unit"));
		UUID categoryId = categoryId(json);
		try {
			// shortcut: a category archived between the check and this insert still gets the product, harmless since archived categories keep their products; lock the category row if that changes
			return repository.saveAndFlush(new Product(sku, name, unit, categoryId)).getId();
		}
		catch (DataIntegrityViolationException e) {
			if (NestedExceptionUtils.getMostSpecificCause(e).getMessage().contains("products_tenant_sku_key")) {
				throw new DuplicateSkuException(sku);
			}
			throw e;
		}
	}

	@Transactional(readOnly = true)
	public ProductView find(String id) {
		TenantContext.require();
		return view(get(id));
	}

	/**
	 * Edits name and/or category with the version the client read. The version is compared first so a stale client
	 * never touches the row; the flush inside the method turns a concurrent winner into a {@code 409} before commit.
	 */
	@Transactional
	public ProductView update(String id, @Nullable String body) {
		requireWritable();
		Product product = get(id);
		JsonNode json = readBody(body);
		if (json.has("sku") || json.has("unit")) {
			throw new InvalidProductException("sku and unit cannot be changed");
		}
		JsonNode version = json.get("version");
		if (version == null || !version.isIntegralNumber() || !version.canConvertToLong()) {
			throw new InvalidProductException("version is required and must be an integer");
		}
		if (!json.has("name") && !json.has("categoryId")) {
			throw new InvalidProductException("Send name, categoryId or both");
		}
		String name = json.has("name") ? name(text(json, "name")) : null;
		UUID categoryId = json.has("categoryId") ? categoryId(json) : product.getCategoryId();
		if (product.isArchived()) {
			throw new ProductArchivedException(product.getId());
		}
		if (product.getVersion() != version.longValue()) {
			throw new StaleProductVersionException(product.getId());
		}
		if (name != null) {
			product.rename(name);
		}
		product.moveTo(categoryId);
		flush(product);
		return view(product);
	}

	@Transactional
	public void archive(String id) {
		Product product = writable(id);
		product.archive();
		flush(product);
	}

	@Transactional
	public void unarchive(String id) {
		Product product = writable(id);
		product.unarchive();
		flush(product);
	}

	private Product writable(String id) {
		requireWritable();
		return get(id);
	}

	/** Writes now, so a concurrent winner is answered as {@code 409} here and not at commit. */
	private void flush(Product product) {
		try {
			repository.flush();
		}
		catch (ObjectOptimisticLockingFailureException e) {
			throw new StaleProductVersionException(product.getId());
		}
	}

	/**
	 * A page of the catalog. Parameters arrive as text so that any out-of-range value answers {@code 400}; Spring's
	 * {@code Pageable} binding would clamp them instead.
	 */
	@Transactional(readOnly = true)
	public ProductPage list(@Nullable String page, @Nullable String size, @Nullable String q,
			@Nullable String categoryId, @Nullable String status) {
		TenantContext.require();
		int pageNumber = number(page, "page", 0, 0, Integer.MAX_VALUE);
		int pageSize = number(size, "size", 20, 1, 100);
		String filter = status == null ? "ACTIVE" : status;
		if (!List.of("ACTIVE", "ARCHIVED", "ALL").contains(filter)) {
			throw new InvalidListParameterException("status must be ACTIVE, ARCHIVED or ALL");
		}
		@Nullable UUID category = null;
		if (categoryId != null) {
			category = CatalogIds.parse(categoryId);
			if (category == null) {
				throw new InvalidListParameterException("categoryId must be a UUID");
			}
		}
		Specification<Product> where = matching(filter, category, q == null ? "" : q.strip());
		long totalItems;
		List<ProductView> items = List.of();
		if ((long) pageNumber * pageSize > Integer.MAX_VALUE) {
			// past any real catalog, and beyond the int offset of the query
			totalItems = repository.count(where);
		}
		else {
			var found = repository.findAll(where, PageRequest.of(pageNumber, pageSize));
			totalItems = found.getTotalElements();
			items = found.getContent().stream().map(Products::view).toList();
		}
		return new ProductPage(items, pageNumber, pageSize, totalItems, (int) ((totalItems + pageSize - 1) / pageSize));
	}

	private static int number(@Nullable String text, String name, int fallback, int min, int max) {
		if (text == null) {
			return fallback;
		}
		try {
			int value = Integer.parseInt(text);
			if (value >= min && value <= max) {
				return value;
			}
		}
		catch (NumberFormatException e) {
			// falls through to the invalid parameter below
		}
		throw new InvalidListParameterException(name + " must be an integer from " + min + " to " + max);
	}

	private static Specification<Product> matching(String status, @Nullable UUID categoryId, String q) {
		return (root, query, cb) -> {
			var filters = new ArrayList<jakarta.persistence.criteria.Predicate>();
			if (status.equals("ACTIVE")) {
				filters.add(cb.isNull(root.get("archivedAt")));
			}
			else if (status.equals("ARCHIVED")) {
				filters.add(cb.isNotNull(root.get("archivedAt")));
			}
			if (categoryId != null) {
				filters.add(cb.equal(root.get("categoryId"), categoryId));
			}
			if (!q.isEmpty()) {
				// shortcut: a leading-wildcard LIKE on the name cannot use a btree index, so each search scans the tenant's products; add a pg_trgm GIN index when a catalog passes ~100k rows
				filters.add(cb.or(
						cb.like(cb.lower(root.get("name")), "%" + escape(q.toLowerCase(Locale.ROOT)) + "%", '\\'),
						cb.like(root.get("sku"), escape(q.toUpperCase(Locale.ROOT)) + "%", '\\')));
			}
			query.orderBy(cb.asc(cb.lower(root.get("name"))), cb.asc(root.get("id")));
			return cb.and(filters.toArray(jakarta.persistence.criteria.Predicate[]::new));
		};
	}

	/** Makes {@code \}, {@code %} and {@code _} match themselves in a LIKE pattern. */
	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private void requireWritable() {
		permissions.requireManager();
		organizations.requireWritable(TenantContext.require());
	}

	private Product get(String id) {
		UUID parsed = CatalogIds.parse(id);
		return (parsed == null ? Optional.<Product>empty() : repository.findById(parsed))
			.orElseThrow(() -> new ProductNotFoundException(id));
	}

	/** The assignable category of the body, or null when the field is absent or null. */
	private @Nullable UUID categoryId(JsonNode json) {
		String id = text(json, "categoryId");
		if (id == null) {
			return null;
		}
		try {
			return categories.requireAssignable(id).getId();
		}
		catch (InvalidCategoryIdException e) {
			// a body id that is not a UUID answers like an unknown one (spec), not 400
			throw new CategoryNotFoundException(id);
		}
	}

	private static String name(@Nullable String raw) {
		String trimmed = raw == null ? "" : raw.strip();
		if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > MAX_NAME) {
			throw new InvalidNameException(MAX_NAME);
		}
		return trimmed;
	}

	private static JsonNode readBody(@Nullable String body) {
		try {
			JsonNode json = JSON.readTree(body == null ? "" : body);
			if (json.isObject()) {
				return json;
			}
		}
		catch (JacksonException e) {
			// falls through to the invalid request below
		}
		throw new InvalidProductException("Body must be a JSON object");
	}

	private static @Nullable String text(JsonNode json, String field) {
		JsonNode value = json.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		if (!value.isString()) {
			throw new InvalidProductException(field + " must be a string");
		}
		return value.asString();
	}

	private static ProductView view(Product p) {
		return new ProductView(p.getId(), p.getSku(), p.getName(), p.getUnit(), p.getUnit().fractional(),
				p.getCategoryId(), p.isArchived() ? Status.ARCHIVED : Status.ACTIVE, p.getVersion());
	}

}
