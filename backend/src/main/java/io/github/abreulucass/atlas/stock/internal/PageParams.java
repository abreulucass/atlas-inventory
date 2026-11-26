package io.github.abreulucass.atlas.stock.internal;

import java.math.BigInteger;

import org.jspecify.annotations.Nullable;

import io.github.abreulucass.atlas.stock.InvalidMovementException;

/** {@code page} and {@code size} as text so that any out-of-range value answers 400 instead of being clamped. */
public record PageParams(BigInteger page, int size) {

	/** Any non-negative integer page (a page past the end is just empty) and a size from 1 to 100, default 20. */
	public static PageParams parse(@Nullable String page, @Nullable String size) {
		BigInteger pageNumber = BigInteger.ZERO;
		if (page != null) {
			try {
				pageNumber = new BigInteger(page);
			}
			catch (NumberFormatException e) {
				throw new InvalidMovementException("page must be a non-negative integer");
			}
			if (pageNumber.signum() < 0) {
				throw new InvalidMovementException("page must be a non-negative integer");
			}
		}
		int pageSize = 20;
		if (size != null) {
			try {
				pageSize = Integer.parseInt(size);
			}
			catch (NumberFormatException e) {
				throw new InvalidMovementException("size must be an integer from 1 to 100");
			}
			if (pageSize < 1 || pageSize > 100) {
				throw new InvalidMovementException("size must be an integer from 1 to 100");
			}
		}
		return new PageParams(pageNumber, pageSize);
	}

	/** The row offset, or -1 when it is past any real table (and beyond a query offset). */
	public long offset() {
		BigInteger offset = page.multiply(BigInteger.valueOf(size));
		return offset.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0 ? -1 : offset.longValue();
	}

	public int totalPages(long totalItems) {
		return (int) ((totalItems + size - 1) / size);
	}

}
