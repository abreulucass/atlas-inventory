package io.github.abreulucass.atlas.company;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.abreulucass.atlas.access.Organizations;
import io.github.abreulucass.atlas.access.TenantContext;
import io.github.abreulucass.atlas.company.internal.Cnpj;
import io.github.abreulucass.atlas.company.internal.Company;
import io.github.abreulucass.atlas.company.internal.CompanyRepository;

/** Companies of the organization in the tenant context. */
@Service
public class Companies {

	private final CompanyRepository repository;

	private final Organizations organizations;

	Companies(CompanyRepository repository, Organizations organizations) {
		this.repository = repository;
		this.organizations = organizations;
	}

	@Transactional
	public UUID create(String name, @Nullable String cnpj) {
		UUID tenant = TenantContext.require();
		String trimmed = name == null ? "" : name.strip();
		if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 120) {
			throw new InvalidNameException();
		}
		String normalized = cnpj == null ? null : Cnpj.parse(cnpj);
		organizations.requireWritable(tenant);
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
		List<Company> found = includeArchived ? repository.findAllByOrderByNameAsc()
				: repository.findByArchivedAtIsNullOrderByNameAsc();
		return found.stream()
			.map(c -> new CompanyView(c.getId(), c.getName(), c.getCnpj(),
					c.isArchived() ? Status.ARCHIVED : Status.ACTIVE))
			.toList();
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
