/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.microg.gms.database.TokenAccount
import org.microg.gms.database.TokenDatabase
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object BackendSyncManager {
    private const val TAG = "BackendSyncManager"
    private const val PREFS_NAME = "tokeng_sync_preferences"
    private const val KEY_BACKEND_URL = "backend_url"
    private const val KEY_API_KEY = "backend_api_key"
    private const val KEY_AUTO_SYNC = "auto_sync_enabled"
    private const val KEY_LAST_ERROR = "last_sync_error"

    const val DEFAULT_BACKEND_URL = "http://192.168.1.4:3000"

    fun getLastError(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_ERROR, null)
    }

    fun setLastError(context: Context, error: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_ERROR, error).apply()
    }

    fun testConnection(context: Context, callback: (Boolean, String?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            var conn: HttpURLConnection? = null
            try {
                val targetUrl = "${getBackendUrl(context)}/api/health"
                conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                val code = conn.responseCode
                withContext(Dispatchers.Main) {
                    if (code in 200..399) {
                        setLastError(context, null)
                        callback(true, "HTTP $code - Backend reachable")
                    } else {
                        val msg = "Server responded with HTTP $code"
                        setLastError(context, msg)
                        callback(false, msg)
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Connection refused / Host unreachable"
                setLastError(context, msg)
                withContext(Dispatchers.Main) {
                    callback(false, msg)
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    fun getBackendUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL
    }

    fun setBackendUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BACKEND_URL, url.trim().trimEnd('/')).apply()
    }

    fun getApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(context: Context, apiKey: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    fun isAutoSyncEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_SYNC, true)
    }

    fun setAutoSyncEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
    }

    /**
     * Asynchronously sync a single TokenAccount to the remote backend database.
     */
    fun syncAccount(
        context: Context,
        account: TokenAccount,
        callback: ((Boolean, String?) -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = TokenDatabase.getInstance(context)
                val payload = JSONObject().apply {
                    put("email", account.email)
                    put("masterToken", account.masterToken)
                    put("aasToken", account.aasToken ?: "")
                    put("sid", account.sid ?: "")
                    put("lsid", account.lsid ?: "")
                    put("androidId", account.androidId)
                    put("securityToken", account.securityToken)
                    put("registeredAt", account.registeredAt)
                    put("accountStatus", account.accountStatus)
                    put("lastValidatedAt", account.lastValidatedAt)
                    put("signedOutReason", account.signedOutReason ?: "")

                    val deviceObj = JSONObject().apply {
                        put("name", account.deviceName)
                        put("model", account.deviceModel)
                        put("brand", account.deviceBrand)
                        put("fingerprint", account.deviceFingerprint)
                        put("sdkVersion", account.deviceSdk)
                    }
                    put("device", deviceObj)
                }

                val targetUrl = "${getBackendUrl(context)}/api/sync-account"
                Log.d(TAG, "Syncing account ${account.email} to $targetUrl...")

                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 10000
                        readTimeout = 10000
                        doInput = true
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        val apiKey = getApiKey(context)
                        if (apiKey.isNotEmpty()) {
                            setRequestProperty("X-API-Key", apiKey)
                        }
                    }

                    OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                        writer.write(payload.toString())
                        writer.flush()
                    }

                    val responseCode = conn.responseCode
                    if (responseCode in 200..299) {
                        val respText = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                        Log.d(TAG, "Account ${account.email} synced successfully: $respText")
                        db.updateSyncStatus(account.email, "SYNCED", System.currentTimeMillis())
                        withContext(Dispatchers.Main) {
                            callback?.invoke(true, null)
                        }
                    } else {
                        val errText = conn.errorStream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: "HTTP $responseCode"
                        Log.w(TAG, "Sync failed for ${account.email}: $errText")
                        setLastError(context, "HTTP $responseCode: $errText")
                        db.updateSyncStatus(account.email, "FAILED", System.currentTimeMillis())
                        withContext(Dispatchers.Main) {
                            callback?.invoke(false, errText)
                        }
                    }
                } finally {
                    conn?.disconnect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during sync for ${account.email}", e)
                val errMsg = e.message ?: "Connection error / server unreachable"
                setLastError(context, errMsg)
                try {
                    TokenDatabase.getInstance(context).updateSyncStatus(account.email, "FAILED", System.currentTimeMillis())
                } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    callback?.invoke(false, errMsg)
                }
            }
        }
    }

    /**
     * Backward-compatible overload for Account.
     */
    fun syncAccount(
        context: Context,
        account: Account,
        callback: ((Boolean, String?) -> Unit)? = null
    ) {
        val dbAccount = TokenDatabase.getInstance(context).getAccount(account.name)
        if (dbAccount != null) {
            syncAccount(context, dbAccount, callback)
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val am = AccountManager.get(context)
                    val masterToken = am.getPassword(account) ?: ""
                    val aasToken = am.getUserData(account, org.microg.gms.profile.MultiDeviceRegistry.KEY_AAS_TOKEN) ?: ""
                    val androidId = am.getUserData(account, org.microg.gms.profile.MultiDeviceRegistry.KEY_ANDROID_ID) ?: ""
                    val securityToken = am.getUserData(account, org.microg.gms.profile.MultiDeviceRegistry.KEY_SECURITY_TOKEN) ?: ""
                    val deviceName = am.getUserData(account, org.microg.gms.profile.MultiDeviceRegistry.KEY_DEVICE_NAME) ?: "MicroG Virtual Device"
                    val deviceFingerprint = am.getUserData(account, org.microg.gms.profile.MultiDeviceRegistry.KEY_DEVICE_FINGERPRINT) ?: ""
                    val sdkVersionStr = am.getUserData(account, org.microg.gms.profile.MultiDeviceRegistry.KEY_DEVICE_SDK) ?: "34"

                    val fallbackTokenAcct = TokenAccount(
                        email = account.name,
                        masterToken = masterToken,
                        aasToken = aasToken,
                        androidId = androidId,
                        securityToken = securityToken,
                        deviceName = deviceName,
                        deviceModel = deviceName,
                        deviceBrand = "google",
                        deviceFingerprint = deviceFingerprint,
                        deviceSdk = sdkVersionStr.toIntOrNull() ?: 34
                    )
                    syncAccount(context, fallbackTokenAcct, callback)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        callback?.invoke(false, e.message)
                    }
                }
            }
        }
    }

    /**
     * Sync all accounts stored in TokenDatabase.
     */
    fun syncAllAccounts(
        context: Context,
        callback: ((Int, Int) -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val accounts = TokenDatabase.getInstance(context).getAllAccounts()
            var successCount = 0
            var failCount = 0

            if (accounts.isEmpty()) {
                withContext(Dispatchers.Main) {
                    callback?.invoke(0, 0)
                }
                return@launch
            }

            for (account in accounts) {
                syncAccount(context, account) { success, _ ->
                    if (success) successCount++ else failCount++
                    if (successCount + failCount == accounts.size) {
                        callback?.invoke(successCount, failCount)
                    }
                }
            }
        }
    }
}
