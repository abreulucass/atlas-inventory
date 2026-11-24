package io.github.abreulucass.atlas.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class TenantContextTest {

	@Test
	void isBoundInsideCallAs() throws Exception {
		UUID tenant = UUID.randomUUID();

		UUID seen = TenantContext.callAs(tenant, TenantContext::require);

		assertThat(seen).isEqualTo(tenant);
	}

	@Test
	void isEmptyOutsideAnyScope() throws Exception {
		TenantContext.callAs(UUID.randomUUID(), () -> null);

		assertThat(TenantContext.current()).isEmpty();
	}

	@Test
	void requireThrowsOutsideAnyScope() {
		assertThatThrownBy(TenantContext::require).isInstanceOf(MissingTenantContextException.class);
	}

	@Test
	void nestedCallAsRestoresOuterValue() throws Exception {
		UUID outer = UUID.randomUUID();
		UUID inner = UUID.randomUUID();

		TenantContext.callAs(outer, () -> {
			assertThat(TenantContext.callAs(inner, TenantContext::require)).isEqualTo(inner);
			assertThat(TenantContext.require()).isEqualTo(outer);
			return null;
		});
	}

	@Test
	void runAsBindsTheTenant() {
		UUID tenant = UUID.randomUUID();

		TenantContext.runAs(tenant, () -> assertThat(TenantContext.require()).isEqualTo(tenant));
	}

}
