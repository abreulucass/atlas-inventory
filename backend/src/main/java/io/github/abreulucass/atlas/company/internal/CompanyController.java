package io.github.abreulucass.atlas.company.internal;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import io.github.abreulucass.atlas.access.Permissions;
import io.github.abreulucass.atlas.company.Companies;
import io.github.abreulucass.atlas.company.CompanyView;

@RestController
@RequestMapping("/api/companies")
class CompanyController {

	private final Companies companies;

	private final Permissions permissions;

	CompanyController(Companies companies, Permissions permissions) {
		this.companies = companies;
		this.permissions = permissions;
	}

	record CreateCompany(@NotBlank String name, String cnpj) {
	}

	@GetMapping
	List<CompanyView> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
		return companies.list(includeArchived);
	}

	@GetMapping("/{id}")
	CompanyView get(@PathVariable UUID id) {
		return companies.find(id);
	}

	@PostMapping
	ResponseEntity<CompanyView> create(@Valid @RequestBody CreateCompany request) {
		permissions.requireManager();
		UUID id = companies.create(request.name(), request.cnpj());
		return ResponseEntity.created(URI.create("/api/companies/" + id)).body(companies.find(id));
	}

	@PostMapping("/{id}/archive")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void archive(@PathVariable UUID id) {
		permissions.requireManager();
		companies.archive(id);
	}

	@PostMapping("/{id}/unarchive")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void unarchive(@PathVariable UUID id) {
		permissions.requireManager();
		companies.unarchive(id);
	}

}
