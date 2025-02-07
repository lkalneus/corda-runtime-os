package net.corda.ledger.utxo.flow.impl.flows.backchain.v1

import net.corda.crypto.core.SecureHashImpl
import net.corda.ledger.common.data.transaction.CordaPackageSummaryImpl
import net.corda.ledger.common.data.transaction.TransactionStatus.UNVERIFIED
import net.corda.ledger.utxo.flow.impl.flows.backchain.TopologicalSort
import net.corda.ledger.utxo.flow.impl.persistence.TransactionExistenceStatus
import net.corda.ledger.utxo.flow.impl.persistence.UtxoLedgerPersistenceService
import net.corda.v5.application.messaging.FlowSession
import net.corda.v5.crypto.SecureHash
import net.corda.v5.ledger.utxo.StateRef
import net.corda.v5.ledger.utxo.transaction.UtxoSignedTransaction
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@Suppress("MaxLineLength")
class TransactionBackchainReceiverFlowV1Test {

    private companion object {
        val TX_ID_1 = SecureHashImpl("SHA", byteArrayOf(2, 2, 2, 2))
        val TX_ID_2 = SecureHashImpl("SHA", byteArrayOf(3, 3, 3, 3))
        val TX_ID_3 = SecureHashImpl("SHA", byteArrayOf(4, 4, 4, 4))
        val TX_3_INPUT_DEPENDENCY_STATE_REF_1 = StateRef(TX_ID_3, 0)
        val TX_3_INPUT_DEPENDENCY_STATE_REF_2 = StateRef(TX_ID_3, 1)

        val TX_3_INPUT_REFERENCE_DEPENDENCY_STATE_REF_1 = StateRef(TX_ID_3, 0)
        val TX_3_INPUT_REFERENCE_DEPENDENCY_STATE_REF_2 = StateRef(TX_ID_3, 1)

        val PACKAGE_SUMMARY = CordaPackageSummaryImpl("name", "version", "hash", "checksum")
    }

    private val utxoLedgerPersistenceService = mock<UtxoLedgerPersistenceService>()
    private val session = mock<FlowSession>()

    private val retrievedTransaction1 = mock<UtxoSignedTransaction>()
    private val retrievedTransaction2 = mock<UtxoSignedTransaction>()
    private val retrievedTransaction3 = mock<UtxoSignedTransaction>()

    @Test
    fun `a resolved transaction has its dependencies retrieved from its peer and persisted`() {
        whenever(utxoLedgerPersistenceService.find(any(), any())).thenReturn(null)

        whenever(session.sendAndReceive(eq(List::class.java), any())).thenReturn(
            listOf(retrievedTransaction1),
            listOf(retrievedTransaction2),
            listOf(retrievedTransaction3)
        )

        whenever(utxoLedgerPersistenceService.persistIfDoesNotExist(any(), eq(UNVERIFIED)))
            .thenReturn(TransactionExistenceStatus.DOES_NOT_EXIST to listOf(PACKAGE_SUMMARY))

        whenever(retrievedTransaction1.id).thenReturn(TX_ID_1)
        whenever(retrievedTransaction1.inputStateRefs).thenReturn(listOf(TX_3_INPUT_DEPENDENCY_STATE_REF_1))
        whenever(retrievedTransaction1.referenceStateRefs).thenReturn(listOf(TX_3_INPUT_REFERENCE_DEPENDENCY_STATE_REF_1))

        whenever(retrievedTransaction2.id).thenReturn(TX_ID_2)
        whenever(retrievedTransaction2.inputStateRefs).thenReturn(listOf(TX_3_INPUT_DEPENDENCY_STATE_REF_2))
        whenever(retrievedTransaction2.referenceStateRefs).thenReturn(listOf(TX_3_INPUT_REFERENCE_DEPENDENCY_STATE_REF_2))

        whenever(retrievedTransaction3.id).thenReturn(TX_ID_3)
        whenever(retrievedTransaction3.inputStateRefs).thenReturn(emptyList())
        whenever(retrievedTransaction3.referenceStateRefs).thenReturn(emptyList())

        assertThat(callTransactionBackchainReceiverFlow(setOf(TX_ID_1, TX_ID_2)).complete()).isEqualTo(listOf(TX_ID_3, TX_ID_2, TX_ID_1))

        verify(session).sendAndReceive(List::class.java, TransactionBackchainRequestV1.Get(setOf(TX_ID_1)))
        verify(session).sendAndReceive(List::class.java, TransactionBackchainRequestV1.Get(setOf(TX_ID_2)))
        verify(session).sendAndReceive(List::class.java, TransactionBackchainRequestV1.Get(setOf(TX_ID_3)))
        verify(session).send(TransactionBackchainRequestV1.Stop)
        verify(utxoLedgerPersistenceService).persistIfDoesNotExist(retrievedTransaction1, UNVERIFIED)
        verify(utxoLedgerPersistenceService).persistIfDoesNotExist(retrievedTransaction2, UNVERIFIED)
        verify(utxoLedgerPersistenceService).persistIfDoesNotExist(retrievedTransaction3, UNVERIFIED)
    }

