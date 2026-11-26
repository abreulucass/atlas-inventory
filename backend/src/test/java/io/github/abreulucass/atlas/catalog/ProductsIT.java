package io.github.abreulucass.atlas.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import io.github.abreulucass.atlas.OwnerFixture;
import io.github.abreulucass.atlas.TestcontainersConfiguration;
import io.github.abreulucass.atlas.access.Organizations;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProductsIT {

	@Autowired
	Products products;

	@Autowired
	Organizations organizations;

	@Autowired
	JdbcTemplate app;

	@Value("${spring.datasource.url}")
	String url;

	@Value("${spring.flyway.user}")
	String ownerUser;

	@Value("${spring.flyway.password}")
	String ownerPassword;

	<T> T as(UUID tenant, Callable<T> work) throws Exception {
		return OwnerFixture.callAsOwner(app, tenant, work);
	}

	// RLS is forced on the owner too, so it seeds inside the tenant it writes to
	UUID seedProduct(UUID tenant, boolean archived) {
		UUID id = UUID.randomUUID();
		SingleConnectionDataSource connection = new SingleConnectionDataSource(url, ownerUser, ownerPassword, true);
		try {
			JdbcTemplate owner = new JdbcTemplate(connection);
			owner.queryForObject("select set_config('app.tenant_id', ?, false)", String.class, tenant.toString());
			owner.update("""
					insert into products (id, tenant_id, sku, name, unit, archived_at)
					values (?, ?, ?, 'P', 'UN', case when ? then now() end)""", id, tenant, "SKU-" + id, archived);
		}
		finally {
			connection.destroy();
		}
		return id;
	}

	UUID organization() {
		return organizations.create("Org", "org-" + UUID.randomUUID());
	}

	@Test
	void findAllReturnsOwnProductsArchivedIncluded() throws Exception {
		UUID org = organization();
		UUID active = seedProduct(org, false);
		UUID archived = seedProduct(org, true);
		seedProduct(org, false);

		List<ProductView> found = as(org, () -> products.findAll(Set.of(active, archived)));

		assertThat(found).extracting(ProductView::id).containsExactlyInAnyOrder(active, archived);
		assertThat(found).filteredOn(p -> p.id().equals(archived)).extracting(ProductView::status)
			.containsExactly(Status.ARCHIVED);
	}

	@Test
	void findAllSkipsUnknownIds() throws Exception {
		UUID org = organization();
		UUID own = seedProduct(org, false);

		List<ProductView> found = as(org, () -> products.findAll(List.of(own, UUID.randomUUID())));

		assertThat(found).extracting(ProductView::id).containsExactly(own);
	}

	@Test
	void findAllSkipsAnotherOrganizationsIds() throws Exception {
		UUID org = organization();
		UUID other = organization();
		UUID own = seedProduct(org, false);
		UUID foreign = seedProduct(other, false);

		List<ProductView> found = as(org, () -> products.findAll(List.of(own, foreign)));

		assertThat(found).extracting(ProductView::id).containsExactly(own);
	}

	@Test
	void findAllOfNothingIsEmpty() throws Exception {
		UUID org = organization();
		seedProduct(org, false);

		assertThat(as(org, () -> products.findAll(List.of()))).isEmpty();
	}

}
