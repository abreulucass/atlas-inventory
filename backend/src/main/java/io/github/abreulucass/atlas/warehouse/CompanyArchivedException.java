package io.github.abreulucass.atlas.warehouse;

import java.util.UUID;

public class CompanyArchivedException extends RuntimeException {

	public CompanyArchivedException(UUID companyId) {
		super("Company is archived: " + companyId);
	}

}
