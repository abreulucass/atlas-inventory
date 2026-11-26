package io.github.abreulucass.atlas.company;

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
import io.github.abreulucass.atlas.access.Visibility;
import io.github.abreulucass.atlas.company.internal.Cnpj;
import io.github.abreulucass.atlas.company.internal.Company;
import io.github.abreulucass.atlas.company.internal.CompanyRepository;

/** Companies of the organization in the tenant context. */
@Service
public class Companies {

	private final CompanyRepository repository;

	private final Organizations organizations;

	private final Permissions permissions;

	Companies(CompanyRepository repository, Organizations organizations, Permissions permissions) {
		this.repository = repository;
		this.organizations = organizations;
		this.permissions = permissions;
	}

	@Transactional
	public UUID create(String name, @Nullable String cnpj) {
		UUID tenant = TenantContext.require();
		organizations.requireWritable(tenant);
		String trimmed = name == null ? "" : name.strip();
		if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 120) {
			throw new InvalidNameException();
		}
		String normalized = cnpj == null ? null : Cnpj.parse(cnpj);
		try {
			return repository.saveAndFlush(new Company(trimmed, normalized)).getId();
		}
		catch (DataIntegrityViolationException e) {
			String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
			if (message.contains("companies_tenant_name_key")) {
				throw new DuplicateCompanyNameException(trimmed);
			}
			if (message.contains("companies_tenant_cnpj_key")) {
				throw new DuplicateCnpjException(normalized);
			}
			throw e;
		}
	}

	@Transactional(readOnly = true)
	public List<CompanyView> list(boolean includeArchived) {
		TenantContext.require();
		Visibility visibility = permissions.visibility();
		List<Company> found = includeArchived ? repository.findAllByOrderByNameAsc()
				: repository.findByArchivedAtIsNullOrderByNameAsc();
		// shortcut: loads the whole organization and filters in Java, fine for tens of companies; move the filter to SQL with pagination when lists grow
		return found.stream().filter(c -> visibility.seesCompany(c.getId())).map(Companies::view).toList();
	}

	@Transactional(readOnly = true)
	public CompanyView find(UUID id) {
		TenantContext.require();
		// a company outside a MEMBER's grants answers like one that does not exist
		return view(repository.findById(id)
			.filter(c -> permissions.visibility().seesCompany(c.getId()))
			.orElseThrow(() -> new CompanyNotFoundException(id)));
	}

	private static CompanyView view(Company c) {
		return new CompanyView(c.getId(), c.getName(), c.getCnpj(), c.isArchived() ? Status.ARCHIVED : Status.ACTIVE);
	}

	@Transactional
	public void archive(UUID id) {
		writable(id).archive();
	}

	@Transactional
	public void unarchive(UUID id) {
		writable(id).unarchive();
	}

	private Company writable(UUID id) {
		organizations.requireWritable(TenantContext.require());
		// the tenant filter and RLS hide companies of other organizations
		return repository.findById(id).orElseThrow(() -> new CompanyNotFoundException(id));
	}

}
