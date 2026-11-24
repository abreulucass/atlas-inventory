package io.github.abreulucass.atlas.access.internal;

import java.util.UUID;

import org.hibernate.cfg.MultiTenancySettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.abreulucass.atlas.access.TenantContext;

import jakarta.persistence.EntityManagerFactory;

@Configuration(proxyBeanMethods = false)
class TenantPersistenceConfiguration {

	// no organization has this id: a session opened without context filters every tenant row out
	static final UUID NO_TENANT = new UUID(0, 0);

	@Bean
	PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
		return new TenantTransactionManager(entityManagerFactory);
	}

	@Bean
	HibernatePropertiesCustomizer tenantIdentifierResolver() {
		CurrentTenantIdentifierResolver<UUID> resolver = new CurrentTenantIdentifierResolver<>() {

			@Override
			public UUID resolveCurrentTenantIdentifier() {
				return TenantContext.current().orElse(NO_TENANT);
			}

			@Override
			public boolean validateExistingCurrentSessions() {
				return false;
			}

		};
		return properties -> properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
	}

}
