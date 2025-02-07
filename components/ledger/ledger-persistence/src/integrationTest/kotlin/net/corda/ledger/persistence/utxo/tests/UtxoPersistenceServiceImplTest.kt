package net.corda.ledger.persistence.utxo.tests

import net.corda.common.json.validation.JsonValidator
import net.corda.crypto.core.SecureHashImpl
import net.corda.db.persistence.testkit.components.VirtualNodeService
import net.corda.db.testkit.DbUtils
import net.corda.ledger.common.data.transaction.SignedTransactionContainer
import net.corda.ledger.common.data.transaction.TransactionMetadataInternal
import net.corda.ledger.common.data.transaction.TransactionStatus
import net.corda.ledger.common.data.transaction.TransactionStatus.UNVERIFIED
import net.corda.ledger.common.data.transaction.TransactionStatus.VERIFIED
import net.corda.ledger.common.data.transaction.factory.WireTransactionFactory
import net.corda.ledger.common.testkit.getPrivacySalt
import net.corda.ledger.common.testkit.getSignatureWithMetadataExample
import net.corda.ledger.common.testkit.transactionMetadataExample
import net.corda.ledger.persistence.consensual.tests.datamodel.field
import net.corda.ledger.persistence.utxo.CustomRepresentation
import net.corda.ledger.persistence.utxo.UtxoPersistenceService
import net.corda.ledger.persistence.utxo.UtxoRepository
import net.corda.ledger.persistence.utxo.UtxoTransactionReader
import net.corda.ledger.persistence.utxo.impl.UtxoPersistenceServiceImpl
import net.corda.ledger.persistence.utxo.tests.datamodel.UtxoEntityFactory
import net.corda.ledger.utxo.data.state.StateAndRefImpl
import net.corda.ledger.utxo.data.transaction.UtxoComponentGroup
import net.corda.ledger.utxo.data.transaction.UtxoOutputInfoComponent
import net.corda.orm.utils.transaction
import net.corda.persistence.common.getEntityManagerFactory
import net.corda.persistence.common.getSerializationService
import net.corda.sandboxgroupcontext.getSandboxSingletonService
import net.corda.test.util.time.AutoTickTestClock
import net.corda.testing.sandboxes.SandboxSetup
import net.corda.testing.sandboxes.fetchService
import net.corda.testing.sandboxes.lifecycle.EachTestLifecycle
import net.corda.v5.application.crypto.DigestService
import net.corda.v5.application.crypto.DigitalSignatureAndMetadata
import net.corda.v5.application.marshalling.JsonMarshallingService
import net.corda.v5.application.serialization.SerializationService
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.crypto.SecureHash
import net.corda.v5.ledger.common.Party
import net.corda.v5.ledger.common.transaction.CordaPackageSummary
import net.corda.v5.ledger.common.transaction.PrivacySalt
import net.corda.v5.ledger.utxo.Contract
import net.corda.v5.ledger.utxo.ContractState
import net.corda.v5.ledger.utxo.EncumbranceGroup
import net.corda.v5.ledger.utxo.StateAndRef
import net.corda.v5.ledger.utxo.StateRef
import net.corda.v5.ledger.utxo.TransactionState
import net.corda.v5.ledger.utxo.transaction.UtxoLedgerTransaction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import org.osgi.framework.BundleContext
import org.osgi.test.common.annotation.InjectBundleContext
import org.osgi.test.common.annotation.InjectService
import org.osgi.test.junit5.context.BundleContextExtension
import org.osgi.test.junit5.service.ServiceExtension
import java.math.BigDecimal
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.persistence.EntityManagerFactory

@ExtendWith(ServiceExtension::class, BundleContextExtension::class)
@TestInstance(PER_CLASS)
@Suppress("FunctionName")
class UtxoPersistenceServiceImplTest {
    @RegisterExtension
    private val lifecycle = EachTestLifecycle()

