/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tokeng.gms.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.tokeng.gms.crypto.TokenCryptoManager
import org.tokeng.gms.database.TokenAccount
import org.tokeng.gms.database.TokenDatabase
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
    private const val KEY_AUTH_TOKEN = "backend_auth_token"
    private const val KEY_USER_EMAIL = "backend_user_email"
    private const val KEY_USER_ID = "backend_user_id"
    private const val KEY_LAST_SERVER_TIME = "last_server_time"

    const val DEFAULT_BACKEND_URL = "https://tokeng.sanshiro.qzz.io"

    fun getLastError(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_ERROR, null)
    }

    fun setLastError(context: Context, error: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_ERROR, error).apply()
    }

    fun getBackendUrl(context: Context): String {
        return DEFAULT_BACKEND_URL
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

    fun getAuthToken(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AUTH_TOKEN, null)
    }

    fun setAuthToken(context: Context, token: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_AUTH_TOKEN, token).apply()
    }

    fun getUserEmail(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_USER_EMAIL, null)
    }

    fun setUserEmail(context: Context, email: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_USER_EMAIL, email).apply()
    }

    fun getUserId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_USER_ID, null)
    }

    fun setUserId(context: Context, userId: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_USER_ID, userId).apply()
    }

    fun getLastServerTime(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_SERVER_TIME, "1970-01-01T00:00:00Z") ?: "1970-01-01T00:00:00Z"
    }

    fun setLastServerTime(context: Context, time: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_SERVER_TIME, time).apply()
    }

    fun isLoggedIn(context: Context): Boolean {
        return !getAuthToken(context).isNullOrEmpty()
    }

    fun logout(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_ID)
            .remove(KEY_LAST_SERVER_TIME)
            .apply()
    }

    private const val KEY_LOCAL_MODE = "is_local_mode_active"

    enum class Reachability {
        ONLINE_SERVER_UP,
        ONLINE_SERVER_DOWN,
        OFFLINE
    }

    fun isLocalMode(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_LOCAL_MODE, false)
    }

    fun setLocalMode(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_LOCAL_MODE, enabled).apply()
    }

    fun isUnlocked(context: Context): Boolean {
        return (isLoggedIn(context) || isLocalMode(context)) && TokenCryptoManager.isUnlocked()
    }

    fun checkConnectivity(context: Context, callback: (Reachability) -> Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val activeNet = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNet)
        val hasNet = caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)

        if (!hasNet) {
            callback(Reachability.OFFLINE)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            var conn: HttpURLConnection? = null
            var state = Reachability.ONLINE_SERVER_DOWN
            try {
                conn = (URL("${DEFAULT_BACKEND_URL}/health").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3000
                    readTimeout = 3000
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    if (json.optString("status") == "ok" && json.optString("db") == "up") {
                        state = Reachability.ONLINE_SERVER_UP
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Health check failed against $DEFAULT_BACKEND_URL: ${e.message}")
            } finally {
                conn?.disconnect()
            }
            withContext(Dispatchers.Main) {
                callback(state)
            }
        }
    }

    fun signOut(context: Context) {
        logout(context)
        setLocalMode(context, false)
        TokenDatabase.getInstance(context).deleteAllAccounts()
        TokenCryptoManager.wipeVault(context)
        Log.i(TAG, "Signed out. Local vault and session credentials completely wiped.")
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
     * Test backend server reachability via GET /health.
     */
    fun testConnection(context: Context, callback: (Boolean, String?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            var conn: HttpURLConnection? = null
            try {
                val targetUrl = "${getBackendUrl(context)}/health"
                conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                val code = conn.responseCode
                withContext(Dispatchers.Main) {
                    if (code in 200..399) {
                        setLastError(context, null)
                        callback(true, "HTTP $code - TokenG Server reachable")
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

    /**
     * Register account on Go backend.
     */
    fun register(
        context: Context,
        email: String,
        pass: String,
        callback: (Boolean, String?) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            var conn: HttpURLConnection? = null
            try {
                val targetUrl = "${getBackendUrl(context)}/api/auth/register"
                val payload = JSONObject().apply {
                    put("email", email.trim())
                    put("password", pass)
                }

                conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doInput = true
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }

                OutputStreamWriter(conn.outputStream, "UTF-8").use {
                    it.write(payload.toString())
                    it.flush()
                }

                val code = conn.responseCode
                if (code in 200..299) {
                    val respStr = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    val json = JSONObject(respStr)
                    val token = json.optString("token")
                    val userId = json.optString("user_id")
                    setAuthToken(context, token)
                    setUserEmail(context, email)
                    setUserId(context, userId)
                    setLastError(context, null)
                    withContext(Dispatchers.Main) {
                        callback(true, null)
                    }
                } else {
                    val errStr = conn.errorStream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: "HTTP $code"
                    setLastError(context, "Registration failed: $errStr")
                    withContext(Dispatchers.Main) {
                        callback(false, errStr)
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Network error during registration"
                setLastError(context, msg)
                withContext(Dispatchers.Main) {
                    callback(false, msg)
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    /**
     * Login to Go backend.
     */
    fun login(
        context: Context,
        email: String,
        pass: String,
        callback: (Boolean, String?) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            var conn: HttpURLConnection? = null
            try {
                val targetUrl = "${getBackendUrl(context)}/api/auth/login"
                val payload = JSONObject().apply {
                    put("email", email.trim())
                    put("password", pass)
                }

                conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doInput = true
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }

                OutputStreamWriter(conn.outputStream, "UTF-8").use {
                    it.write(payload.toString())
                    it.flush()
                }

                val code = conn.responseCode
                if (code in 200..299) {
                    val respStr = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    val json = JSONObject(respStr)
                    val token = json.optString("token")
                    val userId = json.optString("user_id")
                    setAuthToken(context, token)
                    setUserEmail(context, email)
                    setUserId(context, userId)
                    setLastError(context, null)
                    withContext(Dispatchers.Main) {
                        callback(true, null)
                    }
                } else {
                    val errStr = conn.errorStream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: "HTTP $code"
                    setLastError(context, "Login failed: $errStr")
                    withContext(Dispatchers.Main) {
                        callback(false, errStr)
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Network error during login"
                setLastError(context, msg)
                withContext(Dispatchers.Main) {
                    callback(false, msg)
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    /**
     * Delta push a single TokenAccount to the Go server.
     */
    fun syncAccount(
        context: Context,
        account: TokenAccount,
        callback: ((Boolean, String?) -> Unit)? = null
    ) {
        syncAccountsBatch(context, listOf(account)) { success, err ->
            callback?.invoke(success, err)
        }
    }

    /**
     * Delta push a batch of TokenAccounts to POST /api/sync/push.
     */
    fun syncAccountsBatch(
        context: Context,
        accounts: List<TokenAccount>,
        callback: ((Boolean, String?) -> Unit)? = null
    ) {
        if (accounts.isEmpty()) {
            callback?.invoke(true, null)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val db = TokenDatabase.getInstance(context)
            var conn: HttpURLConnection? = null
            try {
                val instancesArr = JSONArray()
                for (account in accounts) {
                    val obj = JSONObject().apply {
                        put("instance_id", account.instanceId)
                        put("email", account.email)
                        put("master_token", account.masterToken)
                        put("aas_token", account.aasToken ?: "")
                        put("sid", account.sid ?: "")
                        put("lsid", account.lsid ?: "")
                        put("android_id", account.androidId)
                        put("gsf_id", account.androidId)
                        put("security_token", account.securityToken)
                        put("device_name", account.deviceName)
                        put("device_model", account.deviceModel)
                        put("device_brand", account.deviceBrand)
                        put("device_fingerprint", account.deviceFingerprint)
                        put("device_sdk", account.deviceSdk)
                        put("account_status", account.accountStatus)
                        put("signed_out_reason", account.signedOutReason ?: "")
                        put("deleted", false)
                    }
                    instancesArr.put(obj)
                }

                val payload = JSONObject().apply {
                    put("instances", instancesArr)
                }

                val targetUrl = "${getBackendUrl(context)}/api/sync/push"
                Log.d(TAG, "Pushing ${accounts.size} instance(s) to $targetUrl...")

                conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 10000
                    doInput = true
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    val token = getAuthToken(context)
                    if (!token.isNullOrEmpty()) {
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                    val apiKey = getApiKey(context)
                    if (apiKey.isNotEmpty()) {
                        setRequestProperty("X-API-Key", apiKey)
                    }
                }

                OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val code = conn.responseCode
                if (code in 200..299) {
                    val respText = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    Log.d(TAG, "Instances pushed successfully: $respText")
                    val respJson = JSONObject(respText)
                    val serverTime = respJson.optString("server_time")
                    if (serverTime.isNotEmpty()) {
                        setLastServerTime(context, serverTime)
                    }

                    for (acct in accounts) {
                        db.updateSyncStatus(acct.email, "SYNCED", System.currentTimeMillis())
                    }
                    setLastError(context, null)
                    withContext(Dispatchers.Main) {
                        callback?.invoke(true, null)
                    }
                } else {
                    val errText = conn.errorStream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: "HTTP $code"
                    Log.w(TAG, "Sync push failed: $errText")
                    setLastError(context, "HTTP $code: $errText")
                    for (acct in accounts) {
                        db.updateSyncStatus(acct.email, "FAILED", System.currentTimeMillis())
                    }
                    withContext(Dispatchers.Main) {
                        callback?.invoke(false, errText)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during push", e)
                val errMsg = e.message ?: "Connection error / server unreachable"
                setLastError(context, errMsg)
                for (acct in accounts) {
                    try {
                        db.updateSyncStatus(acct.email, "FAILED", System.currentTimeMillis())
                    } catch (_: Exception) {}
                }
                withContext(Dispatchers.Main) {
                    callback?.invoke(false, errMsg)
                }
            } finally {
                conn?.disconnect()
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
                    val aasToken = am.getUserData(account, org.tokeng.gms.profile.MultiDeviceRegistry.KEY_AAS_TOKEN) ?: ""
                    val androidId = am.getUserData(account, org.tokeng.gms.profile.MultiDeviceRegistry.KEY_ANDROID_ID) ?: ""
                    val securityToken = am.getUserData(account, org.tokeng.gms.profile.MultiDeviceRegistry.KEY_SECURITY_TOKEN) ?: ""
                    val deviceName = am.getUserData(account, org.tokeng.gms.profile.MultiDeviceRegistry.KEY_DEVICE_NAME) ?: "MicroG Virtual Device"
                    val deviceFingerprint = am.getUserData(account, org.tokeng.gms.profile.MultiDeviceRegistry.KEY_DEVICE_FINGERPRINT) ?: ""
                    val sdkVersionStr = am.getUserData(account, org.tokeng.gms.profile.MultiDeviceRegistry.KEY_DEVICE_SDK) ?: "34"

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
     * Sync all accounts stored in TokenDatabase to Go backend.
     */
    fun syncAllAccounts(
        context: Context,
        callback: ((Int, Int) -> Unit)? = null
    ) {
        val accounts = TokenDatabase.getInstance(context).getAllAccounts()
        if (accounts.isEmpty()) {
            callback?.invoke(0, 0)
            return
        }

        syncAccountsBatch(context, accounts) { success, _ ->
            if (success) {
                callback?.invoke(accounts.size, 0)
            } else {
                callback?.invoke(0, accounts.size)
            }
        }
    }

    /**
     * Delta pull accounts from Go server (GET /api/sync/pull?since=...).
     */
    fun pullDelta(
        context: Context,
        callback: (Boolean, Int, String?) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            var conn: HttpURLConnection? = null
            try {
                val since = getLastServerTime(context)
                val targetUrl = "${getBackendUrl(context)}/api/sync/pull?since=$since"
                conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    val token = getAuthToken(context)
                    if (!token.isNullOrEmpty()) {
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                }

                val code = conn.responseCode
                if (code in 200..299) {
                    val respText = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    val json = JSONObject(respText)
                    val serverTime = json.optString("server_time")
                    if (serverTime.isNotEmpty()) {
                        setLastServerTime(context, serverTime)
                    }

                    val instancesArr = json.optJSONArray("instances") ?: JSONArray()
                    val db = TokenDatabase.getInstance(context)
                    var appliedCount = 0

                    for (i in 0 until instancesArr.length()) {
                        val inst = instancesArr.getJSONObject(i)
                        val instanceId = inst.optString("instance_id")
                        val email = inst.optString("email")
                        val masterToken = inst.optString("master_token")
                        val aasToken = inst.optString("aas_token")
                        val sid = inst.optString("sid")
                        val lsid = inst.optString("lsid")
                        val androidId = inst.optString("android_id")
                        val securityToken = inst.optString("security_token")
                        val deviceName = inst.optString("device_name", "Unknown Device")
                        val deviceModel = inst.optString("device_model", "Unknown Model")
                        val deviceBrand = inst.optString("device_brand", "Unknown Brand")
                        val deviceFingerprint = inst.optString("device_fingerprint", "")
                        val deviceSdk = inst.optInt("device_sdk", 34)
                        val accountStatus = inst.optString("account_status", "ACTIVE")
                        val signedOutReason = inst.optString("signed_out_reason")
                        val deleted = inst.optBoolean("deleted", false)

                        if (deleted) {
                            db.deleteAccountByInstanceId(instanceId)
                        } else {
                            val account = TokenAccount(
                                email = email,
                                masterToken = masterToken,
                                aasToken = if (aasToken.isNotEmpty()) aasToken else null,
                                sid = if (sid.isNotEmpty()) sid else null,
                                lsid = if (lsid.isNotEmpty()) lsid else null,
                                androidId = androidId,
                                securityToken = securityToken,
                                deviceName = deviceName,
                                deviceModel = deviceModel,
                                deviceBrand = deviceBrand,
                                deviceFingerprint = deviceFingerprint,
                                deviceSdk = deviceSdk,
                                syncStatus = "SYNCED",
                                accountStatus = accountStatus,
                                signedOutReason = if (signedOutReason.isNotEmpty()) signedOutReason else null,
                                instanceId = instanceId
                            )
                            db.insertOrUpdate(account)
                        }
                        appliedCount++
                    }

                    setLastError(context, null)
                    withContext(Dispatchers.Main) {
                        callback(true, appliedCount, null)
                    }
                } else {
                    val errText = conn.errorStream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: "HTTP $code"
                    setLastError(context, "Pull failed: $errText")
                    withContext(Dispatchers.Main) {
                        callback(false, 0, errText)
                    }
                }
            } catch (e: Exception) {
                val errMsg = e.message ?: "Connection error during delta pull"
                setLastError(context, errMsg)
                withContext(Dispatchers.Main) {
                    callback(false, 0, errMsg)
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    /**
     * Retrieve global stats from Go backend (GET /api/stats).
     */
    fun getGlobalStats(
        context: Context,
        callback: (Boolean, JSONObject?, String?) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            var conn: HttpURLConnection? = null
            try {
                val targetUrl = "${getBackendUrl(context)}/api/stats"
                conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                val code = conn.responseCode
                if (code in 200..299) {
                    val respText = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    val json = JSONObject(respText)
                    withContext(Dispatchers.Main) {
                        callback(true, json, null)
                    }
                } else {
                    val errText = conn.errorStream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: "HTTP $code"
                    withContext(Dispatchers.Main) {
                        callback(false, null, errText)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(false, null, e.message)
                }
            } finally {
                conn?.disconnect()
            }
        }
    }
}
