package io.github.abreulucass.atlas.company;

import java.util.UUID;

/** Also thrown for a company of another organization: its existence is never revealed. */
public class CompanyNotFoundException extends RuntimeException {

	public CompanyNotFoundException(UUID id) {
		super("Company not found: " + id);
	}

}