    private lateinit var persistenceService: UtxoPersistenceService
    private lateinit var jsonMarshallingService: JsonMarshallingService
    private lateinit var jsonValidator: JsonValidator
    private lateinit var wireTransactionFactory: WireTransactionFactory
    private lateinit var digestService: DigestService
    private lateinit var serializationService: SerializationService
    private lateinit var entityManagerFactory: EntityManagerFactory
    private lateinit var repository: UtxoRepository
    private val emConfig = DbUtils.getEntityManagerConfiguration("ledger_db_for_test")

    companion object {
        // Truncating to millis as on Windows builds the micros are lost after fetching the data from Postgres
        private const val TESTING_DATAMODEL_CPB = "/META-INF/testing-datamodel.cpb"
        private const val TIMEOUT_MILLIS = 10000L
        private val testClock = AutoTickTestClock(
            Instant.now().truncatedTo(ChronoUnit.MILLIS), Duration.ofSeconds(1)
        )
        private val seedSequence = AtomicInteger((0..Int.MAX_VALUE / 2).random())
        private val notaryX500Name = MemberX500Name.parse("O=ExampleNotaryService, L=London, C=GB")
        private val publicKeyExample: PublicKey = KeyPairGenerator.getInstance("RSA")
            .also {
                it.initialize(512)
            }.genKeyPair().public
        private val notaryExample = Party(notaryX500Name, publicKeyExample)
        private val transactionInputs = listOf(StateRef(SecureHashImpl("SHA-256", ByteArray(12)), 1))
        private val transactionOutputs = listOf(TestContractState1(), TestContractState2())
    }

    @BeforeAll
    fun setup(
        @InjectService(timeout = TIMEOUT_MILLIS)
        sandboxSetup: SandboxSetup,
        @InjectBundleContext
        bundleContext: BundleContext,
        @TempDir
        testDirectory: Path
    ) {
        sandboxSetup.configure(bundleContext, testDirectory)
        lifecycle.accept(sandboxSetup) { setup ->
            val virtualNode = setup.fetchService<VirtualNodeService>(TIMEOUT_MILLIS)
            val virtualNodeInfo = virtualNode.load(TESTING_DATAMODEL_CPB)
            val ctx = virtualNode.entitySandboxService.get(virtualNodeInfo.holdingIdentity)
            wireTransactionFactory = ctx.getSandboxSingletonService()
            jsonMarshallingService = ctx.getSandboxSingletonService()
            jsonValidator = ctx.getSandboxSingletonService()
            digestService = ctx.getSandboxSingletonService()
            serializationService = ctx.getSerializationService()
            entityManagerFactory = ctx.getEntityManagerFactory()
            repository = ctx.getSandboxSingletonService()
            persistenceService = UtxoPersistenceServiceImpl(
                entityManagerFactory,
                repository,
                serializationService,
                digestService,
                testClock
            )
        }
    }

    @Suppress("Unused")
    @AfterAll
    fun cleanup() {
        emConfig.close()
    }

    @Test
    fun `find signed transaction that matches input status`() {
        val entityFactory = UtxoEntityFactory(entityManagerFactory)
        val transaction = persistTransactionViaEntity(entityFactory)

        val dbSignedTransaction = persistenceService.findTransaction(transaction.id.toString(), UNVERIFIED)

        assertThat(dbSignedTransaction).isEqualTo(transaction)
    }

    @Test
    fun `find signed transaction with different status returns null`() {
        val entityFactory = UtxoEntityFactory(entityManagerFactory)
        val transaction = persistTransactionViaEntity(entityFactory)

        val dbSignedTransaction = persistenceService.findTransaction(transaction.id.toString(), VERIFIED)

        assertThat(dbSignedTransaction).isNull()
    }

