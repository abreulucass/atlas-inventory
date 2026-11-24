package io.github.abreulucass.atlas.company;

public class DuplicateCnpjException extends RuntimeException {

	public DuplicateCnpjException(String cnpj) {
		super("CNPJ already in use in this organization: " + cnpj);
	}

}
