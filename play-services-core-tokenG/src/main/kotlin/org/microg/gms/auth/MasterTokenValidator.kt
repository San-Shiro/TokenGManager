/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.microg.gms.database.TokenAccount
import org.microg.gms.profile.MultiDeviceRegistry
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()
    data class Transient(val reason: String) : ValidationResult()
}

object MasterTokenValidator {
    private const val TAG = "MasterTokenValidator"

    // Definitive auth rejection triggers marking account as SIGNED_OUT
    private val REVOKED_ERRORS = listOf(
        "BadAuthentication",
        "InvalidCredentials",
        "NotVerified",
        "AccountDisabled",
        "Deleted"
    )

    // Browser or re-authentication required triggers
    private val REAUTH_ERRORS = listOf(
        "WebLoginRequired",
        "NeedsBrowser",
        "DeviceManagementRequiredOrSyncDisabled"
    )

    fun hasNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun validate(context: Context, account: TokenAccount): ValidationResult {
        if (!hasNetwork(context)) {
            return ValidationResult.Transient("No network connection")
        }

        if (account.masterToken.isEmpty()) {
            return ValidationResult.Invalid("Empty master token")
        }

        return try {
            val preset = MultiDeviceRegistry.getPresetByModel(account.deviceModel)
            val request = AuthRequest()
                .fromContext(context, preset)
                .email(account.email)
                .token(account.masterToken)
                .service("ac2dm")
                .appIsGms()
                .callerIsGms()
                .systemPartition(true)
                .hasPermission(true)

            if (account.androidId.isNotEmpty() && account.androidId != "0") {
                request.androidIdHex = account.androidId
            }

            Log.d(TAG, "Validating token for ${account.email} against Google auth...")
            val response = request.response

            classifyResponse(response)
        } catch (e: UnknownHostException) {
            ValidationResult.Transient("DNS lookup failed: ${e.message}")
        } catch (e: SocketTimeoutException) {
            ValidationResult.Transient("Connection timed out")
        } catch (e: ConnectException) {
            ValidationResult.Transient("Connection refused: ${e.message}")
        } catch (e: SSLException) {
            ValidationResult.Transient("SSL handshake failed: ${e.message}")
        } catch (e: SocketException) {
            ValidationResult.Transient("Socket error: ${e.message}")
        } catch (e: IOException) {
            classifyErrorMessage(e.message ?: "")
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error during validation for ${account.email}", e)
            ValidationResult.Transient("Unexpected exception: ${e.javaClass.simpleName}")
        }
    }

    private fun classifyResponse(response: AuthResponse?): ValidationResult {
        if (response == null) {
            return ValidationResult.Transient("Null response from Google auth server")
        }

        // If auth or token is non-empty, Google successfully validated the master token
        if (!response.auth.isNullOrEmpty() || !response.token.isNullOrEmpty()) {
            return ValidationResult.Valid
        }

        // Check if error is present in response fields
        val errorText = buildString {
            if (!response.issueAdvice.isNullOrEmpty()) append(response.issueAdvice).append(" ")
            if (!response.ropText.isNullOrEmpty()) append(response.ropText).append(" ")
        }.trim()

        if (errorText.isNotEmpty()) {
            return classifyErrorMessage(errorText)
        }

        // Empty response with no valid token or error
        return ValidationResult.Transient("Empty token response without explicit error")
    }

    private fun classifyErrorMessage(rawMessage: String): ValidationResult {
        for (err in REAUTH_ERRORS) {
            if (rawMessage.contains(err, ignoreCase = true)) {
                Log.w(TAG, "Token validation returned re-auth error: $err")
                return ValidationResult.Invalid(err)
            }
        }

        for (err in REVOKED_ERRORS) {
            if (rawMessage.contains(err, ignoreCase = true)) {
                Log.w(TAG, "Token validation returned revoked error: $err")
                return ValidationResult.Invalid(err)
            }
        }

        Log.w(TAG, "Unclassified auth response message (treating as transient): $rawMessage")
        return ValidationResult.Transient(rawMessage.take(100))
    }
}