    @Test
    fun `find unconsumed visible transaction states`() {
        Assumptions.assumeFalse(DbUtils.isInMemory, "Skipping this test when run against in-memory DB.")
        val createdTs = testClock.instant()
        val entityFactory = UtxoEntityFactory(entityManagerFactory)
        val transaction1 = createSignedTransaction(createdTs)
        val transaction2 = createSignedTransaction(createdTs)
        entityManagerFactory.transaction { em ->

            em.createNativeQuery("DELETE FROM {h-schema}utxo_visible_transaction_state").executeUpdate()

            createTransactionEntity(entityFactory, transaction1, status = VERIFIED).also { em.persist(it) }
            createTransactionEntity(entityFactory, transaction2, status = VERIFIED).also { em.persist(it) }

            repository.persistTransactionVisibleStates(
                em,
                transaction1.id.toString(),
                UtxoComponentGroup.OUTPUTS.ordinal,
                1,
                false,
                CustomRepresentation("{}"),
                createdTs
            )

            repository.persistTransactionVisibleStates(
                em,
                transaction2.id.toString(),
                UtxoComponentGroup.OUTPUTS.ordinal,
                0,
                false,
                CustomRepresentation("{}"),
                createdTs
            )

            repository.persistTransactionVisibleStates(
                em,
                transaction2.id.toString(),
                UtxoComponentGroup.OUTPUTS.ordinal,
                1,
                true,
                CustomRepresentation("{}"),
                createdTs
            )
        }

        val stateClass = TestContractState2::class.java
        val unconsumedStates = persistenceService.findUnconsumedVisibleStatesByType(stateClass)
        assertThat(unconsumedStates).isNotNull
        assertThat(unconsumedStates.size).isEqualTo(1)
        val transactionOutput = unconsumedStates.first()
        assertThat(transactionOutput.transactionId).isEqualTo(transaction1.id.toString())
        assertThat(transactionOutput.leafIndex).isEqualTo(1)
        assertThat(transactionOutput.info).isEqualTo(transaction1.wireTransaction.componentGroupLists[UtxoComponentGroup.OUTPUTS_INFO.ordinal][1])
        assertThat(transactionOutput.data).isEqualTo(transaction1.wireTransaction.componentGroupLists[UtxoComponentGroup.OUTPUTS.ordinal][1])
    }

    @Test
    fun `resolve staterefs`() {
        val entityFactory = UtxoEntityFactory(entityManagerFactory)
        val transactions = listOf(
            persistTransactionViaEntity(entityFactory, VERIFIED),
            persistTransactionViaEntity(entityFactory, VERIFIED)
        )

        val stateRefs = listOf(
            StateRef(transactions[0].id, 0),
            StateRef(transactions[1].id, 1),
        )
        val stateAndRefs = persistenceService.resolveStateRefs(stateRefs)
        assertThat(stateAndRefs).isNotNull
        assertThat(stateAndRefs.size).isEqualTo(2)

        for (i in 0..1) {
            val transactionOutput = stateAndRefs[i]

            assertThat(transactionOutput.transactionId).isEqualTo(transactions[i].id.toString())
            assertThat(transactionOutput.leafIndex).isEqualTo(i)
            assertThat(transactionOutput.info).isEqualTo(transactions[i].wireTransaction.componentGroupLists[UtxoComponentGroup.OUTPUTS_INFO.ordinal][i])
            assertThat(transactionOutput.data).isEqualTo(transactions[i].wireTransaction.componentGroupLists[UtxoComponentGroup.OUTPUTS.ordinal][i])
        }
    }

    @Test
    fun `update transaction status`() {
        Assumptions.assumeFalse(DbUtils.isInMemory, "Skipping this test when run against in-memory DB.")
        var floorDateTime = nextTime()

        val entityFactory = UtxoEntityFactory(entityManagerFactory)
        val transaction = persistTransactionViaEntity(entityFactory)

        assertTransactionStatus(transaction.id.toString(), UNVERIFIED, entityFactory, floorDateTime)

        floorDateTime = nextTime()

        persistenceService.updateStatus(transaction.id.toString(), VERIFIED)

        assertTransactionStatus(transaction.id.toString(), VERIFIED, entityFactory, floorDateTime)

    }

