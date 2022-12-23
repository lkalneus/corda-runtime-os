package net.corda.ledger.utxo.flow.impl.transaction.factory

import net.corda.ledger.common.data.transaction.WireTransaction
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.ledger.utxo.transaction.UtxoLedgerTransaction

interface UtxoLedgerTransactionFactory {
    @Suspendable
    fun create(
        wireTransaction: WireTransaction
    ): UtxoLedgerTransaction
}