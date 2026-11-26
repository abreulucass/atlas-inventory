package io.github.abreulucass.atlas.access.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/** Documentation only: the controller reads the raw body, so this type never runs. Send exactly one of companyId or warehouseId. */
@Schema(oneOf = { GrantBody.ToCompany.class, GrantBody.ToWarehouse.class })
record GrantBody(
		@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, description = "Grants access to a whole company") String companyId,
		@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, description = "Grants access to one warehouse") String warehouseId) {

	@Schema(name = "GrantToCompanyBody")
	record ToCompany(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Grants access to a whole company") String companyId) {
	}

	@Schema(name = "GrantToWarehouseBody")
	record ToWarehouse(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Grants access to one warehouse") String warehouseId) {
	}

}
