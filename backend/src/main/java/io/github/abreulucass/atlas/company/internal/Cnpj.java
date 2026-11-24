package io.github.abreulucass.atlas.company.internal;

import java.util.regex.Pattern;

import io.github.abreulucass.atlas.company.InvalidCnpjException;

/** Numeric and alphanumeric CNPJ (IN RFB 2.229/2024): each character is worth its ASCII code minus 48. */
public final class Cnpj {

	private static final Pattern FORMAT = Pattern.compile("[0-9A-Z]{12}[0-9]{2}");

	private static final Pattern MASK = Pattern
		.compile("[0-9A-Za-z]{2}\\.[0-9A-Za-z]{3}\\.[0-9A-Za-z]{3}/[0-9A-Za-z]{4}-[0-9A-Za-z]{2}");

	private static final int[] FIRST_WEIGHTS = { 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2 };

	private static final int[] SECOND_WEIGHTS = { 6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2 };

	private Cnpj() {
	}

	/** Accepts the 14 characters bare or in the full mask; returns them without mask, in upper case. */
	public static String parse(String raw) {
		String cnpj = (MASK.matcher(raw).matches() ? raw.replaceAll("[./-]", "") : raw).toUpperCase();
		if (!FORMAT.matcher(cnpj).matches() || cnpj.chars().distinct().count() == 1
				|| checkDigit(cnpj, FIRST_WEIGHTS) != cnpj.charAt(12) - '0'
				|| checkDigit(cnpj, SECOND_WEIGHTS) != cnpj.charAt(13) - '0') {
			throw new InvalidCnpjException(raw);
		}
		return cnpj;
	}

	private static int checkDigit(String cnpj, int[] weights) {
		int sum = 0;
		for (int i = 0; i < weights.length; i++) {
			sum += (cnpj.charAt(i) - '0') * weights[i];
		}
		int remainder = sum % 11;
		return remainder < 2 ? 0 : 11 - remainder;
	}

}
