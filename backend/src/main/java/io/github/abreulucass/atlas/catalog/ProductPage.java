package io.github.abreulucass.atlas.catalog;

import java.util.List;

public record ProductPage(List<ProductView> items, java.math.BigInteger page, int size, long totalItems, int totalPages) {
}
