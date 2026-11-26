package io.github.abreulucass.atlas.stock;

import java.math.BigInteger;
import java.util.List;

public record BalancePage(List<BalanceView> items, BigInteger page, int size, long totalItems, int totalPages) {
}
