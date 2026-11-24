package io.github.abreulucass.atlas.access.internal;

import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.github.abreulucass.atlas.access.TenantContext;

import jakarta.persistence.EntityManagerFactory;

/**
 * Applies the tenant of the context to every transaction, local to it ({@code is_local = true}): the setting dies
 * with the transaction and never reaches the next user of the pooled connection (ADR-002, AD-001).
 */
class TenantTransactionManager extends JpaTransactionManager {

	TenantTransactionManager(EntityManagerFactory entityManagerFactory) {
		super(entityManagerFactory);
	}

	@Override
	protected void doBegin(Object transaction, TransactionDefinition definition) {
		super.doBegin(transaction, definition);
		TenantContext.current().ifPresent(tenant -> {
			EntityManagerHolder holder = (EntityManagerHolder) TransactionSynchronizationManager
				.getResource(obtainEntityManagerFactory());
			holder.getEntityManager()
				.createNativeQuery("select set_config('app.tenant_id', :tenant, true)")
				.setParameter("tenant", tenant.toString())
				.getSingleResult();
		});
	}

}
