/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the watch's long-term pairing key (`K1`) in a key held by the Android Keystore.
 *
 * `K1` is what lets anything decrypt this watch's traffic, so it does not belong in
 * plain preferences where any backup or `adb` pull would carry it off. The wrapping key
 * never leaves the Keystore, and is not backed up, so a restore onto a new phone simply
 * re-pairs rather than moving the secret.
 */
object SecretVault {
    private const val TAG = "SecretVault"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "recmf.auth-key-wrapper"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    /** Returns base64 of `iv || ciphertext`, or null if the Keystore refused. */
    fun seal(plaintext: ByteArray): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val sealed = cipher.iv + cipher.doFinal(plaintext)
        Base64.encodeToString(sealed, Base64.NO_WRAP)
    } catch (e: GeneralSecurityException) {
        Log.e(TAG, "Could not seal the pairing key", e)
        null
    }

    /**
     * Returns null when the blob is missing, corrupt, or was sealed under a key this
     * device no longer has — after a restore onto new hardware, for instance. Callers
     * treat that as "not paired" and pair again.
     */
    fun unseal(encoded: String): ByteArray? = try {
        val sealed = Base64.decode(encoded, Base64.NO_WRAP)
        if (sealed.size <= IV_SIZE) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    wrappingKey(),
                    GCMParameterSpec(TAG_BITS, sealed, 0, IV_SIZE),
                )
            }
            cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE)
        }
    } catch (e: GeneralSecurityException) {
        Log.w(TAG, "Stored pairing key could not be read; will re-pair", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Stored pairing key is not valid base64; will re-pair", e)
        null
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
