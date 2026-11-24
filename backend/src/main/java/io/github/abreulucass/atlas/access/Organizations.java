package io.github.abreulucass.atlas.access;

import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.abreulucass.atlas.access.internal.Organization;
import io.github.abreulucass.atlas.access.internal.OrganizationRepository;

@Service
public class Organizations {

	private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

	private final OrganizationRepository repository;

	Organizations(OrganizationRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public UUID create(String name, String slug) {
		String trimmed = name == null ? "" : name.strip();
		if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 120) {
			throw new InvalidNameException();
		}
		if (slug == null || slug.length() < 3 || slug.length() > 63 || !SLUG.matcher(slug).matches()) {
			throw new InvalidSlugException(slug);
		}
		try {
			return repository.saveAndFlush(new Organization(trimmed, slug)).getId();
		}
		catch (DataIntegrityViolationException e) {
			if (NestedExceptionUtils.getMostSpecificCause(e).getMessage().contains("organizations_slug_key")) {
				throw new DuplicateSlugException(slug);
			}
			throw e;
		}
	}

	@Transactional(readOnly = true)
	public OrganizationView find(UUID id) {
		Organization organization = get(id);
		return new OrganizationView(organization.getId(), organization.getName(), organization.getSlug(),
				organization.isArchived() ? Status.ARCHIVED : Status.ACTIVE);
	}

	@Transactional
	public void archive(UUID id) {
		get(id).archive();
	}

	@Transactional
	public void unarchive(UUID id) {
		get(id).unarchive();
	}

	/** Call inside the transaction that writes: the share lock holds until it ends. */
	@Transactional
	public void requireWritable(UUID id) {
		if (repository.findForShare(id).orElseThrow(() -> new OrganizationNotFoundException(id)).isArchived()) {
			throw new OrganizationArchivedException(id);
		}
	}

	private Organization get(UUID id) {
		return repository.findById(id).orElseThrow(() -> new OrganizationNotFoundException(id));
	}

}
