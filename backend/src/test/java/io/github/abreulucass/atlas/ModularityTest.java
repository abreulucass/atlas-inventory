package io.github.abreulucass.atlas;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

	@Test
	void modulesRespectTheirBoundaries() {
		ApplicationModules.of(AtlasApplication.class).verify();
	}

}
