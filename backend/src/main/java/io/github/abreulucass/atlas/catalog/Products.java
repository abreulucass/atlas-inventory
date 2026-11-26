package io.github.abreulucass.atlas.catalog;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
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
