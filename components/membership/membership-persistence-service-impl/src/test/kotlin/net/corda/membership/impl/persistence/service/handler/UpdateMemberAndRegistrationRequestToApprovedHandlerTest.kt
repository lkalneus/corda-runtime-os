package net.corda.membership.impl.persistence.service.handler

import net.corda.crypto.cipher.suite.KeyEncodingService
import net.corda.data.CordaAvroDeserializer
import net.corda.data.CordaAvroSerializationFactory
import net.corda.data.CordaAvroSerializer
import net.corda.data.KeyValuePair
import net.corda.data.KeyValuePairList
import net.corda.data.identity.HoldingIdentity
import net.corda.data.membership.PersistentMemberInfo
import net.corda.data.membership.common.RegistrationStatus
import net.corda.data.membership.db.request.MembershipRequestContext
import net.corda.data.membership.db.request.command.UpdateMemberAndRegistrationRequestToApproved
import net.corda.db.connection.manager.DbConnectionManager
import net.corda.db.schema.CordaDb
import net.corda.libs.packaging.core.CpiIdentifier
import net.corda.libs.platform.PlatformInfoProvider
import net.corda.membership.datamodel.MemberInfoEntity
import net.corda.membership.datamodel.MemberInfoEntityPrimaryKey
import net.corda.membership.datamodel.RegistrationRequestEntity
import net.corda.membership.lib.MemberInfoExtension.Companion.MEMBER_STATUS_ACTIVE
import net.corda.membership.lib.MemberInfoExtension.Companion.MEMBER_STATUS_PENDING
import net.corda.membership.lib.MemberInfoExtension.Companion.STATUS
import net.corda.membership.lib.MemberInfoFactory
import net.corda.membership.lib.exceptions.MembershipPersistenceException
import net.corda.orm.JpaEntitiesRegistry
import net.corda.orm.JpaEntitiesSet
import net.corda.test.util.TestRandom
import net.corda.test.util.time.TestClock
import net.corda.v5.base.exceptions.CordaRuntimeException
import net.corda.virtualnode.VirtualNodeInfo
import net.corda.virtualnode.read.VirtualNodeInfoReadService
import net.corda.virtualnode.toCorda
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import javax.persistence.EntityManager
import javax.persistence.EntityManagerFactory
import javax.persistence.LockModeType

class UpdateMemberAndRegistrationRequestToApprovedHandlerTest {
    private val clock = TestClock(Instant.ofEpochMilli(0))
    private val jpaEntitiesSet = mock<JpaEntitiesSet>()
    private val jpaEntitiesRegistry = mock<JpaEntitiesRegistry> {
        on { get(CordaDb.Vault.persistenceUnitName) } doReturn jpaEntitiesSet
    }
    private val memberInfoFactory = mock<MemberInfoFactory>()
    private val keyValuePairListDeserializer = mock<CordaAvroDeserializer<KeyValuePairList>>()
    private val keyValuePairListSerializer = mock<CordaAvroSerializer<KeyValuePairList>> {
        on { serialize(any()) } doReturn byteArrayOf(0)
    }
    private val cordaAvroSerializationFactory = mock<CordaAvroSerializationFactory> {
        on {
            createAvroDeserializer(
                any(),
                eq(KeyValuePairList::class.java)
            )
        } doReturn keyValuePairListDeserializer
        on {
            createAvroSerializer<KeyValuePairList>(any())
        } doReturn keyValuePairListSerializer
    }
    private val vaultDmlConnectionId = UUID(0, 0)
    private val virtualNodeInfo = VirtualNodeInfo(
        vaultDmlConnectionId = vaultDmlConnectionId,
        cpiIdentifier = CpiIdentifier(
            "", "", TestRandom.secureHash()
        ),
        cryptoDmlConnectionId = UUID(0, 0),
        uniquenessDmlConnectionId = UUID(0, 0),
        holdingIdentity = HoldingIdentity("CN=Bob, O=Bob Corp, L=LDN, C=GB", "").toCorda(),
        timestamp = clock.instant(),
    )
    private val virtualNodeInfoReadService = mock<VirtualNodeInfoReadService> {
        on { getByHoldingIdentityShortHash(any()) } doReturn virtualNodeInfo
    }
    private val entityManager = mock<EntityManager> {
        on { transaction } doReturn mock()
    }
    private val factory = mock<EntityManagerFactory> {
        on { createEntityManager() } doReturn entityManager
    }
    private val dbConnectionManager = mock<DbConnectionManager> {
        on {
            createEntityManagerFactory(
                vaultDmlConnectionId,
                jpaEntitiesSet
            )
        } doReturn factory
    }
    private val requestEntity = mock<RegistrationRequestEntity> {
        on { status } doReturn RegistrationStatus.SENT_TO_MGM.name
    }
    private val keyEncodingService: KeyEncodingService = mock()
    private val platformInfoProvider: PlatformInfoProvider = mock()
    private val service = PersistenceHandlerServices(
        clock,
        dbConnectionManager,
        jpaEntitiesRegistry,
        memberInfoFactory,
        cordaAvroSerializationFactory,
        virtualNodeInfoReadService,
        keyEncodingService,
        platformInfoProvider,
        mock(),
    )
    private val handler = UpdateMemberAndRegistrationRequestToApprovedHandler(service)