    @Test
    fun `update transaction status does not affect other transactions`() {
        Assumptions.assumeFalse(DbUtils.isInMemory, "Skipping this test when run against in-memory DB.")
        var floorDateTime = nextTime()

        val entityFactory = UtxoEntityFactory(entityManagerFactory)
        val transaction1 = persistTransactionViaEntity(entityFactory)
        val transaction2 = persistTransactionViaEntity(entityFactory)

        assertTransactionStatus(transaction1.id.toString(), UNVERIFIED, entityFactory, floorDateTime)
        assertTransactionStatus(transaction2.id.toString(), UNVERIFIED, entityFactory, floorDateTime)

        floorDateTime = nextTime()

        persistenceService.updateStatus(transaction1.id.toString(), VERIFIED)

        assertTransactionStatus(transaction1.id.toString(), VERIFIED, entityFactory, floorDateTime)
        assertTransactionStatus(transaction2.id.toString(), UNVERIFIED, entityFactory, floorDateTime)
    }

    @Test
    fun `persist signed transaction`() {
        Assumptions.assumeFalse(DbUtils.isInMemory, "Skipping this test when run against in-memory DB.")
        val account = "Account"
        val transactionStatus = VERIFIED
        val signedTransaction = createSignedTransaction(Instant.now())
        val visibleStatesIndexes = listOf(0)

        // Persist transaction
        val transactionReader = TestUtxoTransactionReader(
            signedTransaction,
            account,
            transactionStatus,
            visibleStatesIndexes
        )
        persistenceService.persistTransaction(transactionReader)

        val entityFactory = UtxoEntityFactory(entityManagerFactory)

        // Verify persisted data
        entityManagerFactory.transaction { em ->
            val dbTransaction = em.find(entityFactory.utxoTransaction, signedTransaction.id.toString())

            assertThat(dbTransaction).isNotNull
            val txPrivacySalt = dbTransaction.field<ByteArray>("privacySalt")
            val txAccountId = dbTransaction.field<String>("accountId")
            val txCreatedTs = dbTransaction.field<Instant?>("created")

            assertThat(txPrivacySalt).isEqualTo(signedTransaction.wireTransaction.privacySalt.bytes)
            assertThat(txAccountId).isEqualTo(account)
            assertThat(txCreatedTs).isNotNull

            val componentGroupLists = signedTransaction.wireTransaction.componentGroupLists
            val txComponents = dbTransaction.field<Collection<Any>?>("components")
            assertThat(txComponents).isNotNull
                .hasSameSizeAs(componentGroupLists.flatten().filter { it.isNotEmpty() })
            txComponents!!
                .sortedWith(compareBy<Any> { it.field<Int>("groupIndex") }.thenBy { it.field<Int>("leafIndex") })
                .groupBy { it.field<Int>("groupIndex") }.values
                .zip(componentGroupLists)
                .forEachIndexed { groupIndex, (dbComponentGroup, componentGroup) ->
                    assertThat(dbComponentGroup).hasSameSizeAs(componentGroup)
                    dbComponentGroup.zip(componentGroup)
                        .forEachIndexed { leafIndex, (dbComponent, component) ->
                            assertThat(dbComponent.field<Int>("groupIndex")).isEqualTo(groupIndex)
                            assertThat(dbComponent.field<Int>("leafIndex")).isEqualTo(leafIndex)
                            assertThat(dbComponent.field<ByteArray>("data")).isEqualTo(component)
                            assertThat(dbComponent.field<String>("hash")).isEqualTo(
                                digest("SHA-256", component).toString()
                            )
                            assertThat(dbComponent.field<Instant>("created")).isEqualTo(txCreatedTs)
                        }
                }

            val dbTransactionSources = em.createNamedQuery(
                "UtxoTransactionSourceEntity.findByTransactionId",
                entityFactory.utxoTransactionSource
            )
                .setParameter("transactionId", signedTransaction.id.toString())
                .resultList
            assertThat(dbTransactionSources).isNotNull
                .hasSameSizeAs(transactionInputs)
            dbTransactionSources
                .sortedWith(compareBy<Any> { it.field<Int>("groupIndex") }.thenBy { it.field<Int>("leafIndex") })
                .zip(transactionInputs)
                .forEachIndexed { leafIndex, (dbInput, transactionInput) ->
                    assertThat(dbInput.field<Int>("groupIndex")).isEqualTo(UtxoComponentGroup.INPUTS.ordinal)
                    assertThat(dbInput.field<Int>("leafIndex")).isEqualTo(leafIndex)
                    assertThat(dbInput.field<String>("refTransactionId")).isEqualTo(transactionInput.transactionId.toString())
                    assertThat(dbInput.field<Int>("refLeafIndex")).isEqualTo(transactionInput.index)
                    assertThat(dbInput.field<Boolean>("isRefInput")).isEqualTo(false)
                }

            val dbTransactionOutputs = em.createNamedQuery(
                "UtxoTransactionOutputEntity.findByTransactionId",
                entityFactory.utxoTransactionOutput
            )
                .setParameter("transactionId", signedTransaction.id.toString())
                .resultList
            assertThat(dbTransactionOutputs).isNotNull
                .hasSameSizeAs(componentGroupLists.get(UtxoComponentGroup.OUTPUTS.ordinal))
            dbTransactionOutputs
                .sortedWith(compareBy<Any> { it.field<Int>("groupIndex") }.thenBy { it.field<Int>("leafIndex") })
                .zip(transactionOutputs)
                .forEachIndexed { leafIndex, (dbInput, transactionOutput) ->
                    assertThat(dbInput.field<Int>("groupIndex")).isEqualTo(UtxoComponentGroup.OUTPUTS.ordinal)
                    assertThat(dbInput.field<Int>("leafIndex")).isEqualTo(leafIndex)
                    assertThat(dbInput.field<String>("type")).isEqualTo(transactionOutput::class.java.canonicalName)
                    assertThat(dbInput.field<String>("tokenType")).isNull()
                    assertThat(dbInput.field<String>("tokenIssuerHash")).isNull()
                    assertThat(dbInput.field<String>("tokenNotaryX500Name")).isNull()
                    assertThat(dbInput.field<String>("tokenSymbol")).isNull()
                    assertThat(dbInput.field<String>("tokenTag")).isNull()
                    assertThat(dbInput.field<String>("tokenOwnerHash")).isNull()
                    assertThat(dbInput.field<BigDecimal>("tokenAmount")).isNull()
                }

            val dbRelevancyData = em.createNamedQuery(
                "UtxoVisibleTransactionStateEntity.findByTransactionId",
                entityFactory.utxoVisibleTransactionState
            )
                .setParameter("transactionId", signedTransaction.id.toString())
                .resultList
            assertThat(dbRelevancyData).isNotNull
                .hasSameSizeAs(visibleStatesIndexes)
            dbRelevancyData
                .sortedWith(compareBy<Any> { it.field<Int>("groupIndex") }.thenBy { it.field<Int>("leafIndex") })
                .zip(visibleStatesIndexes)
                .forEach { (dbRelevancy, visibleStateIndex) ->
                    assertThat(dbRelevancy.field<Int>("groupIndex")).isEqualTo(UtxoComponentGroup.OUTPUTS.ordinal)
                    assertThat(dbRelevancy.field<Int>("leafIndex")).isEqualTo(visibleStateIndex)
                    assertThat(dbRelevancy.field<String>("customRepresentation")).isEqualTo("{\"temp\": \"value\"}")
                    assertThat(dbRelevancy.field<Instant>("consumed")).isNull()
                }

            val signatures = signedTransaction.signatures
            val txSignatures = dbTransaction.field<Collection<Any>?>("signatures")
            assertThat(txSignatures)
                .isNotNull
                .hasSameSizeAs(signatures)
            txSignatures!!
                .sortedBy { it.field<Int>("index") }
                .zip(signatures)
                .forEachIndexed { index, (dbSignature, signature) ->
                    assertThat(dbSignature.field<Int>("index")).isEqualTo(index)
                    assertThat(dbSignature.field<ByteArray>("signature")).isEqualTo(
                        serializationService.serialize(
                            signature
                        ).bytes
                    )
                    assertThat(dbSignature.field<String>("publicKeyHash")).isEqualTo(
                        digest("SHA-256", signature.by.encoded).toString()
                    )
                    assertThat(dbSignature.field<Instant>("created")).isEqualTo(txCreatedTs)
                }

            val txStatuses = dbTransaction.field<Collection<Any>?>("statuses")
            assertThat(txStatuses)
                .isNotNull
                .hasSize(1)
            val dbStatus = txStatuses!!.first()
            assertThat(dbStatus.field<String>("status")).isEqualTo(transactionStatus.value)
            assertThat(dbStatus.field<Instant>("updated")).isEqualTo(txCreatedTs)
        }
    }

