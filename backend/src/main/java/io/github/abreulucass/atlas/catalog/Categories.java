package io.github.abreulucass.atlas.catalog;

import java.util.List;
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
import io.github.abreulucass.atlas.catalog.internal.Category;
import io.github.abreulucass.atlas.catalog.internal.CategoryRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Flat categories of the organization in the tenant context. Ids arrive as text and are parsed after the checks. */
@Service
public class Categories {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final int MAX_NAME = 80;

	private final CategoryRepository repository;

	private final Organizations organizations;

	private final Permissions permissions;

	Categories(CategoryRepository repository, Organizations organizations, Permissions permissions) {
		this.repository = repository;
		this.organizations = organizations;
		this.permissions = permissions;
	}

	/** Parses the raw body after the role and organization checks, so a malformed one never jumps the precedence. */
	@Transactional
	public UUID createFromBody(@Nullable String body) {
		requireWritable();
		return create(name(body));
	}

	@Transactional
	public UUID create(String name) {
		requireWritable();
		String trimmed = name == null ? "" : name.strip();
		if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > MAX_NAME) {
			throw new InvalidNameException(MAX_NAME);
		}
		try {
			return repository.saveAndFlush(new Category(trimmed)).getId();
		}
		catch (DataIntegrityViolationException e) {
			if (NestedExceptionUtils.getMostSpecificCause(e).getMessage().contains("categories_tenant_name_key")) {
				throw new DuplicateCategoryNameException(trimmed);
			}
			throw e;
		}
	}

	private static @Nullable String name(@Nullable String body) {
		try {
			JsonNode json = JSON.readTree(body == null ? "" : body);
			if (json.isObject()) {
				JsonNode name = json.get("name");
				if (name != null && name.isString()) {
					return name.asString();
				}
			}
		}
		catch (JacksonException e) {
			// falls through to the invalid name below
		}
		throw new InvalidNameException(MAX_NAME);
	}

	@Transactional(readOnly = true)
	public List<CategoryView> list(boolean includeArchived) {
		TenantContext.require();
		return (includeArchived ? repository.findAllOrdered() : repository.findActiveOrdered()).stream()
			.map(Categories::view)
			.toList();
	}

	@Transactional(readOnly = true)
	public CategoryView find(String id) {
		TenantContext.require();
		return view(get(id));
	}

	@Transactional
	public void archive(String id) {
		writable(id).archive();
	}

	@Transactional
	public void unarchive(String id) {
		writable(id).unarchive();
	}

	/** For a body {@code categoryId}: {@code 400} when not a UUID, {@code 404} when unknown, {@code 409} when archived. */
	Category requireAssignable(String id) {
		UUID parsed = CatalogIds.parse(id);
		if (parsed == null) {
			throw new InvalidCategoryIdException();
		}
		Category category = repository.findById(parsed).orElseThrow(() -> new CategoryNotFoundException(id));
		if (category.isArchived()) {
			throw new CategoryArchivedException(parsed);
		}
		return category;
	}

	private Category writable(String id) {
		requireWritable();
		return get(id);
	}

	private void requireWritable() {
		permissions.requireManager();
		organizations.requireWritable(TenantContext.require());
	}

	private Category get(String id) {
		UUID parsed = CatalogIds.parse(id);
		return (parsed == null ? java.util.Optional.<Category>empty() : repository.findById(parsed))
			.orElseThrow(() -> new CategoryNotFoundException(id));
	}

	private static CategoryView view(Category c) {
		return new CategoryView(c.getId(), c.getName(), c.isArchived() ? Status.ARCHIVED : Status.ACTIVE);
	}

}
