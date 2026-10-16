// SPDX-License-Identifier: Apache-2.0
package local.cardmin

import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.crypto.RSADecrypter
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import local.cardmin.crypto.DeviceKeys
import local.cardmin.crypto.DeviceKeys.toHex
import local.cardmin.crypto.PinCrypto
import local.cardmin.data.ApiJson
import local.cardmin.data.Card
import local.cardmin.data.DeviceConfirmBody
import local.cardmin.data.MobileNumberDeleteConfirmBody
import local.cardmin.data.PendingActions
import local.cardmin.data.RestrictedKeyBody
import local.cardmin.data.ScaAuthorizationBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class WireFormatTest {
    private fun keys(body: String) = Json.parseToJsonElement(body).jsonObject.keys

    @Test fun pinJweMatchesOriginalFormat() {
        val rsa = RSAKeyGenerator(2048).keyID("kid-1").keyUse(KeyUse.ENCRYPTION).generate()
        val enc = PinCrypto.encrypt(rsa.toPublicJWK().toJSONString(), "4711")
        assertEquals("kid-1", enc.keyId)
        val jwe = JWEObject.parse(enc.jwe)
        // protected header: alg + enc only (no kid)
        assertEquals(setOf("alg", "enc"), jwe.header.toJSONObject().keys)
        assertEquals("RSA-OAEP-256", jwe.header.algorithm.name)
        assertEquals("A256CBC-HS512", jwe.header.encryptionMethod.name)
        jwe.decrypt(RSADecrypter(rsa))
        assertEquals("""{"pin":"4711"}""", jwe.payload.toString())
    }

    @Test fun publicKeyIsUncompressedHexPoint() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pub = kp.public as ECPublicKey
        val hex = DeviceKeys.encodePoint(pub)
        assertEquals(130, hex.length)
        assertTrue(hex.startsWith("04"))
        assertEquals(hex.lowercase(), hex)
        assertEquals(pub.w.affineX, BigInteger(hex.substring(2, 66), 16))
        assertEquals(pub.w.affineY, BigInteger(hex.substring(66), 16))

        // signature = hex(DER) over UTF-8 of the server string, verifiable with SHA256withECDSA
        val text = "123456"
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(text.toByteArray()); sign() }
        val sigHex = der.toHex()
        assertTrue(sigHex.startsWith("30"))
        val decoded = sigHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val ok = Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(text.toByteArray()); verify(decoded) }
        assertTrue(ok)
    }

    @Test fun requestBodiesUseOriginalKeys() {
        assertEquals(setOf("key", "signature"), keys(ApiJson.encodeToString(RestrictedKeyBody.serializer(), RestrictedKeyBody("04ab", "30cd"))))
        assertEquals(setOf("challengeId", "signedText"), keys(ApiJson.encodeToString(DeviceConfirmBody.serializer(), DeviceConfirmBody("c", "s"))))
        // explicit nulls like Dart's toJson
        val del = ApiJson.encodeToString(MobileNumberDeleteConfirmBody.serializer(), MobileNumberDeleteConfirmBody("r", tan = "1"))
        assertEquals("""{"changeRequestId":"r","tan":"1","deviceId":null,"signature":null}""", del)
        val sca = ApiJson.encodeToString(ScaAuthorizationBody.serializer(), ScaAuthorizationBody("x", null))
        assertEquals("""{"authenticateChangeRequestId":"x","deviceId":null}""", sca)
    }

    @Test fun responsesTolerateUnknownFields() {
        val card = ApiJson.decodeFromString(Card.serializer(), """
            {"id":7,"line1":"MAX MUSTER","maskedPan":"**** 1234","status":"CARD_SETUP_REQUIRED","newField":1,
             "quickActions":[{"quickActionType":"SETUP_CARD","benefitType":"SPENDITCARD"}],
             "details":{"benefitType":"SPENDITCARD","zipCodeChangeType":null},"formattedExpirationDate":"09/29"}
        """)
        assertEquals(false, card.isSetupCompleted)
        assertTrue(card.has("SETUP_CARD"))
        val p = ApiJson.decodeFromString(PendingActions.serializer(), """
            {"fraudCases":[],"scaChallenges":[{"merchantName":"Shop","authenticateChangeRequestId":"a","challengedAt":"2026-10-02T10:00:00Z","challengeId":"c","amountCents":1999}],"updatePopUp":null}
        """)
        assertEquals(1999L, p.scaChallenges.single().amountCents)
    }

    @Test fun homeBalanceIsMatchedByCardId() {
        val home = ApiJson.decodeFromString(local.cardmin.data.HomePage.serializer(), """
            {"firstName":"X","greetingsText":"Hi","quickActions":[],"benefits":[
              {"imageUrl":null,"availableAmount":4250,"detail":{"benefitType":"SPENDITCARD","subscriptionStatus":{"value":"ACTIVE"},"cardStatus":"ACTIVE","cardId":7}},
              {"availableAmount":0,"detail":{"benefitType":"DEALS","cardId":null}}]}
        """)
        assertEquals(4250L, home.benefits.first { it.detail?.cardId == 7L }.availableAmount)
    }
}