    private fun persistTransactionViaEntity(
        entityFactory: UtxoEntityFactory,
        status: TransactionStatus = UNVERIFIED
    ): SignedTransactionContainer {
        val signedTransaction = createSignedTransaction()
        entityManagerFactory.transaction { em ->
            em.persist(createTransactionEntity(entityFactory, signedTransaction, status = status))
        }
        return signedTransaction
    }

    private fun createTransactionEntity(
        entityFactory: UtxoEntityFactory,
        signedTransaction: SignedTransactionContainer,
        account: String = "Account",
        createdTs: Instant = testClock.instant(),
        status: TransactionStatus = UNVERIFIED
    ): Any {
        return entityFactory.createUtxoTransactionEntity(
            signedTransaction.id.toString(),
            signedTransaction.wireTransaction.privacySalt.bytes,
            account,
            createdTs
        ).also { transaction ->
            transaction.field<MutableCollection<Any>>("components").addAll(
                signedTransaction.wireTransaction.componentGroupLists.flatMapIndexed { groupIndex, componentGroup ->
                    componentGroup.mapIndexed { leafIndex: Int, component ->
                        entityFactory.createUtxoTransactionComponentEntity(
                            transaction,
                            groupIndex,
                            leafIndex,
                            component,
                            digest("SHA-256", component).toString(),
                            createdTs
                        )
                    }
                }
            )
            transaction.field<MutableCollection<Any>>("signatures").addAll(
                signedTransaction.signatures.mapIndexed { index, signature ->
                    entityFactory.createUtxoTransactionSignatureEntity(
                        transaction,
                        index,
                        serializationService.serialize(signature).bytes,
                        digest("SHA-256", signature.by.encoded).toString(),
                        createdTs
                    )
                }
            )
            transaction.field<MutableCollection<Any>>("statuses").addAll(
                listOf(
                    entityFactory.createUtxoTransactionStatusEntity(transaction, status.value, createdTs)
                )
            )
        }
    }

