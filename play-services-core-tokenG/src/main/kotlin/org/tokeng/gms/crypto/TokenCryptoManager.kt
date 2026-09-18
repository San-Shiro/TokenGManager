/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tokeng.gms.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object TokenCryptoManager {
    private const val TAG = "TokenCryptoManager"
    private const val PREFS_NAME = "tokeng_vault_security"
    private const val KEY_VAULT_SALT = "vault_master_salt"
    private const val KEY_VAULT_VERIFIER = "vault_verifier_blob"
    private const val VERIFIER_MAGIC = "TOKEN_G_VAULT_VERIFIER_OK_V1"

    private const val PBKDF2_ITERATIONS = 600_000
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val SALT_LENGTH_BYTES = 16

    private val secureRandom = SecureRandom()

    @Volatile
    private var activeMasterKey: ByteArray? = null

    /**
     * Checks if a local encrypted vault has already been established on this device.
     */
    fun isVaultInitialized(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_VAULT_VERIFIER) && prefs.contains(KEY_VAULT_SALT)
    }

    /**
     * Checks if the vault is currently unlocked and key is present in memory.
     */
    fun isUnlocked(): Boolean {
        return activeMasterKey != null
    }

    /**
     * Zeroizes in-memory cryptographic keys and locks the vault.
     */
    @Synchronized
    fun lock() {
        activeMasterKey?.let { key ->
            Arrays.fill(key, 0.toByte())
        }
        activeMasterKey = null
        Log.d(TAG, "Vault locked. In-memory keys zeroized.")
    }

    /**
     * Initializes a brand-new vault with a fresh salt and encrypted verifier blob.
     */
    @Synchronized
    fun initializeVault(context: Context, password: CharArray): Boolean {
        return try {
            val salt = ByteArray(SALT_LENGTH_BYTES).apply { secureRandom.nextBytes(this) }
            val key = deriveKey(password, salt)

            val verifierBlob = encryptWithKey(key, VERIFIER_MAGIC.toByteArray(Charsets.UTF_8), "vault_verifier")
            val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_VAULT_SALT, saltB64)
                .putString(KEY_VAULT_VERIFIER, verifierBlob)
                .apply()

            activeMasterKey = key.encoded
            Log.i(TAG, "Vault initialized successfully with PBKDF2 (600k iters) & AES-256-GCM.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize vault", e)
            false
        }
    }

    /**
     * Unlocks an existing vault using the user-provided password.
     */
    @Synchronized
    fun unlockVault(context: Context, password: CharArray): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saltB64 = prefs.getString(KEY_VAULT_SALT, null) ?: return false
        val verifierBlob = prefs.getString(KEY_VAULT_VERIFIER, null) ?: return false

        return try {
            val salt = Base64.decode(saltB64, Base64.NO_WRAP)
            val key = deriveKey(password, salt)

            val decryptedVerifier = decryptWithKey(key, verifierBlob, "vault_verifier")
            val verifierStr = String(decryptedVerifier, Charsets.UTF_8)

            if (verifierStr == VERIFIER_MAGIC) {
                activeMasterKey = key.encoded
                Log.i(TAG, "Vault unlocked successfully.")
                true
            } else {
                Log.w(TAG, "Verifier mismatch. Incorrect password.")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Decryption failed during vault unlock (incorrect password or corrupted data): ${e.message}")
            false
        }
    }

    /**
     * Encrypts plaintext string using the active in-memory master key with AAD binding.
     */
    fun encrypt(plaintext: String, aad: String): String {
        val keyBytes = activeMasterKey ?: throw IllegalStateException("Vault is locked; active master key not loaded")
        val key = SecretKeySpec(keyBytes, "AES")
        return encryptWithKey(key, plaintext.toByteArray(Charsets.UTF_8), aad)
    }

    /**
     * Decrypts ciphertext string using the active in-memory master key with AAD binding.
     */
    fun decrypt(ciphertextBlob: String, aad: String): String {
        val keyBytes = activeMasterKey ?: throw IllegalStateException("Vault is locked; active master key not loaded")
        val key = SecretKeySpec(keyBytes, "AES")
        val decrypted = decryptWithKey(key, ciphertextBlob, aad)
        return String(decrypted, Charsets.UTF_8)
    }

    /**
     * Clears all local vault credentials and resets security preferences.
     */
    @Synchronized
    fun wipeVault(context: Context) {
        lock()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        Log.i(TAG, "Vault security preferences completely wiped.")
    }

    // --- Private Cryptographic Primitives ---

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun encryptWithKey(key: SecretKey, plaintext: ByteArray, aad: String): String {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).apply { secureRandom.nextBytes(this) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))

        val ciphertext = cipher.doFinal(plaintext)
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)

        return "v1:$ivB64:$ctB64"
    }

    private fun decryptWithKey(key: SecretKey, blob: String, aad: String): ByteArray {
        val parts = blob.split(":")
        if (parts.size != 3 || parts[0] != "v1") {
            throw IllegalArgumentException("Unsupported or invalid ciphertext envelope: $blob")
        }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ct = Base64.decode(parts[2], Base64.NO_WRAP)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))

        return cipher.doFinal(ct)
    }
}