    @Test
    fun `invoke throws exception if member can not be fund`() {
        val member = HoldingIdentity("CN=Member, O=Corp, L=LDN, C=GB", "group")
        whenever(
            entityManager.find(
                eq(MemberInfoEntity::class.java),
                eq(
                    MemberInfoEntityPrimaryKey(
                        groupId = member.groupId,
                        memberX500Name = member.x500Name,
                    )
                ),
                eq(LockModeType.PESSIMISTIC_WRITE),
            )
        ).doReturn(null)
        val context = MembershipRequestContext(
            clock.instant(),
            "id",
            HoldingIdentity(member.x500Name.toString(), "group"),
        )
        val request = UpdateMemberAndRegistrationRequestToApproved(
            HoldingIdentity(member.x500Name.toString(), "group"),
            "requestId",
        )

        assertThrows<MembershipPersistenceException> {
            handler.invoke(context, request)
        }
    }

    @Test
    fun `invoke throws exception if request can not be fund`() {
        val member = HoldingIdentity("CN=Member, O=Corp, L=LDN, C=GB", "group")
        val entity = mock<MemberInfoEntity>()
        val requestId = "requestId"
        whenever(
            entityManager.find(
                eq(MemberInfoEntity::class.java),
                eq(
                    MemberInfoEntityPrimaryKey(
                        groupId = member.groupId,
                        memberX500Name = member.x500Name,
                    )
                ),
                eq(LockModeType.PESSIMISTIC_WRITE)
            )
        ).doReturn(entity)
        whenever(
            entityManager.find(
                eq(RegistrationRequestEntity::class.java),
                eq(requestId),
                eq(LockModeType.PESSIMISTIC_WRITE)
            )
        ).doReturn(null)
        val context = MembershipRequestContext(
            clock.instant(),
            "id",
            HoldingIdentity(member.x500Name.toString(), "group"),
        )
        val request = UpdateMemberAndRegistrationRequestToApproved(
            HoldingIdentity(member.x500Name.toString(), "group"),
            requestId,
        )

        assertThrows<MembershipPersistenceException> {
            handler.invoke(context, request)
        }
    }

