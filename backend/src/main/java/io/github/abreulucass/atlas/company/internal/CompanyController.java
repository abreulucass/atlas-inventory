package io.github.abreulucass.atlas.company.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.abreulucass.atlas.company.Companies;
import io.github.abreulucass.atlas.company.CompanyView;

@RestController
@RequestMapping("/api/companies")
class CompanyController {

	private final Companies companies;

	CompanyController(Companies companies) {
		this.companies = companies;
	}

	@GetMapping
	List<CompanyView> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
		return companies.list(includeArchived);
	}

	@GetMapping("/{id}")
	CompanyView get(@PathVariable UUID id) {
		return companies.find(id);
	}

}
