package net.corda.membership.impl.persistence.service.handler

import net.corda.data.crypto.wire.CryptoSignatureSpec
import net.corda.data.crypto.wire.CryptoSignatureWithKey
import net.corda.data.membership.db.request.MembershipRequestContext
import net.corda.data.membership.db.request.query.QueryMemberSignature
import net.corda.data.membership.db.response.query.MemberSignature
import net.corda.data.membership.db.response.query.MemberSignatureQueryResponse
import net.corda.membership.datamodel.MemberInfoEntityPrimaryKey
import net.corda.membership.datamodel.MemberSignatureEntity
import net.corda.membership.lib.exceptions.MembershipPersistenceException
import net.corda.virtualnode.toCorda
import java.nio.ByteBuffer

internal class QueryMemberSignatureHandler(
    persistenceHandlerServices: PersistenceHandlerServices
) : BasePersistenceHandler<QueryMemberSignature, MemberSignatureQueryResponse>(persistenceHandlerServices) {

    override fun invoke(
        context: MembershipRequestContext,
        request: QueryMemberSignature,
    ): MemberSignatureQueryResponse {
        return transaction(context.holdingIdentity.toCorda().shortHash) { em ->
            MemberSignatureQueryResponse(
                request.queryIdentities.mapNotNull { holdingIdentity ->
                    val signatureEntity = em.find(
                        MemberSignatureEntity::class.java,
                        MemberInfoEntityPrimaryKey(
                            holdingIdentity.groupId,
                            holdingIdentity.x500Name,
                            false
                        )
                    ) ?: throw MembershipPersistenceException("Could not find signature for $holdingIdentity")
                    val signatureSpec = if (signatureEntity.signatureSpec.isEmpty()) {
                        CryptoSignatureSpec("", null, null)
                    } else {
                        CryptoSignatureSpec(signatureEntity.signatureSpec, null, null)
                    }
                    val signature = CryptoSignatureWithKey(
                        ByteBuffer.wrap(signatureEntity.publicKey),
                        ByteBuffer.wrap(signatureEntity.content)
                    )
                    MemberSignature(
                        holdingIdentity,
                        signature,
                        signatureSpec
                    )
                }
            )
        }
    }
}
