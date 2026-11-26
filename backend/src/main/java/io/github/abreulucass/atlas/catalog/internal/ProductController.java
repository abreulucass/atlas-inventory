package io.github.abreulucass.atlas.catalog.internal;

import java.net.URI;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.abreulucass.atlas.catalog.ProductPage;
import io.github.abreulucass.atlas.catalog.ProductView;
import io.github.abreulucass.atlas.catalog.Products;

@RestController
@RequestMapping("/api/products")
class ProductController {

	private final Products products;

	ProductController(Products products) {
		this.products = products;
	}

	@GetMapping
	ProductPage list(@RequestParam(required = false) @Nullable String page,
			@RequestParam(required = false) @Nullable String size, @RequestParam(required = false) @Nullable String q,
			@RequestParam(required = false) @Nullable String categoryId,
			@RequestParam(required = false) @Nullable String status) {
		return products.list(page, size, q, categoryId, status);
	}

	@GetMapping("/{id}")
	ProductView get(@PathVariable String id) {
		return products.find(id);
	}

	@PatchMapping("/{id}")
	ProductView update(@PathVariable String id, @RequestBody(required = false) @Nullable String body) {
		return products.update(id, body);
	}

	@PostMapping("/{id}/archive")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void archive(@PathVariable String id) {
		products.archive(id);
	}

	@PostMapping("/{id}/unarchive")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void unarchive(@PathVariable String id) {
		products.unarchive(id);
	}

	@PostMapping
	ResponseEntity<ProductView> create(@RequestBody(required = false) @Nullable String body) {
		UUID id = products.create(body);
		return ResponseEntity.created(URI.create("/api/products/" + id)).body(products.find(id.toString()));
	}

}