    /**
     * Checks the transaction status. [floorDateTime] should be the lowest value that is a valid
     * time for the next value of `updated` for the record. The function will verify that this
     * field is at least the floor time.
     */
    private fun assertTransactionStatus(
        transactionId: String, status: TransactionStatus,
        entityFactory: UtxoEntityFactory,
        floorDateTime: Instant
    ) {
        entityManagerFactory.transaction { em ->
            val dbTransaction = em.find(entityFactory.utxoTransaction, transactionId)
            val statuses = dbTransaction.field<Collection<Any>?>("statuses")
            assertThat(statuses)
                .isNotNull
                .hasSize(1)
            with(statuses?.single()!!) {
                assertAll(
                    { assertThat(field<String>("status")).isEqualTo(status.value) },
                    { assertThat(field<Instant>("updated")).isAfterOrEqualTo(floorDateTime) }
                )
            }
        }
    }

    private fun createSignedTransaction(
        createdTs: Instant = testClock.instant(),
        seed: String = seedSequence.incrementAndGet().toString()
    ): SignedTransactionContainer {
        val transactionMetadata = transactionMetadataExample(
            cpkPackageSeed = seed,
            numberOfComponentGroups = UtxoComponentGroup.values().size
        )
        val componentGroupLists: List<List<ByteArray>> = listOf(
            listOf(jsonValidator.canonicalize(jsonMarshallingService.format(transactionMetadata)).toByteArray()),
            listOf("group1_component1".toByteArray()),
            listOf("group2_component1".toByteArray()),
            listOf(
                UtxoOutputInfoComponent(
                    null, null, notaryExample, TestContractState1::class.java.name, "contract tag"
                ).toBytes(),
                UtxoOutputInfoComponent(
                    null, null, notaryExample, TestContractState2::class.java.name, "contract tag"
                ).toBytes()
            ),
            listOf("group4_component1".toByteArray()),
            listOf("group5_component1".toByteArray()),
            transactionInputs.map { it.toBytes() },
            listOf("group7_component1".toByteArray()),
            transactionOutputs.map { it.toBytes() },
            listOf("group9_component1".toByteArray())

        )
        val wireTransaction = wireTransactionFactory.create(
            componentGroupLists,
            getPrivacySalt()
        )
        val publicKey = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair().public
        val signatures = listOf(getSignatureWithMetadataExample(publicKey, createdTs))
        return SignedTransactionContainer(wireTransaction, signatures)
    }

