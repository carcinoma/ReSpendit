// SPDX-License-Identifier: Apache-2.0
package local.cardmin.crypto

import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSAEncrypter
import com.nimbusds.jose.jwk.RSAKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * PIN encryption, as in the original (client-spec/crypto.md §6): JWE compact, alg RSA-OAEP-256,
 * enc A256CBC-HS512, protected header with alg+enc only, plaintext {"pin":"<digits>"}, encrypted
 * to the RSA JWK returned by GET …/card/{id}/pin. The JWK kid is sent separately as keyId.
 */
object PinCrypto {
    class Encrypted(val jwe: String, val keyId: String)

    fun encrypt(jwkJson: String, pin: String): Encrypted {
        require(pin.all { it.isDigit() })
        val jwk = RSAKey.parse(jwkJson)
        val payload = JsonObject(mapOf("pin" to JsonPrimitive(pin))).let { Json.encodeToString(JsonObject.serializer(), it) }
        val jwe = JWEObject(JWEHeader(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256CBC_HS512), Payload(payload))
        jwe.encrypt(RSAEncrypter(jwk.toRSAPublicKey()))
        return Encrypted(jwe.serialize(), jwk.keyID ?: error("Server-Schlüssel ohne kid"))
    }
}
