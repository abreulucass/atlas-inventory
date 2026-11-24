package io.github.abreulucass.atlas.company;

public class DuplicateCompanyNameException extends RuntimeException {

	public DuplicateCompanyNameException(String name) {
		super("Company name already in use in this organization: " + name);
	}

}