    @Test
    fun `a transaction without any dependencies does not need resolving`() {
        assertThat(callTransactionBackchainReceiverFlow(emptySet()).complete()).isEmpty()

        verifyNoInteractions(session)
        verifyNoInteractions(utxoLedgerPersistenceService)
    }

    @Test
    fun `receiving a transaction that is stored locally as UNVERIFIED has its dependencies added to the transactions to retrieve`() {
        whenever(utxoLedgerPersistenceService.find(any(), any())).thenReturn(null)

        whenever(session.sendAndReceive(eq(List::class.java), any())).thenReturn(
            listOf(retrievedTransaction1),
            listOf(retrievedTransaction2),
            listOf(retrievedTransaction3)
        )

        whenever(utxoLedgerPersistenceService.persistIfDoesNotExist(any(), eq(UNVERIFIED)))
            .thenReturn(TransactionExistenceStatus.DOES_NOT_EXIST to listOf(PACKAGE_SUMMARY))

        whenever(retrievedTransaction1.id).thenReturn(TX_ID_1)
        whenever(retrievedTransaction1.inputStateRefs).thenReturn(listOf(TX_3_INPUT_DEPENDENCY_STATE_REF_1))
        whenever(retrievedTransaction1.referenceStateRefs).thenReturn(listOf(TX_3_INPUT_REFERENCE_DEPENDENCY_STATE_REF_1))

        whenever(retrievedTransaction2.id).thenReturn(TX_ID_2)
        whenever(retrievedTransaction2.inputStateRefs).thenReturn(listOf(TX_3_INPUT_DEPENDENCY_STATE_REF_2))
        whenever(retrievedTransaction2.referenceStateRefs).thenReturn(listOf(TX_3_INPUT_REFERENCE_DEPENDENCY_STATE_REF_2))

        whenever(retrievedTransaction3.id).thenReturn(TX_ID_3)
        whenever(retrievedTransaction3.inputStateRefs).thenReturn(emptyList())
        whenever(retrievedTransaction3.referenceStateRefs).thenReturn(emptyList())

        assertThat(callTransactionBackchainReceiverFlow(setOf(TX_ID_1, TX_ID_2)).complete()).isEqualTo(listOf(TX_ID_3, TX_ID_2, TX_ID_1))

        verify(session).sendAndReceive(List::class.java, TransactionBackchainRequestV1.Get(setOf(TX_ID_1)))
        verify(session).sendAndReceive(List::class.java, TransactionBackchainRequestV1.Get(setOf(TX_ID_2)))
        verify(session).sendAndReceive(List::class.java, TransactionBackchainRequestV1.Get(setOf(TX_ID_3)))
        verify(utxoLedgerPersistenceService).persistIfDoesNotExist(retrievedTransaction1, UNVERIFIED)
        verify(utxoLedgerPersistenceService).persistIfDoesNotExist(retrievedTransaction2, UNVERIFIED)
        verify(utxoLedgerPersistenceService).persistIfDoesNotExist(retrievedTransaction3, UNVERIFIED)
    }

