package io.github.abreulucass.atlas.catalog.internal;

import java.net.URI;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.abreulucass.atlas.catalog.ProductView;
import io.github.abreulucass.atlas.catalog.Products;

@RestController
@RequestMapping("/api/products")
class ProductController {

	private final Products products;

	ProductController(Products products) {
		this.products = products;
	}

	@GetMapping("/{id}")
	ProductView get(@PathVariable String id) {
		return products.find(id);
	}

	@PostMapping
	ResponseEntity<ProductView> create(@RequestBody(required = false) @Nullable String body) {
		UUID id = products.create(body);
		return ResponseEntity.created(URI.create("/api/products/" + id)).body(products.find(id.toString()));
	}

}
