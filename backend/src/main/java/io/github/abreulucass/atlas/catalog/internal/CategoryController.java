package io.github.abreulucass.atlas.catalog.internal;

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

import io.github.abreulucass.atlas.catalog.CategoryView;
import io.github.abreulucass.atlas.catalog.Categories;

@RestController
@RequestMapping("/api/categories")
class CategoryController {

	private final Categories categories;

	CategoryController(Categories categories) {
		this.categories = categories;
	}

	record CreateCategory(String name) {
	}

	@GetMapping
	List<CategoryView> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
		return categories.list(includeArchived);
	}

	@GetMapping("/{id}")
	CategoryView get(@PathVariable String id) {
		return categories.find(id);
	}

	@PostMapping
	ResponseEntity<CategoryView> create(@RequestBody CreateCategory request) {
		UUID id = categories.create(request.name());
		return ResponseEntity.created(URI.create("/api/categories/" + id)).body(categories.find(id.toString()));
	}

	@PostMapping("/{id}/archive")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void archive(@PathVariable String id) {
		categories.archive(id);
	}

	@PostMapping("/{id}/unarchive")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void unarchive(@PathVariable String id) {
		categories.unarchive(id);
	}

}