    @Test
    fun `receiving a transaction that is stored locally as VERIFIED does not have its dependencies added to the transactions to retrieve`() {
        whenever(utxoLedgerPersistenceService.find(TX_ID_1)).thenReturn(retrievedTransaction1)

        whenever(session.sendAndReceive(eq(List::class.java), any())).thenReturn(
            listOf(retrievedTransaction1),
            listOf(retrievedTransaction2)
        )

        whenever(utxoLedgerPersistenceService.persistIfDoesNotExist(any(), eq(UNVERIFIED)))
            .thenReturn(TransactionExistenceStatus.DOES_NOT_EXIST to listOf(PACKAGE_SUMMARY))

        whenever(utxoLedgerPersistenceService.persistIfDoesNotExist(retrievedTransaction1, UNVERIFIED))
            .thenReturn(TransactionExistenceStatus.VERIFIED to listOf(PACKAGE_SUMMARY))

        whenever(retrievedTransaction1.id).thenReturn(TX_ID_1)
        whenever(retrievedTransaction1.inputStateRefs).thenReturn(listOf(TX_3_INPUT_DEPENDENCY_STATE_REF_1))
        whenever(retrievedTransaction1.referenceStateRefs).thenReturn(listOf(TX_3_INPUT_REFERENCE_DEPENDENCY_STATE_REF_1))

        whenever(retrievedTransaction2.id).thenReturn(TX_ID_2)
        whenever(retrievedTransaction2.inputStateRefs).thenReturn(emptyList())
        whenever(retrievedTransaction2.referenceStateRefs).thenReturn(emptyList())

        assertThat(callTransactionBackchainReceiverFlow(setOf(TX_ID_1, TX_ID_2)).complete()).isEqualTo(listOf(TX_ID_2))

        verify(session).sendAndReceive(List::class.java, TransactionBackchainRequestV1.Get(setOf(TX_ID_1)))
        verify(session).sendAndReceive(List::class.java, TransactionBackchainRequestV1.Get(setOf(TX_ID_2)))
        verify(session, never()).sendAndReceive(List::class.java, TransactionBackchainRequestV1.Get(setOf(TX_ID_3)))
        verify(utxoLedgerPersistenceService).persistIfDoesNotExist(retrievedTransaction1, UNVERIFIED)
        verify(utxoLedgerPersistenceService).persistIfDoesNotExist(retrievedTransaction2, UNVERIFIED)
        verify(utxoLedgerPersistenceService, never()).persistIfDoesNotExist(retrievedTransaction3, UNVERIFIED)
    }

    @Test
    fun `receiving a transaction that was not included in the requested batch of transactions throws an exception`() {
        whenever(utxoLedgerPersistenceService.find(TX_ID_1)).thenReturn(retrievedTransaction1)

        whenever(session.sendAndReceive(eq(List::class.java), any())).thenReturn(
            listOf(retrievedTransaction1),
            listOf(retrievedTransaction2)
        )

        whenever(utxoLedgerPersistenceService.persistIfDoesNotExist(retrievedTransaction1, UNVERIFIED))
            .thenReturn(TransactionExistenceStatus.DOES_NOT_EXIST to listOf(PACKAGE_SUMMARY))

        whenever(retrievedTransaction1.id).thenReturn(TX_ID_1)
        whenever(retrievedTransaction1.inputStateRefs).thenReturn(listOf(TX_3_INPUT_DEPENDENCY_STATE_REF_1))
        whenever(retrievedTransaction1.referenceStateRefs).thenReturn(listOf(TX_3_INPUT_REFERENCE_DEPENDENCY_STATE_REF_1))

        whenever(retrievedTransaction2.id).thenReturn(TX_ID_2)

        assertThatThrownBy { callTransactionBackchainReceiverFlow(setOf(TX_ID_1)) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)

        verify(session).sendAndReceive(List::class.java, TransactionBackchainRequestV1.Get(setOf(TX_ID_1)))
        verify(utxoLedgerPersistenceService).persistIfDoesNotExist(retrievedTransaction1, UNVERIFIED)
        verify(utxoLedgerPersistenceService, never()).persistIfDoesNotExist(retrievedTransaction2, UNVERIFIED)
    }

    private fun callTransactionBackchainReceiverFlow(originalTransactionsToRetrieve: Set<SecureHash>): TopologicalSort {
        return TransactionBackchainReceiverFlowV1(
            setOf(SecureHashImpl("SHA", byteArrayOf(1, 1, 1, 1))),
            originalTransactionsToRetrieve, session
        ).apply { utxoLedgerPersistenceService = this@TransactionBackchainReceiverFlowV1Test.utxoLedgerPersistenceService }
            .call()
    }
}