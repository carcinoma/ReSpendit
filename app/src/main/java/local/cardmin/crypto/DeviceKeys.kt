// SPDX-License-Identifier: Apache-2.0
package local.cardmin.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * The two EC P-256 signing keys used by the Solaris device binding (client-spec/crypto.md).
 *
 *  - device key: registered with POST /device. Signs the binding TAN, SCA challenges and the
 *    restricted public key. (Java alias KEY_3D_SECURE in the original.)
 *  - restricted key: registered with POST /device/{id}/key. Signs the PIN JWE and phone-number
 *    deletion. (Java alias UNRESTRICTED in the original.) The original only shows a cosmetic
 *    biometric prompt; here the key itself requires user authentication for every use.
 *
 * Wire formats: public key = lowercase hex "04" + X(32) + Y(32); signature = lowercase hex of the
 * ASN.1 DER SHA256withECDSA signature over the UTF-8 bytes of the given string.
 */
object DeviceKeys {
    private const val DEVICE = "cardmin_device"
    private const val RESTRICTED = "cardmin_restricted"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun hasDeviceKey() = keyStore().containsAlias(DEVICE)
    fun hasRestrictedKey() = keyStore().containsAlias(RESTRICTED)

    fun deleteAll() {
        val ks = keyStore()
        listOf(DEVICE, RESTRICTED).forEach { if (ks.containsAlias(it)) ks.deleteEntry(it) }
    }

    /** Fresh device key + restricted key, as the original does before every binding. */
    fun regenerate() {
        deleteAll()
        generate(DEVICE, userAuth = false)
        generate(RESTRICTED, userAuth = true)
    }

    fun regenerateRestricted() {
        val ks = keyStore()
        if (ks.containsAlias(RESTRICTED)) ks.deleteEntry(RESTRICTED)
        generate(RESTRICTED, userAuth = true)
    }

    fun devicePublicKeyHex() = publicKeyHex(DEVICE)
    fun restrictedPublicKeyHex() = publicKeyHex(RESTRICTED)

    fun signWithDeviceKey(text: String): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey(DEVICE))
        sig.update(text.toByteArray(Charsets.UTF_8))
        return sig.sign().toHex()
    }

    /**
     * A Signature initialised with the restricted key. It must be unlocked through a
     * BiometricPrompt CryptoObject before [signPrepared] works. Throws
     * KeyPermanentlyInvalidatedException if the screen lock was removed or changed.
     */
    fun prepareRestrictedSignature(): Signature =
        Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey(RESTRICTED)) }

    fun signPrepared(sig: Signature, text: String): String {
        sig.update(text.toByteArray(Charsets.UTF_8))
        return sig.sign().toHex()
    }

    private fun generate(alias: String, userAuth: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
        if (userAuth) {
            spec.setUserAuthenticationRequired(true)
            spec.setUserAuthenticationParameters(
                0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
            initialize(spec.build())
            generateKeyPair()
        }
    }

    private fun privateKey(alias: String) =
        keyStore().getKey(alias, null) as? PrivateKey ?: error("Schlüssel $alias fehlt – Gerät neu verknüpfen")

    private fun publicKeyHex(alias: String): String {
        val pub = keyStore().getCertificate(alias)?.publicKey as? ECPublicKey
            ?: error("Schlüssel $alias fehlt – Gerät neu verknüpfen")
        return encodePoint(pub)
    }

    /** Uncompressed point as lowercase hex: "04" + X(32 bytes) + Y(32 bytes). */
    internal fun encodePoint(pub: ECPublicKey) = "04" + pub.w.affineX.to32().toHex() + pub.w.affineY.to32().toHex()

    private fun BigInteger.to32(): ByteArray {
        val b = toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    internal fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
