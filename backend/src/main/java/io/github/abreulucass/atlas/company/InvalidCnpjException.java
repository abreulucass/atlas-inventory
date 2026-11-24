package io.github.abreulucass.atlas.company;

public class InvalidCnpjException extends RuntimeException {

	public InvalidCnpjException(String cnpj) {
		super("Invalid CNPJ: " + cnpj);
	}

}