    @Test
    fun `invoke updates the member`() {
        val mgmContextBytes = byteArrayOf(1, 10)
        whenever(keyValuePairListDeserializer.deserialize(mgmContextBytes)).doReturn(
            KeyValuePairList(listOf(KeyValuePair(STATUS, MEMBER_STATUS_PENDING)))
        )
        val member = HoldingIdentity("CN=Member, O=Corp, L=LDN, C=GB", "group")
        val entity = mock<MemberInfoEntity> {
            on { mgmContext } doReturn mgmContextBytes
        }
        val requestId = "requestId"
        whenever(
            entityManager.find(
                eq(MemberInfoEntity::class.java),
                eq(
                    MemberInfoEntityPrimaryKey(
                        groupId = member.groupId,
                        memberX500Name = member.x500Name,
                    )
                ),
                eq(LockModeType.PESSIMISTIC_WRITE),
            )
        ).doReturn(entity)
        whenever(
            entityManager.find(
                eq(RegistrationRequestEntity::class.java),
                eq(requestId),
                eq(LockModeType.PESSIMISTIC_WRITE),
            )
        ).doReturn(requestEntity)
        val context = MembershipRequestContext(
            clock.instant(),
            "id",
            HoldingIdentity(member.x500Name.toString(), "group"),
        )
        val request = UpdateMemberAndRegistrationRequestToApproved(
            HoldingIdentity(member.x500Name.toString(), "group"),
            requestId,
        )

        clock.setTime(Instant.ofEpochMilli(500))
        handler.invoke(context, request)

        verify(entity).status = MEMBER_STATUS_ACTIVE
        verify(entity).modifiedTime = Instant.ofEpochMilli(500)
        verify(entity).mgmContext = byteArrayOf(0)
    }

    @Test
    fun `invoke will update the MGM context members`() {
        val mgmContextBytes = byteArrayOf(1, 10)
        whenever(keyValuePairListDeserializer.deserialize(mgmContextBytes)).doReturn(
            KeyValuePairList(
                listOf(
                    KeyValuePair(STATUS, MEMBER_STATUS_PENDING),
                    KeyValuePair("another-key", "value"),
                )
            )
        )
        val mgmContextCapture = argumentCaptor<KeyValuePairList>()
        whenever(keyValuePairListSerializer.serialize(mgmContextCapture.capture())).doReturn(byteArrayOf(0))
        val member = HoldingIdentity("CN=Member, O=Corp, L=LDN, C=GB", "group")
        val entity = mock<MemberInfoEntity> {
            on { mgmContext } doReturn mgmContextBytes
        }
        val requestId = "requestId"
        whenever(
            entityManager.find(
                eq(MemberInfoEntity::class.java),
                eq(
                    MemberInfoEntityPrimaryKey(
                        groupId = member.groupId,
                        memberX500Name = member.x500Name,
                    )
                ),
                eq(LockModeType.PESSIMISTIC_WRITE),
            )
        ).doReturn(entity)
        whenever(
            entityManager.find(
                eq(RegistrationRequestEntity::class.java),
                eq(requestId),
                eq(LockModeType.PESSIMISTIC_WRITE),
            )
        ).doReturn(requestEntity)
        val context = MembershipRequestContext(
            clock.instant(),
            "id",
            HoldingIdentity(member.x500Name.toString(), "group"),
        )
        val request = UpdateMemberAndRegistrationRequestToApproved(
            HoldingIdentity(member.x500Name.toString(), "group"),
            requestId,
        )

        clock.setTime(Instant.ofEpochMilli(500))
        handler.invoke(context, request)

        assertThat(mgmContextCapture.firstValue.items).containsExactly(
            KeyValuePair(STATUS, MEMBER_STATUS_ACTIVE),
            KeyValuePair("another-key", "value"),
        )
    }

