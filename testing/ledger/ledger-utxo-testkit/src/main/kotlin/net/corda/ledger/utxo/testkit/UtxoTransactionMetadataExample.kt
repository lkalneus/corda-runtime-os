package net.corda.ledger.utxo.testkit

import net.corda.ledger.common.data.transaction.TransactionMetadataImpl
import net.corda.ledger.common.data.transaction.WireTransactionDigestSettings
import net.corda.ledger.common.testkit.cpiPackageSummaryExample
import net.corda.ledger.common.testkit.cpkPackageSummaryListExample
import net.corda.ledger.utxo.data.transaction.UtxoComponentGroup
import net.corda.ledger.utxo.data.transaction.UtxoLedgerTransactionImpl
import net.corda.ledger.utxo.data.transaction.UtxoTransactionMetadata

fun utxoTransactionMetadataExample(cpkPackageSeed: String? = null) = TransactionMetadataImpl(mapOf(
    TransactionMetadataImpl.LEDGER_MODEL_KEY to UtxoLedgerTransactionImpl::class.java.name,
    TransactionMetadataImpl.LEDGER_VERSION_KEY to UtxoTransactionMetadata.LEDGER_VERSION,
    TransactionMetadataImpl.TRANSACTION_SUBTYPE_KEY to UtxoTransactionMetadata.TransactionSubtype.GENERAL,
    TransactionMetadataImpl.DIGEST_SETTINGS_KEY to WireTransactionDigestSettings.defaultValues,
    TransactionMetadataImpl.PLATFORM_VERSION_KEY to 123,
    TransactionMetadataImpl.CPI_METADATA_KEY to cpiPackageSummaryExample,
    TransactionMetadataImpl.CPK_METADATA_KEY to cpkPackageSummaryListExample(cpkPackageSeed),
    TransactionMetadataImpl.SCHEMA_VERSION_KEY to TransactionMetadataImpl.SCHEMA_VERSION,
    TransactionMetadataImpl.NUMBER_OF_COMPONENT_GROUPS to UtxoComponentGroup.values().size
// TODO
// List of component group types
// Membership group parameters hash
))