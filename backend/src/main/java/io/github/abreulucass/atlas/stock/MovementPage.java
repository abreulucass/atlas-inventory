package io.github.abreulucass.atlas.stock;

import java.math.BigInteger;
import java.util.List;

public record MovementPage(List<MovementView> items, BigInteger page, int size, long totalItems, int totalPages) {
}