    @Test
    fun `invoke will throw an exception if MGM context can not be serialize`() {
        whenever(keyValuePairListSerializer.serialize(any())).doReturn(null)
        val mgmContextBytes = byteArrayOf(1, 10)
        whenever(keyValuePairListDeserializer.deserialize(mgmContextBytes)).doReturn(
            KeyValuePairList(listOf(KeyValuePair(STATUS, MEMBER_STATUS_PENDING)))
        )
        val member = HoldingIdentity("CN=Member, O=Corp, L=LDN, C=GB", "group")
        val entity = mock<MemberInfoEntity> {
            on { mgmContext } doReturn mgmContextBytes
        }
        val requestId = "requestId"
        whenever(
            entityManager.find(
                eq(MemberInfoEntity::class.java),
                eq(
                    MemberInfoEntityPrimaryKey(
                        groupId = member.groupId,
                        memberX500Name = member.x500Name,
                    )
                ),
                eq(LockModeType.PESSIMISTIC_WRITE),
            )
        ).doReturn(entity)
        whenever(
            entityManager.find(
                eq(RegistrationRequestEntity::class.java),
                eq(requestId),
                eq(LockModeType.PESSIMISTIC_WRITE),
            )
        ).doReturn(requestEntity)
        val context = MembershipRequestContext(
            clock.instant(),
            "id",
            HoldingIdentity(member.x500Name.toString(), "group"),
        )
        val request = UpdateMemberAndRegistrationRequestToApproved(
            HoldingIdentity(member.x500Name.toString(), "group"),
            requestId,
        )

        clock.setTime(Instant.ofEpochMilli(500))
        assertThrows<CordaRuntimeException> {
            handler.invoke(context, request)
        }
    }

    @Test
    fun `invoke will throw an exception if mgm context can not be deserialize`() {
        val mgmContextBytes = byteArrayOf(1, 10)
        whenever(keyValuePairListDeserializer.deserialize(mgmContextBytes)).doReturn(null)
        val member = HoldingIdentity("CN=Member, O=Corp, L=LDN, C=GB", "group")
        val entity = mock<MemberInfoEntity> {
            on { mgmContext } doReturn mgmContextBytes
        }
        val requestId = "requestId"
        whenever(
            entityManager.find(
                eq(MemberInfoEntity::class.java),
                eq(
                    MemberInfoEntityPrimaryKey(
                        groupId = member.groupId,
                        memberX500Name = member.x500Name,
                    )
                ),
                eq(LockModeType.PESSIMISTIC_WRITE),
            )
        ).doReturn(entity)
        whenever(
            entityManager.find(
                eq(RegistrationRequestEntity::class.java),
                eq(requestId),
                eq(LockModeType.PESSIMISTIC_WRITE),
            )
        ).doReturn(requestEntity)
        val context = MembershipRequestContext(
            clock.instant(),
            "id",
            HoldingIdentity(member.x500Name.toString(), "group"),
        )
        val request = UpdateMemberAndRegistrationRequestToApproved(
            HoldingIdentity(member.x500Name.toString(), "group"),
            requestId,
        )

        clock.setTime(Instant.ofEpochMilli(500))
        assertThrows<MembershipPersistenceException> {
            handler.invoke(context, request)
        }
    }

    @Test
    fun `invoke updates the request`() {
        val mgmContextBytes = byteArrayOf(1, 10)
        whenever(keyValuePairListDeserializer.deserialize(mgmContextBytes)).doReturn(
            KeyValuePairList(listOf(KeyValuePair(STATUS, MEMBER_STATUS_PENDING)))
        )
        val member = HoldingIdentity("CN=Member, O=Corp, L=LDN, C=GB", "group")
        val entity = mock<MemberInfoEntity> {
            on { mgmContext } doReturn mgmContextBytes
        }
        val requestId = "requestId"
        whenever(
            entityManager.find(
                eq(MemberInfoEntity::class.java),
                eq(
                    MemberInfoEntityPrimaryKey(
                        groupId = member.groupId,
                        memberX500Name = member.x500Name,
                    )
                ),
                eq(LockModeType.PESSIMISTIC_WRITE),
            )
        ).doReturn(entity)
        whenever(
            entityManager.find(
                eq(RegistrationRequestEntity::class.java),
                eq(requestId),
                eq(LockModeType.PESSIMISTIC_WRITE),
            )
        ).doReturn(requestEntity)
        val context = MembershipRequestContext(
            clock.instant(),
            "id",
            HoldingIdentity(member.x500Name.toString(), "group"),
        )
        val request = UpdateMemberAndRegistrationRequestToApproved(
            HoldingIdentity(member.x500Name.toString(), "group"),
            requestId,
        )

        clock.setTime(Instant.ofEpochMilli(500))
        handler.invoke(context, request)

        verify(requestEntity).status = RegistrationStatus.APPROVED.name
        verify(requestEntity).lastModified = Instant.ofEpochMilli(500)
    }

