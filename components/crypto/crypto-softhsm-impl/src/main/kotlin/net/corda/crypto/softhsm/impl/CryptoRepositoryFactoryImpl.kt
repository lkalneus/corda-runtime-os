package net.corda.crypto.softhsm.impl

import net.corda.crypto.core.CryptoTenants
import net.corda.crypto.core.ShortHash
import net.corda.crypto.softhsm.CryptoRepository
import net.corda.crypto.softhsm.CryptoRepositoryFactory
import net.corda.db.connection.manager.DbConnectionManager
import net.corda.db.core.DbPrivilege
import net.corda.db.schema.CordaDb
import net.corda.orm.JpaEntitiesRegistry
import net.corda.virtualnode.read.VirtualNodeInfoReadService
import javax.persistence.EntityManagerFactory


class CryptoRepositoryFactoryImpl
constructor(
    private val dbConnectionManager: DbConnectionManager,
    private val jpaEntitiesRegistry: JpaEntitiesRegistry,
    private val virtualNodeInfoReadService: VirtualNodeInfoReadService,
) : CryptoRepositoryFactory {
    override fun create(tenantId: String): CryptoRepository {
        val onCluster = CryptoTenants.isClusterTenant(tenantId)
        val entityManagerFactory = if (onCluster) {
            // tenantID is crypto, P2P or REST; let's obtain a connection to our cluster Crypto database 
            val baseEMF = dbConnectionManager.getOrCreateEntityManagerFactory(CordaDb.Crypto, DbPrivilege.DML)
            object : EntityManagerFactory by baseEMF {
                override fun close() {
                    // ignored; we should never close this since dbConnectionManager owns it
                    // TODO maybe move this logic to never close to DbConnectionManager
                }
            }
        } else {
            // tenantID is a virtual node; let's connect to one of the virtual node Crypto databases
            dbConnectionManager.createEntityManagerFactory(
                connectionId = virtualNodeInfoReadService.getByHoldingIdentityShortHash(
                    ShortHash.of(
                        tenantId
                    )
                )?.cryptoDmlConnectionId
                    ?: throw IllegalStateException(
                        "virtual node for $tenantId is not registered."
                    ),
                entitiesSet = jpaEntitiesRegistry.get(CordaDb.Crypto.persistenceUnitName)
                    ?: throw IllegalStateException(
                        "persistenceUnitName ${CordaDb.Crypto.persistenceUnitName} is not registered."
                    )
            )
        }

//        // somehow figure out which version we want, e.g.
//        when (entityManagerFactory.getSchemaVersion()) {
//            1 -> V1CryptoRepositoryImpl(entityManagerFactory)
//            else -> V2CryptoRepositoryImpl(entityManagerFactory)
//        }

        return V1CryptoRepositoryImpl(entityManagerFactory)
    }
}