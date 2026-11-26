package io.github.abreulucass.atlas.stock.internal;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.abreulucass.atlas.stock.BalancePage;
import io.github.abreulucass.atlas.stock.Balances;

@RestController
class BalanceController {

	private final Balances balances;

	BalanceController(Balances balances) {
		this.balances = balances;
	}

	@GetMapping("/api/companies/{companyId}/warehouses/{warehouseId}/stock")
	BalancePage ofWarehouse(@PathVariable String companyId, @PathVariable String warehouseId,
			@RequestParam(required = false) @Nullable String page,
			@RequestParam(required = false) @Nullable String size) {
		return balances.ofWarehouse(companyId, warehouseId, page, size);
	}

}