    @Test
    fun `invoke will not update a declined request`() {
        whenever(requestEntity.status).doReturn(RegistrationStatus.DECLINED.name)
        val mgmContextBytes = byteArrayOf(1, 10)
        whenever(keyValuePairListDeserializer.deserialize(mgmContextBytes)).doReturn(
            KeyValuePairList(listOf(KeyValuePair(STATUS, MEMBER_STATUS_PENDING)))
        )
        val member = HoldingIdentity("CN=Member, O=Corp, L=LDN, C=GB", "group")
        val entity = mock<MemberInfoEntity> {
            on { mgmContext } doReturn mgmContextBytes
        }
        val requestId = "requestId"
        whenever(
            entityManager.find(
                eq(MemberInfoEntity::class.java),
                eq(
                    MemberInfoEntityPrimaryKey(
                        groupId = member.groupId,
                        memberX500Name = member.x500Name,
                    )
                ),
                eq(LockModeType.PESSIMISTIC_WRITE),
            )
        ).doReturn(entity)
        whenever(
            entityManager.find(
                eq(RegistrationRequestEntity::class.java),
                eq(requestId),
                eq(LockModeType.PESSIMISTIC_WRITE),
            )
        ).doReturn(requestEntity)
        val context = MembershipRequestContext(
            clock.instant(),
            "id",
            HoldingIdentity(member.x500Name.toString(), "group"),
        )
        val request = UpdateMemberAndRegistrationRequestToApproved(
            HoldingIdentity(member.x500Name.toString(), "group"),
            requestId,
        )

        assertThrows<MembershipPersistenceException> {
            handler.invoke(context, request)
        }
    }

    @Test
    fun `invoke returns the correct data`() {
        val member = HoldingIdentity("CN=Member, O=Corp, L=LDN, C=GB", "group")
        val entity = mock<MemberInfoEntity> {
            on { memberContext } doReturn byteArrayOf(1)
            on { mgmContext } doReturn byteArrayOf(2)
        }
        val requestId = "requestId"
        whenever(
            entityManager.find(
                eq(MemberInfoEntity::class.java),
                eq(
                    MemberInfoEntityPrimaryKey(
                        groupId = member.groupId,
                        memberX500Name = member.x500Name,
                    )
                ),
                eq(LockModeType.PESSIMISTIC_WRITE),
            )
        ).doReturn(entity)
        whenever(
            entityManager.find(
                eq(RegistrationRequestEntity::class.java),
                eq(requestId),
                eq(LockModeType.PESSIMISTIC_WRITE),
            )
        ).doReturn(requestEntity)
        val context = MembershipRequestContext(
            clock.instant(),
            "id",
            HoldingIdentity(member.x500Name.toString(), "group"),
        )
        val request = UpdateMemberAndRegistrationRequestToApproved(
            HoldingIdentity(member.x500Name.toString(), "group"),
            requestId,
        )
        val mgmContext = KeyValuePairList(
            listOf(
                KeyValuePair("one", "1")
            )
        )
        val memberContext = KeyValuePairList(
            listOf(
                KeyValuePair("two", "2")
            )
        )
        whenever(keyValuePairListDeserializer.deserialize(byteArrayOf(1))).thenReturn(memberContext)
        whenever(keyValuePairListDeserializer.deserialize(byteArrayOf(2))).thenReturn(mgmContext)

        val result = handler.invoke(context, request)

        assertThat(result.memberInfo).isEqualTo(
            PersistentMemberInfo(
                context.holdingIdentity,
                memberContext, mgmContext
            )
        )
    }
}
