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
    private const val KEY_LAST_SYNCED_USER = "last_synced_user"

    @Volatile
    private var lastAutoSyncTimestamp: Long = 0L
    private const val AUTO_SYNC_DEBOUNCE_MS = 60_000L

    const val DEFAULT_BACKEND_URL = "https://tokeng.sanshiro.qzz.io"

    fun getLastSyncedUser(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_SYNCED_USER, null)
    }

    fun setLastSyncedUser(context: Context, email: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_SYNCED_USER, email).apply()
    }

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

    fun openBackendConnection(
        urlStr: String,
        method: String = "GET",
        connectTimeoutMs: Int = 10000,
        readTimeoutMs: Int = 15000
    ): HttpURLConnection {
        return (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", "TokenG-Android/${org.tokeng.gms.BuildConfig.VERSION_NAME} (Linux; Android)")
            setRequestProperty("Accept", "application/json")
        }
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
        val token = getAuthToken(context)
        val email = getUserEmail(context)
        return !token.isNullOrEmpty() && !email.isNullOrEmpty()
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
        return isLoggedIn(context) || isLocalMode(context)
    }

    fun checkConnectivity(context: Context, callback: (Reachability) -> Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val activeNet = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNet)
        val hasNet = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: true

        CoroutineScope(Dispatchers.IO).launch {
            var conn: HttpURLConnection? = null
            var state = if (hasNet) Reachability.ONLINE_SERVER_DOWN else Reachability.OFFLINE
            try {
                conn = openBackendConnection("${getBackendUrl(context)}/health", "GET", 8000, 8000)
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    if (json.optString("status") == "ok" && json.optString("db") == "up") {
                        state = Reachability.ONLINE_SERVER_UP
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Health check failed against ${getBackendUrl(context)}: ${e.message}")
                if (!hasNet) {
                    state = Reachability.OFFLINE
                }
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

    @JvmStatic
    fun sanitizeErrorMessage(rawError: String?, isRegister: Boolean = false): String {
        if (rawError.isNullOrBlank()) {
            return if (isRegister) {
                "Unable to create account. Please check your information and try again."
            } else {
                "Sign in failed. Please check your email and password."
            }
        }
        val lower = rawError.lowercase()

        // Strip any technical URL, domain, or IP addresses
        if (lower.contains("tokeng.sanshiro.qzz.io") || lower.contains("http://") || lower.contains("https://")) {
            if (lower.contains("refused") || lower.contains("unreachable") || lower.contains("failed to connect") || lower.contains("timeout")) {
                return "Unable to connect to the server. Please check your internet connection."
            }
        }

        // Connection & Network errors
        if (lower.contains("connectexception") || lower.contains("connection refused") || 
            lower.contains("failed to connect") || lower.contains("network is unreachable") ||
            lower.contains("unknownhostexception") || lower.contains("no address associated") ||
            lower.contains("host unreachable") || lower.contains("sockettimeoutexception") ||
            lower.contains("timed out") || lower.contains("timeout") || lower.contains("sslhandshakeexception") ||
            lower.contains("route to host") || lower.contains("connection reset") || lower.contains("stream closed") ||
            lower.contains("broken pipe")) {
            return "Unable to connect to the server. Please check your internet connection."
        }

        // HTTP 401 Unauthorized / Invalid Credentials
        if (lower.contains("401") || lower.contains("unauthorized") || lower.contains("invalid credential") || 
            lower.contains("wrong password") || lower.contains("bad credentials") || lower.contains("invalid email or password") ||
            lower.contains("incorrect email or password") || lower.contains("invalid_credentials")) {
            return "Incorrect email or password. Please try again."
        }

        // HTTP 409 Conflict / Already Registered
        if (lower.contains("409") || lower.contains("conflict") || lower.contains("already exists") || 
            lower.contains("duplicate") || lower.contains("user already registered") || lower.contains("user_exists")) {
            return if (isRegister) {
                "An account with this email already exists. Please sign in instead."
            } else {
                "An account conflict occurred. Please check your details."
            }
        }

        // HTTP 400 Bad Request / Validation
        if (lower.contains("400") || lower.contains("bad request") || lower.contains("validation") ||
            lower.contains("invalid input") || lower.contains("malformed")) {
            return "Invalid input. Please ensure your email and password meet requirements."
        }

        // HTTP 403 Forbidden
        if (lower.contains("403") || lower.contains("forbidden") || lower.contains("access denied")) {
            return "Access denied. Please check your account permissions."
        }

        // HTTP 404 Not Found
        if (lower.contains("404") || lower.contains("not found")) {
            return "Service is temporarily unavailable. Please try again later."
        }

        // HTTP 429 Too Many Requests
        if (lower.contains("429") || lower.contains("rate limit") || lower.contains("too many requests")) {
            return "Too many attempts. Please wait a few moments before trying again."
        }

        // HTTP 500 / 502 / 503 / 504 / 530 / 1033 / Database / Server Errors
        if (lower.contains("500") || lower.contains("502") || lower.contains("503") || lower.contains("504") ||
            lower.contains("530") || lower.contains("1033") || lower.contains("degraded") ||
            lower.contains("server error") || lower.contains("internal") || lower.contains("database") ||
            lower.contains("postgres") || lower.contains("pq:") || lower.contains("sql") || lower.contains("bad gateway")) {
            return "Server is temporarily offline (database unreachable). Please use Local Mode to access your tokens."
        }

        // Clean any backend JSON error payload: e.g. {"error":"..."} or {"error":{"code":"...","message":"..."}}
        if (rawError.trim().startsWith("{")) {
            try {
                val json = JSONObject(rawError)
                val errorVal = json.opt("error")
                val msg = when (errorVal) {
                    is JSONObject -> errorVal.optString("message").takeIf { it.isNotBlank() } ?: errorVal.optString("code")
                    is String -> errorVal
                    else -> json.optString("message").takeIf { it.isNotBlank() } ?: json.optString("error")
                }
                if (msg.isNotBlank()) {
                    return sanitizeErrorMessage(msg, isRegister)
                }
            } catch (_: Exception) {}
        }

        if (rawError.matches(Regex("^HTTP \\d{3}.*"))) {
            return "Server returned an unexpected response. Please try again later."
        }

        return if (isRegister) {
            "Unable to complete registration. Please verify your details and try again."
        } else {
            "Unable to sign in. Please verify your credentials and try again."
        }
    }

    @JvmStatic
    @JvmOverloads
    fun triggerAutoSync(
        context: Context,
        force: Boolean = false,
        callback: ((Boolean) -> Unit)? = null
    ) {
        if (isLocalMode(context)) {
            // Local mode does not sync with cloud; complete gracefully
            callback?.invoke(true)
            return
        }

        if (!isLoggedIn(context)) {
            callback?.invoke(false)
            return
        }

        val now = System.currentTimeMillis()
        if (!force && (now - lastAutoSyncTimestamp < AUTO_SYNC_DEBOUNCE_MS)) {
            Log.d(TAG, "triggerAutoSync: debounced (last sync was ${now - lastAutoSyncTimestamp}ms ago)")
            callback?.invoke(true)
            return
        }

        lastAutoSyncTimestamp = now

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Push any unsynced local accounts first so new accounts are uploaded to cloud
                val db = TokenDatabase.getInstance(context)
                val unsynced = db.getUnsyncedAccounts().filter { it.syncStatus != "LOCAL_ONLY" }
                val performPush = { onPushComplete: (Boolean) -> Unit ->
                    if (unsynced.isNotEmpty()) {
                        syncAccountsBatch(context, unsynced) { pushSuccess, _ ->
                            onPushComplete(pushSuccess)
                        }
                    } else {
                        onPushComplete(true)
                    }
                }

                performPush { pushSuccess ->
                    // 2. Pull delta from cloud
                    pullDelta(context) { pullSuccess, _, _ ->
                        callback?.invoke(pushSuccess && pullSuccess)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "triggerAutoSync exception", e)
                withContext(Dispatchers.Main) {
                    callback?.invoke(false)
                }
            }
        }
    }

    /**
     * Test backend server reachability via GET /health.
     */
    fun testConnection(context: Context, callback: (Boolean, String?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            var conn: HttpURLConnection? = null
            try {
                val targetUrl = "${getBackendUrl(context)}/health"
                conn = openBackendConnection(targetUrl, "GET", 8000, 8000)
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

                conn = openBackendConnection(targetUrl, "POST", 10000, 10000).apply {
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

                    val prevUser = getLastSyncedUser(context)
                    if (prevUser == null || !prevUser.equals(email.trim(), ignoreCase = true)) {
                        val db = TokenDatabase.getInstance(context)
                        db.deleteSyncedAccounts()
                        db.markRemainingAsLocalOnly()
                        setLastServerTime(context, "1970-01-01T00:00:00Z")
                    }
                    setLastSyncedUser(context, email.trim())
                    lastAutoSyncTimestamp = 0L

                    setAuthToken(context, token)
                    setUserEmail(context, email.trim())
                    setUserId(context, userId)
                    setLastError(context, null)
                    withContext(Dispatchers.Main) {
                        callback(true, null)
                    }
                } else {
                    val errStr = conn.errorStream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: "HTTP $code"
                    val userFriendly = sanitizeErrorMessage(errStr, isRegister = true)
                    setLastError(context, userFriendly)
                    withContext(Dispatchers.Main) {
                        callback(false, userFriendly)
                    }
                }
            } catch (e: Exception) {
                val userFriendly = sanitizeErrorMessage(e.message, isRegister = true)
                setLastError(context, userFriendly)
                withContext(Dispatchers.Main) {
                    callback(false, userFriendly)
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

                conn = openBackendConnection(targetUrl, "POST", 10000, 10000).apply {
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

                    val prevUser = getLastSyncedUser(context)
                    if (prevUser == null || !prevUser.equals(email.trim(), ignoreCase = true)) {
                        val db = TokenDatabase.getInstance(context)
                        db.deleteSyncedAccounts()
                        db.markRemainingAsLocalOnly()
                        setLastServerTime(context, "1970-01-01T00:00:00Z")
                    }
                    setLastSyncedUser(context, email.trim())
                    lastAutoSyncTimestamp = 0L

                    setAuthToken(context, token)
                    setUserEmail(context, email.trim())
                    setUserId(context, userId)
                    setLastError(context, null)
                    withContext(Dispatchers.Main) {
                        callback(true, null)
                    }
                } else {
                    val errStr = conn.errorStream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: "HTTP $code"
                    val userFriendly = sanitizeErrorMessage(errStr, isRegister = false)
                    setLastError(context, userFriendly)
                    withContext(Dispatchers.Main) {
                        callback(false, userFriendly)
                    }
                }
            } catch (e: Exception) {
                val userFriendly = sanitizeErrorMessage(e.message, isRegister = false)
                setLastError(context, userFriendly)
                withContext(Dispatchers.Main) {
                    callback(false, userFriendly)
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

                conn = openBackendConnection(targetUrl, "POST", 15000, 15000).apply {
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
                    val userFriendly = sanitizeErrorMessage(errText)
                    setLastError(context, userFriendly)
                    for (acct in accounts) {
                        db.updateSyncStatus(acct.email, "FAILED", System.currentTimeMillis())
                    }
                    withContext(Dispatchers.Main) {
                        callback?.invoke(false, userFriendly)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during push", e)
                val userFriendly = sanitizeErrorMessage(e.message)
                setLastError(context, userFriendly)
                for (acct in accounts) {
                    try {
                        db.updateSyncStatus(acct.email, "FAILED", System.currentTimeMillis())
                    } catch (_: Exception) {}
                }
                withContext(Dispatchers.Main) {
                    callback?.invoke(false, userFriendly)
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
                conn = openBackendConnection(targetUrl, "GET", 15000, 15000).apply {
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
                    var applied = 0

                    for (i in 0 until instancesArr.length()) {
                        val inst = instancesArr.getJSONObject(i)
                        val instanceId = inst.optString("instance_id")
                        val email = inst.optString("email")
                        val isDeleted = inst.optBoolean("deleted", false)

                        if (isDeleted) {
                            if (instanceId.isNotEmpty()) {
                                db.deleteAccountByInstanceId(instanceId)
                            } else if (email.isNotEmpty()) {
                                db.deleteAccount(email)
                            }
                            applied++
                            continue
                        }

                        val existing = if (instanceId.isNotEmpty()) db.getAccountByInstanceId(instanceId) else db.getAccount(email)
                        val account = TokenAccount(
                            instanceId = if (instanceId.isNotEmpty()) instanceId else (existing?.instanceId ?: java.util.UUID.randomUUID().toString()),
                            email = email,
                            masterToken = inst.optString("master_token"),
                            aasToken = inst.optString("aas_token").takeIf { it.isNotEmpty() },
                            sid = inst.optString("sid").takeIf { it.isNotEmpty() },
                            lsid = inst.optString("lsid").takeIf { it.isNotEmpty() },
                            androidId = inst.optString("android_id"),
                            securityToken = inst.optString("security_token"),
                            deviceName = inst.optString("device_name", "Unknown Device"),
                            deviceModel = inst.optString("device_model", "Unknown Model"),
                            deviceBrand = inst.optString("device_brand", "Unknown Brand"),
                            deviceFingerprint = inst.optString("device_fingerprint", ""),
                            deviceSdk = inst.optInt("device_sdk", 34),
                            accountStatus = inst.optString("account_status", "ACTIVE"),
                            signedOutReason = inst.optString("signed_out_reason").takeIf { it.isNotEmpty() },
                            syncStatus = "SYNCED",
                            lastSyncAt = System.currentTimeMillis()
                        )
                        db.insertOrUpdate(account)
                        applied++
                    }

                    setLastError(context, null)
                    Log.i(TAG, "Delta sync complete: applied $applied change(s). Server time: $serverTime")
                    withContext(Dispatchers.Main) {
                        callback(true, applied, null)
                    }
                } else {
                    val errText = conn.errorStream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: "HTTP $code"
                    val userFriendly = sanitizeErrorMessage(errText)
                    setLastError(context, userFriendly)
                    Log.w(TAG, "Pull delta failed ($code): $errText")
                    withContext(Dispatchers.Main) {
                        callback(false, 0, userFriendly)
                    }
                }
            } catch (e: Exception) {
                val userFriendly = sanitizeErrorMessage(e.message)
                setLastError(context, userFriendly)
                Log.w(TAG, "Pull delta error: ${e.message}")
                withContext(Dispatchers.Main) {
                    callback(false, 0, userFriendly)
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
                conn = openBackendConnection(targetUrl, "GET", 8000, 8000)

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