    private class TestUtxoTransactionReader(
        val transactionContainer: SignedTransactionContainer,
        override val account: String,
        override val status: TransactionStatus,
        override val visibleStatesIndexes: List<Int>
    ) : UtxoTransactionReader {
        override val id: SecureHash
            get() = transactionContainer.id
        override val privacySalt: PrivacySalt
            get() = transactionContainer.wireTransaction.privacySalt
        override val rawGroupLists: List<List<ByteArray>>
            get() = transactionContainer.wireTransaction.componentGroupLists
        override val signatures: List<DigitalSignatureAndMetadata>
            get() = transactionContainer.signatures
        override val cpkMetadata: List<CordaPackageSummary>
            get() = (transactionContainer.wireTransaction.metadata as TransactionMetadataInternal).getCpkMetadata()

        override fun getProducedStates(): List<StateAndRef<ContractState>> {
            return listOf(
                stateAndRef<TestContract>(TestContractState1(), id, 0),
                stateAndRef<TestContract>(TestContractState2(), id, 1)
            )
        }

        override fun getConsumedStates(persistenceService: UtxoPersistenceService): List<StateAndRef<ContractState>> {
            TODO("Not yet implemented")
        }

        override fun getConsumedStateRefs(): List<StateRef> {
            return listOf(StateRef(SecureHashImpl("SHA-256", ByteArray(12)), 1))
        }

        private inline fun <reified C : Contract> stateAndRef(
            state: ContractState,
            transactionId: SecureHash,
            index: Int
        ): StateAndRef<ContractState> {
            return StateAndRefImpl(
                object : TransactionState<ContractState> {

                    override fun getContractState(): ContractState {
                        return state
                    }

                    override fun getContractStateType(): Class<ContractState> {
                        return state.javaClass
                    }

                    override fun getContractType(): Class<out Contract> {
                        return C::class.java
                    }

                    override fun getNotary(): Party {
                        return notaryExample
                    }

                    override fun getEncumbranceGroup(): EncumbranceGroup? {
                        return null
                    }
                },

                StateRef(transactionId, index)
            )
        }
    }

    class TestContract : Contract {
        override fun verify(transaction: UtxoLedgerTransaction) {
        }
    }

    class TestContractState1 : ContractState {
        override fun getParticipants(): List<PublicKey> {
            return emptyList()
        }
    }

    class TestContractState2 : ContractState {
        override fun getParticipants(): List<PublicKey> {
            return emptyList()
        }
    }

    private fun ContractState.toBytes() = serializationService.serialize(this).bytes
    private fun StateRef.toBytes() = serializationService.serialize(this).bytes
    private fun UtxoOutputInfoComponent.toBytes() = serializationService.serialize(this).bytes

    private fun digest(algorithm: String, data: ByteArray) =
        SecureHashImpl(algorithm, MessageDigest.getInstance(algorithm).digest(data))

    private fun nextTime() = testClock.peekTime()
}
