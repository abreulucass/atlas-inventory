package io.github.abreulucass.atlas.warehouse;

import java.util.List;
import java.util.UUID;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.abreulucass.atlas.access.Organizations;
import io.github.abreulucass.atlas.access.TenantContext;
import io.github.abreulucass.atlas.company.Companies;
import io.github.abreulucass.atlas.warehouse.internal.Warehouse;
import io.github.abreulucass.atlas.warehouse.internal.WarehouseCode;
import io.github.abreulucass.atlas.warehouse.internal.WarehouseRepository;

/** Warehouses of the companies of the organization in the tenant context. */
@Service
public class Warehouses {

	private final WarehouseRepository repository;

	private final Companies companies;

	private final Organizations organizations;

	Warehouses(WarehouseRepository repository, Companies companies, Organizations organizations) {
		this.repository = repository;
		this.companies = companies;
		this.organizations = organizations;
	}

	@Transactional
	public UUID create(UUID companyId, String name, String code) {
		UUID tenant = TenantContext.require();
		organizations.requireWritable(tenant);
		var company = companies.find(companyId);
		String trimmed = name == null ? "" : name.strip();
		if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 120) {
			throw new InvalidNameException();
		}
		String normalized = WarehouseCode.parse(code);
		// shortcut: a company archived between this check and the insert still gets the warehouse, harmless since archiving a company leaves its warehouses alone; lock the company row if that changes
		if (company.status() == io.github.abreulucass.atlas.company.Status.ARCHIVED) {
			throw new CompanyArchivedException(companyId);
		}
		try {
			return repository.saveAndFlush(new Warehouse(companyId, trimmed, normalized)).getId();
		}
		catch (DataIntegrityViolationException e) {
			String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
			if (message.contains("warehouses_tenant_company_name_key")) {
				throw new DuplicateWarehouseNameException(trimmed);
			}
			if (message.contains("warehouses_tenant_company_code_key")) {
				throw new DuplicateWarehouseCodeException(normalized);
			}
			throw e;
		}
	}

	@Transactional(readOnly = true)
	public List<WarehouseView> list(UUID companyId, boolean includeArchived) {
		companies.find(companyId);
		List<Warehouse> found = includeArchived ? repository.findByCompanyIdOrderByNameAsc(companyId)
				: repository.findByCompanyIdAndArchivedAtIsNullOrderByNameAsc(companyId);
		return found.stream().map(Warehouses::view).toList();
	}

	@Transactional(readOnly = true)
	public WarehouseView find(UUID companyId, UUID id) {
		companies.find(companyId);
		return view(repository.findByIdAndCompanyId(id, companyId).orElseThrow(() -> new WarehouseNotFoundException(id)));
	}

	private static WarehouseView view(Warehouse w) {
		return new WarehouseView(w.getId(), w.getCompanyId(), w.getName(), w.getCode(),
				w.isArchived() ? Status.ARCHIVED : Status.ACTIVE);
	}

	@Transactional
	public void archive(UUID companyId, UUID id) {
		writable(companyId, id).archive();
	}

	@Transactional
	public void unarchive(UUID companyId, UUID id) {
		writable(companyId, id).unarchive();
	}

	private Warehouse writable(UUID companyId, UUID id) {
		organizations.requireWritable(TenantContext.require());
		companies.find(companyId);
		return repository.findByIdAndCompanyId(id, companyId).orElseThrow(() -> new WarehouseNotFoundException(id));
	}

}
