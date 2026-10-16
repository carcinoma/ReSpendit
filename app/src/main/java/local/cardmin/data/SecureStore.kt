// SPDX-License-Identifier: Apache-2.0
package local.cardmin.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Session data at rest. Unlike the original app (plain SharedPreferences, REPORT §9.1), every
 * value is AES-GCM encrypted with a non-exportable Android Keystore key.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("cardmin_store", Context.MODE_PRIVATE)

    var sessionToken by item("sessionToken")
    var refreshToken by item("refreshToken")
    var appUserId by item("appUserId")
    var deviceId by item("deviceId")
    var email by item("email")
    /** "1" once the restricted key of the current binding was accepted by the server. */
    var restrictedKeyRegistered by item("restrictedKeyRegistered")

    fun clearSession() {
        sessionToken = null
        refreshToken = null
        appUserId = null
    }

    /** Device binding is tied to the Keystore keys, so it is removed together with them. */
    fun clearDevice() {
        deviceId = null
        restrictedKeyRegistered = null
    }

    private fun item(name: String) = object : kotlin.properties.ReadWriteProperty<Any?, String?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = read(name)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String?) = write(name, value)
    }

    @Synchronized
    private fun read(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_LEN))
            String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
        } catch (e: Exception) {
            prefs.edit().remove(name).apply()
            null
        }
    }

    @Synchronized
    private fun write(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "cardmin_store"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
