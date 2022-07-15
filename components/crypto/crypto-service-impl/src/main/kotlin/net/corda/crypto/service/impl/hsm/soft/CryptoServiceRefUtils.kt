package net.corda.crypto.service.impl.hsm.soft

import net.corda.crypto.persistence.signing.SigningKeySaveContext
import net.corda.crypto.persistence.signing.SigningPublicKeySaveContext
import net.corda.crypto.persistence.signing.SigningWrappedKeySaveContext
import net.corda.crypto.persistence.signing.SigningCachedKey
import net.corda.crypto.service.CryptoServiceRef
import net.corda.v5.cipher.suite.CRYPTO_CATEGORY
import net.corda.v5.cipher.suite.CRYPTO_TENANT_ID
import net.corda.v5.cipher.suite.GeneratedKey
import net.corda.v5.cipher.suite.GeneratedPublicKey
import net.corda.v5.cipher.suite.GeneratedWrappedKey
import net.corda.v5.cipher.suite.KeyGenerationSpec
import net.corda.v5.cipher.suite.KeyMaterialSpec
import net.corda.v5.cipher.suite.SharedSecretAliasSpec
import net.corda.v5.cipher.suite.SharedSecretWrappedSpec
import net.corda.v5.cipher.suite.SigningAliasSpec
import net.corda.v5.cipher.suite.SigningWrappedSpec
import net.corda.v5.cipher.suite.schemes.KeyScheme
import net.corda.v5.crypto.SignatureSpec
import java.security.PublicKey

fun CryptoServiceRef.getSupportedSchemes(): List<String> =
    instance.supportedSchemes.map { it.key.codeName }

fun CryptoServiceRef.generateKeyPair(
    alias: String?,
    scheme: KeyScheme,
    context: Map<String, String>
): GeneratedKey =
    instance.generateKeyPair(
        KeyGenerationSpec(
            keyScheme = scheme,
            alias = alias,
            masterKeyAlias = masterKeyAlias,
            secret = aliasSecret
        ),
        context + mapOf(
            CRYPTO_TENANT_ID to tenantId,
            CRYPTO_CATEGORY to category
        )
    )

fun CryptoServiceRef.toSaveKeyContext(
    key: GeneratedKey,
    alias: String?,
    scheme: KeyScheme,
    externalId: String?
): SigningKeySaveContext =
    when (key) {
        is GeneratedPublicKey -> SigningPublicKeySaveContext(
            key = key,
            alias = alias,
            keyScheme = scheme,
            category = category,
            associationId = associationId,
            externalId = externalId,
        )
        is GeneratedWrappedKey -> SigningWrappedKeySaveContext(
            key = key,
            masterKeyAlias = masterKeyAlias,
            externalId = externalId,
            alias = alias,
            keyScheme = scheme,
            category = category,
            associationId = associationId,
        )
        else -> throw IllegalStateException("Unknown key generation response: ${key::class.java.name}")
    }

fun CryptoServiceRef.sign(
    record: SigningCachedKey,
    scheme: KeyScheme,
    signatureSpec: SignatureSpec,
    data: ByteArray,
    context: Map<String, String>
): ByteArray {
    val spec = if (record.keyMaterial != null) {
        require(record.keyMaterial!!.isNotEmpty()) {
            "The key material is empty."
        }
        require(record.encodingVersion != null) {
            "The encoding version is missing."
        }
        SigningWrappedSpec(
            keyMaterialSpec = KeyMaterialSpec(
                keyMaterial = record.keyMaterial!!,
                masterKeyAlias = record.masterKeyAlias,
                encodingVersion = record.encodingVersion!!
            ),
            keyScheme = scheme,
            signatureSpec = signatureSpec
        )
    } else {
        require(!record.hsmAlias.isNullOrBlank()) {
            "The hsm assigned alias is missing."
        }
        SigningAliasSpec(
            hsmAlias = record.hsmAlias!!,
            keyScheme = scheme,
            signatureSpec = signatureSpec
        )
    }
    return instance.sign(
        spec, data, context + mapOf(
            CRYPTO_TENANT_ID to tenantId
        )
    )
}

fun CryptoServiceRef.deriveSharedSecret(
    record: SigningCachedKey,
    scheme: KeyScheme,
    otherPublicKey: PublicKey,
    context: Map<String, String>
): ByteArray {
    val spec = if (record.keyMaterial != null) {
        require(record.keyMaterial!!.isNotEmpty()) {
            "The key material is empty."
        }
        require(record.encodingVersion != null) {
            "The encoding version is missing."
        }
        SharedSecretWrappedSpec(
            keyMaterialSpec = KeyMaterialSpec(
                keyMaterial = record.keyMaterial!!,
                masterKeyAlias = record.masterKeyAlias,
                encodingVersion = record.encodingVersion!!
            ),
            keyScheme = scheme,
            otherPublicKey = otherPublicKey
        )
    } else {
        require(!record.hsmAlias.isNullOrBlank()) {
            "The hsm assigned alias is missing."
        }
        SharedSecretAliasSpec(
            hsmAlias = record.hsmAlias!!,
            keyScheme = scheme,
            otherPublicKey = otherPublicKey
        )
    }
    return instance.deriveSharedSecret(
        spec, context + mapOf(
            CRYPTO_TENANT_ID to tenantId
        )
    )
}
