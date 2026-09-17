/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.sync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.microg.gms.auth.MasterTokenValidator
import org.microg.gms.auth.ValidationResult
import org.microg.gms.database.TokenAccount
import org.microg.gms.database.TokenDatabase

object TokenValidationRunner {
    private const val TAG = "TokenValidationRunner"
    const val STRIKE_THRESHOLD = 2
    private val runMutex = Mutex()

    /**
     * Validate a single account.
     * @param force If true, marks signed out on the first auth rejection (used for manual checks).
     */
    suspend fun validateAccount(
        context: Context,
        account: TokenAccount,
        force: Boolean = false
    ): ValidationResult = withContext(Dispatchers.IO) {
        val db = TokenDatabase.getInstance(context)
        val now = System.currentTimeMillis()
        val result = MasterTokenValidator.validate(context, account)

        when (result) {
            is ValidationResult.Valid -> {
                Log.i(TAG, "Account ${account.email} token is VALID")
                db.markValidated(account.email, account.masterToken, now, "VALID")
            }
            is ValidationResult.Transient -> {
                Log.d(TAG, "Account ${account.email} token check is TRANSIENT: ${result.reason}")
                db.markTransient(account.email, now, "TRANSIENT")
            }
            is ValidationResult.Invalid -> {
                Log.w(TAG, "Account ${account.email} token check is INVALID: ${result.reason}")
                if (force) {
                    db.markSignedOut(account.email, account.masterToken, result.reason, now)
                    val updated = db.getAccount(account.email)
                    if (updated != null && BackendSyncManager.isAutoSyncEnabled(context)) {
                        BackendSyncManager.syncAccount(context, updated, null)
                    }
                } else {
                    val strikes = db.incrementAuthFailure(account.email, account.masterToken)
                    Log.w(TAG, "Account ${account.email} auth failure strikes: $strikes/$STRIKE_THRESHOLD")
                    if (strikes >= STRIKE_THRESHOLD) {
                        db.markSignedOut(account.email, account.masterToken, result.reason, now)
                        val updated = db.getAccount(account.email)
                        if (updated != null && BackendSyncManager.isAutoSyncEnabled(context)) {
                            BackendSyncManager.syncAccount(context, updated, null)
                        }
                    } else {
                        db.markTransient(account.email, now, "INVALID_STRIKE_$strikes")
                    }
                }
            }
        }
        result
    }

    /**
     * Validates all accounts sequentially with small delays to avoid 429 throttling.
     */
    suspend fun validateAll(
        context: Context,
        force: Boolean = false
    ): Map<String, ValidationResult> = withContext(Dispatchers.IO) {
        runMutex.withLock {
            val db = TokenDatabase.getInstance(context)
            val accounts = db.getAllAccounts()
            val results = mutableMapOf<String, ValidationResult>()

            for (account in accounts) {
                // If already signed out and not forcing, skip
                if (account.isSignedOut && !force) {
                    continue
                }

                val res = validateAccount(context, account, force)
                results[account.email] = res
                delay(400) // gentle delay between network requests
            }
            results
        }
    }

    /**
     * Throttled check: runs in background if any account is older than minAgeMs.
     */
    fun maybeValidateAll(context: Context, minAgeMs: Long = 6 * 3600 * 1000L) {
        CoroutineScope(Dispatchers.IO).launch {
            if (!MasterTokenValidator.hasNetwork(context)) return@launch
            val db = TokenDatabase.getInstance(context)
            val accounts = db.getAllAccounts()
            val now = System.currentTimeMillis()

            val needsCheck = accounts.any { acct ->
                !acct.isSignedOut && (now - acct.lastValidatedAt > minAgeMs)
            }

            if (needsCheck) {
                Log.d(TAG, "Triggering throttled background validation for stale accounts")
                validateAll(context, force = false)
            }
        }
    }
}
