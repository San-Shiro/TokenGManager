/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tokeng.gms.ui

import android.accounts.Account
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceViewHolder
import org.tokeng.gms.R
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.microg.gms.auth.AuthConstants
import org.tokeng.gms.auth.SpoofTokenFetcher
import org.tokeng.gms.auth.ValidationResult
import org.tokeng.gms.auth.login.LoginActivity
import org.microg.gms.common.Constants
import org.tokeng.gms.database.TokenAccount
import org.tokeng.gms.database.TokenDatabase
import org.tokeng.gms.sync.BackendSyncManager
import org.tokeng.gms.sync.TokenValidationRunner
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UnifiedDashboardFragment : PreferenceFragmentCompat() {

    private val expandedAccounts = mutableSetOf<String>()
    private var initializedExpandedState = false
    private var lastAccountsSignature: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = context ?: return
        val screen = preferenceManager.createPreferenceScreen(context)
        preferenceScreen = screen
        refreshDashboard(force = true)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val bg = MaterialColors.getColor(view, android.R.attr.colorBackground)
        view.setBackgroundColor(bg)
        setDivider(null)
        setDividerHeight(0)
        listView?.let { rv ->
            rv.itemAnimator = null
            rv.overScrollMode = View.OVER_SCROLL_NEVER
            rv.setBackgroundColor(bg)
            rv.clipToPadding = false
            val padTop = (8 * resources.displayMetrics.density).toInt()
            val padBottom = (24 * resources.displayMetrics.density).toInt()
            rv.setPadding(rv.paddingLeft, padTop, rv.paddingRight, padBottom)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard(force = false)
        context?.let { ctx ->
            TokenValidationRunner.maybeValidateAll(ctx)
        }
    }

    private val avatarCache = mutableMapOf<String, Drawable>()

    fun refreshDashboard(force: Boolean = true) {
        val context = context ?: return
        val accounts = TokenDatabase.getInstance(context).getAllAccounts()
        val currentSignature = accounts.joinToString("|") {
            "${it.email}:${it.androidId}:${it.masterToken}:${it.aasToken ?: ""}:${it.deviceName}:${it.deviceModel}:${it.deviceSdk}:${it.accountStatus}:${it.lastValidatedAt}"
        }
        if (!force && currentSignature == lastAccountsSignature && (preferenceScreen?.preferenceCount ?: 0) > 0) {
            return
        }
        lastAccountsSignature = currentSignature

        val screen = preferenceManager.createPreferenceScreen(context)
        preferenceScreen = screen

        // 1. TOP ADD ACCOUNT CARD (Simple Home)
        val actionCategory = PreferenceCategory(context).apply {
            title = "ADD ACCOUNT"
            layoutResource = R.layout.preference_material_category
            isIconSpaceReserved = false
        }
        screen.addPreference(actionCategory)

        val addAccountPref = Preference(context).apply {
            layoutResource = R.layout.preference_material_single
            key = "pref_add_account"
            title = "+ Add Google Account"
            summary = "Add a Google account to your vault"
            icon = AppCompatResources.getDrawable(context, R.drawable.ic_add)
            isIconSpaceReserved = true
            setOnPreferenceClickListener {
                val intent = Intent(context, LoginActivity::class.java)
                startActivity(intent)
                true
            }
        }
        actionCategory.addPreference(addAccountPref)

        // ACCOUNTS & DEVICES CATEGORY (Single unified category for all accounts)
        val accountsCategory = PreferenceCategory(context).apply {
            title = "ACCOUNTS & DEVICES"
            layoutResource = R.layout.preference_material_category
            isIconSpaceReserved = false
        }
        screen.addPreference(accountsCategory)

        if (accounts.isEmpty()) {
            val emptyPref = Preference(context).apply {
                layoutResource = R.layout.preference_material_information
                isSelectable = false
                title = "No Accounts Registered"
                summary = "No Google accounts found in vault.\n\nTap '+ Add Google Account' above to get started."
            }
            accountsCategory.addPreference(emptyPref)
            return
        }

        accounts.forEach { account ->
            val activeToken = if (!account.aasToken.isNullOrEmpty()) account.aasToken!! else account.masterToken
            val cardPref = AccountCardPreference(
                context = context,
                account = account,
                obfuscatedToken = obfuscateToken(activeToken),
                circleAvatar = getCircleAvatar(account),
                onCopyToken = {
                    copyToClipboard("Master AAS Token", activeToken)
                },
                onGenerateToken = {
                    showTokenGeneratorDialog(account)
                },
                onManageAccount = {
                    showAccountOptionsDialog(account)
                },
                onRelogin = {
                    startReLogin(account)
                }
            )
            accountsCategory.addPreference(cardPref)
        }
    }

    inner class AccountCardPreference(
        context: Context,
        private val account: TokenAccount,
        private val obfuscatedToken: String,
        private val circleAvatar: Drawable,
        private val onCopyToken: () -> Unit,
        private val onGenerateToken: () -> Unit,
        private val onManageAccount: () -> Unit,
        private val onRelogin: () -> Unit
    ) : Preference(context) {

        private var isExpanded = expandedAccounts.contains(account.email)

        init {
            layoutResource = R.layout.preference_account_card
            key = "acc_card_${account.email}"
            isSelectable = false
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val itemView = holder.itemView
            val avatarView = itemView.findViewById<ImageView>(R.id.account_avatar)
            val emailView = itemView.findViewById<TextView>(R.id.account_email)
            val gsmIdView = itemView.findViewById<TextView>(R.id.account_gsm_id)
            val chevron = itemView.findViewById<ImageView>(R.id.chevron_arrow)
            val expandableContainer = itemView.findViewById<LinearLayout>(R.id.expandable_container)
            val btnCopyToken = itemView.findViewById<View>(R.id.btn_copy_token)
            val textDeviceInfo = itemView.findViewById<TextView>(R.id.text_device_info)
            val textMasterToken = itemView.findViewById<TextView>(R.id.text_master_token)
            val btnGenerate = itemView.findViewById<View>(R.id.btn_generate_token)
            val btnManage = itemView.findViewById<View>(R.id.btn_manage_account)
            val btnRelogin = itemView.findViewById<View>(R.id.btn_relogin)
            val statusBadge = itemView.findViewById<TextView>(R.id.account_status_badge)
            val syncBadge = itemView.findViewById<TextView>(R.id.account_sync_badge)
            val btnHeaderSignIn = itemView.findViewById<View>(R.id.btn_header_signin)
            val signedOutBanner = itemView.findViewById<View>(R.id.signed_out_banner)
            val cardHeader = itemView.findViewById<View>(R.id.card_header)

            val gsfId = if (account.androidId.isNotEmpty() && account.androidId != "0") account.androidId else "Pending"

            avatarView?.setImageDrawable(circleAvatar)
            emailView?.text = account.email
            gsmIdView?.text = "GSM ID: $gsfId"
            textDeviceInfo?.text = "${account.deviceName} (${account.deviceModel}) • Android SDK ${account.deviceSdk}"
            textMasterToken?.text = "Master AAS: $obfuscatedToken"

            // Mark unsynced accounts with a red badge; do NOT mark accounts that are synced
            if (account.syncStatus != "SYNCED") {
                syncBadge?.visibility = View.VISIBLE
                syncBadge?.text = if (account.syncStatus == "LOCAL_ONLY") "LOCAL" else "UNSYNCED"
            } else {
                syncBadge?.visibility = View.GONE
            }

            // Disabled/signed-out accounts: gray out and show direct Sign In button
            if (account.isSignedOut) {
                itemView.alpha = 0.65f
                statusBadge?.visibility = View.VISIBLE
                signedOutBanner?.visibility = View.VISIBLE
                btnHeaderSignIn?.visibility = View.VISIBLE
                btnRelogin?.visibility = View.VISIBLE
                btnGenerate?.visibility = View.GONE
                btnHeaderSignIn?.setOnClickListener { onRelogin() }
                btnRelogin?.setOnClickListener { onRelogin() }
            } else {
                itemView.alpha = 1.0f
                statusBadge?.visibility = View.GONE
                signedOutBanner?.visibility = View.GONE
                btnHeaderSignIn?.visibility = View.GONE
                btnRelogin?.visibility = View.GONE
                btnGenerate?.visibility = View.VISIBLE
            }

            expandableContainer?.visibility = if (isExpanded) View.VISIBLE else View.GONE
            chevron?.rotation = if (isExpanded) 180f else 0f

            btnCopyToken?.setOnClickListener { onCopyToken() }
            btnGenerate?.setOnClickListener { onGenerateToken() }
            btnManage?.setOnClickListener { onManageAccount() }

            val toggleExpansion = {
                val parentContainer = (itemView.parent as? ViewGroup) ?: (itemView as? ViewGroup)
                if (parentContainer != null) {
                    android.transition.TransitionManager.beginDelayedTransition(
                        parentContainer,
                        android.transition.AutoTransition().apply {
                            duration = 250
                            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                        }
                    )
                }
                isExpanded = !isExpanded
                if (isExpanded) {
                    expandedAccounts.add(account.email)
                } else {
                    expandedAccounts.remove(account.email)
                }
                expandableContainer?.visibility = if (isExpanded) View.VISIBLE else View.GONE
                chevron?.animate()?.rotation(if (isExpanded) 180f else 0f)?.setDuration(250)?.start()
            }

            itemView.setOnClickListener { toggleExpansion() }
            cardHeader?.setOnClickListener { toggleExpansion() }
            chevron?.setOnClickListener { toggleExpansion() }
        }
    }

    private fun obfuscateToken(token: String?): String {
        if (token.isNullOrEmpty()) return "No Token"
        if (token.length <= 16) return "••••••••••••••••"
        val prefix = token.take(6)
        val suffix = token.takeLast(4)
        return "$prefix••••••••••••••••$suffix"
    }

    private fun showTokenGeneratorDialog(account: TokenAccount) {
        val context = requireContext()
        val options = arrayOf(
            "Google Photos (Native 1P aas_et)",
            "Google Photos Auth Request String (Full URL Payload)",
            "YouTube (Temporary ya29)",
            "Gmail (Temporary ya29)",
            "Google Drive (Temporary ya29)",
            "Custom OAuth2 Scope..."
        )

        MaterialAlertDialogBuilder(context)
            .setTitle("Generate Token for ${account.email}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> generateAppToken(account, SpoofTokenFetcher.PHOTOS_PACKAGE, SpoofTokenFetcher.PHOTOS_SCOPE, "Google Photos")
                    1 -> copyGPhotosAuthRequestString(account)
                    2 -> generateAppToken(account, SpoofTokenFetcher.YOUTUBE_PACKAGE, SpoofTokenFetcher.YOUTUBE_SCOPE, "YouTube")
                    3 -> generateAppToken(account, SpoofTokenFetcher.GMAIL_PACKAGE, SpoofTokenFetcher.GMAIL_SCOPE, "Gmail")
                    4 -> generateAppToken(account, SpoofTokenFetcher.DRIVE_PACKAGE, SpoofTokenFetcher.DRIVE_SCOPE, "Google Drive")
                    5 -> showCustomScopeDialog(account)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun generateAppToken(account: TokenAccount, packageName: String, scope: String, appName: String) {
        val context = requireContext()
        view?.let { v -> Snackbar.make(v, "Generating $appName token for ${account.email}...", Snackbar.LENGTH_SHORT).show() }

        lifecycleScope.launch(Dispatchers.IO) {
            val androidAccount = Account(account.email, AuthConstants.DEFAULT_ACCOUNT_TYPE)
            val token = SpoofTokenFetcher.fetchCustomToken(context, androidAccount, packageName, SpoofTokenFetcher.GOOGLE_SIG, scope)

            withContext(Dispatchers.Main) {
                if (!isAdded || activity == null || activity?.isFinishing == true || activity?.isDestroyed == true) {
                    return@withContext
                }
                val actContext = context ?: return@withContext
                if (token.startsWith("ERROR")) {
                    MaterialAlertDialogBuilder(actContext)
                        .setTitle("Token Generation Failed")
                        .setMessage(token)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    val tokenTypeLabel = when {
                        token.startsWith("ya29.") -> "Temporary OAuth2 (ya29)"
                        token.startsWith("aas_et/") -> "Native 1P AES (aas_et)"
                        else -> "App Token"
                    }
                    copyToClipboard("$appName Token", token)
                    MaterialAlertDialogBuilder(actContext)
                        .setTitle("✓ $appName $tokenTypeLabel Generated")
                        .setMessage("Token has been copied to clipboard:\n\n${if (token.length > 80) token.take(40) + "..." + token.takeLast(20) else token}")
                        .setPositiveButton("Copy Again") { _, _ -> copyToClipboard("$appName Token", token) }
                        .setNegativeButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun copyGPhotosAuthRequestString(account: TokenAccount) {
        val context = context ?: return
        val masterToken = if (!account.aasToken.isNullOrEmpty()) account.aasToken!! else account.masterToken
        if (masterToken.isEmpty()) {
            Toast.makeText(context, "No master token available for this account", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val gsfHex = if (account.androidId.isNotEmpty()) account.androidId else java.lang.Long.toHexString(org.tokeng.gms.checkin.LastCheckinInfo.read(context).androidId)
            val locale = Locale.getDefault()
            val countryStr = locale.country.takeIf { it.isNotEmpty() } ?: "US"
            val langStr = locale.language.takeIf { it.isNotEmpty() } ?: "en"
            val lang = "${langStr}_${countryStr}"
            val country = countryStr.lowercase(Locale.US)

            val sb = StringBuilder()
            sb.append("androidId=").append(URLEncoder.encode(gsfHex, "UTF-8"))
            sb.append("&app=").append(URLEncoder.encode(SpoofTokenFetcher.PHOTOS_PACKAGE, "UTF-8"))
            sb.append("&client_sig=").append(URLEncoder.encode(SpoofTokenFetcher.GOOGLE_SIG, "UTF-8"))
            sb.append("&callerPkg=").append(URLEncoder.encode(SpoofTokenFetcher.PHOTOS_PACKAGE, "UTF-8"))
            sb.append("&callerSig=").append(URLEncoder.encode(SpoofTokenFetcher.GOOGLE_SIG, "UTF-8"))
            sb.append("&device_country=").append(URLEncoder.encode(country, "UTF-8"))
            sb.append("&Email=").append(URLEncoder.encode(account.email, "UTF-8"))
            sb.append("&google_play_services_version=").append(Constants.GMS_VERSION_CODE)
            sb.append("&lang=").append(URLEncoder.encode(lang, "UTF-8"))
            sb.append("&oauth2_foreground=1")
            sb.append("&operatorCountry=").append(URLEncoder.encode(country, "UTF-8"))
            sb.append("&sdk_version=").append(account.deviceSdk)
            sb.append("&service=").append(URLEncoder.encode(SpoofTokenFetcher.PHOTOS_SCOPE, "UTF-8"))
            sb.append("&source=android")
            sb.append("&Token=").append(URLEncoder.encode(masterToken, "UTF-8"))

            val requestString = sb.toString()
            copyToClipboard("GPhotos Auth Request String", requestString)
            Toast.makeText(context, "✓ GPhotos Auth Request String copied to clipboard!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Error: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun showCustomScopeDialog(account: TokenAccount) {
        val context = requireContext()
        val editText = EditText(context).apply {
            hint = "e.g. oauth2:https://www.googleapis.com/auth/drive"
        }
        val container = FrameLayout(context).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(editText)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("Enter Custom OAuth2 Scope")
            .setView(container)
            .setPositiveButton("Generate") { _, _ ->
                val scope = editText.text.toString().trim()
                if (scope.isNotEmpty()) {
                    generateAppToken(account, SpoofTokenFetcher.GMS_PACKAGE, scope, "Custom Scope")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startReLogin(account: TokenAccount) {
        val intent = Intent(context, LoginActivity::class.java).apply {
            putExtra("email", account.email)
            putExtra("relogin_mode", true)
            putExtra("androidId", account.androidId)
            putExtra("securityToken", account.securityToken)
            putExtra("deviceName", account.deviceName)
            putExtra("deviceModel", account.deviceModel)
            putExtra("deviceBrand", account.deviceBrand)
            putExtra("deviceFingerprint", account.deviceFingerprint)
            putExtra("deviceSdk", account.deviceSdk)
        }
        startActivity(intent)
    }

    private fun verifyTokenStatus(account: TokenAccount) {
        val context = context ?: return
        view?.let { v -> Snackbar.make(v, "Verifying master token with Google...", Snackbar.LENGTH_SHORT).show() }
        lifecycleScope.launch {
            val result = TokenValidationRunner.validateAccount(context, account, force = true)
            if (!isAdded || activity?.isFinishing == true || activity?.isDestroyed == true) return@launch

            when (result) {
                is ValidationResult.Valid -> {
                    view?.let { v -> Snackbar.make(v, "✓ Master token is VALID and active!", Snackbar.LENGTH_LONG).show() }
                }
                is ValidationResult.Invalid -> {
                    MaterialAlertDialogBuilder(context)
                        .setTitle("Account Signed Out")
                        .setMessage("Google rejected the master token for ${account.email} (${result.reason}).\n\nThis account has been marked as signed out. Re-login is required to mint fresh tokens.")
                        .setPositiveButton("Re-login") { _, _ -> startReLogin(account) }
                        .setNegativeButton(android.R.string.ok, null)
                        .show()
                }
                is ValidationResult.Transient -> {
                    view?.let { v -> Snackbar.make(v, "⚠ Verification deferred: ${result.reason}", Snackbar.LENGTH_LONG).show() }
                }
            }
            refreshDashboard(force = true)
        }
    }

    private fun showAccountOptionsDialog(account: TokenAccount) {
        val context = requireContext()
        val optionsList = mutableListOf<Pair<String, () -> Unit>>()

        optionsList.add("Verify Token Status (Google Auth)" to { verifyTokenStatus(account) })
        if (account.isSignedOut) {
            optionsList.add("Re-login Account" to { startReLogin(account) })
        }
        optionsList.add("View Full Account Details" to { showAccountDetailsDialog(account) })
        optionsList.add("Sync to Central Database" to { syncSingleAccount(account) })
        optionsList.add("Copy GSF Android ID" to { copyToClipboard("GSF Android ID", account.androidId) })
        val activeToken = if (!account.aasToken.isNullOrEmpty()) account.aasToken!! else account.masterToken
        optionsList.add("Copy Master AAS Token (g.a)" to { copyToClipboard("Master AAS Token (g.a)", activeToken) })
        optionsList.add("Remove Account" to { showRemovalConfirmation(account) })

        val labels = optionsList.map { it.first }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(account.email)
            .setItems(labels) { _, which ->
                optionsList[which].second.invoke()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun syncSingleAccount(account: TokenAccount) {
        val context = requireContext()
        view?.let { v -> Snackbar.make(v, "Syncing ${account.email} to database...", Snackbar.LENGTH_SHORT).show() }
        BackendSyncManager.syncAccount(context, account) { success, err ->
            val msg = if (success) "✓ Synced ${account.email} to DB!" else "⚠ Sync failed: ${err ?: "unknown"}"
            view?.let { v -> Snackbar.make(v, msg, Snackbar.LENGTH_LONG).show() }
            refreshDashboard()
        }
    }

    private fun showRemovalConfirmation(account: TokenAccount) {
        val context = requireContext()
        MaterialAlertDialogBuilder(context)
            .setTitle("Remove Account")
            .setMessage("Are you sure you want to remove ${account.email} from TokenG? All local tokens and device registration for this account will be erased.")
            .setPositiveButton("Remove") { _, _ ->
                TokenDatabase.getInstance(context).deleteAccount(account.email)
                view?.let { v -> Snackbar.make(v, "Removed ${account.email}", Snackbar.LENGTH_SHORT).show() }
                refreshDashboard()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAccountDetailsDialog(account: TokenAccount) {
        val context = requireContext()
        val validatedDateStr = if (account.lastValidatedAt > 0) {
            try { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(account.lastValidatedAt)) } catch (_: Exception) { "Unknown" }
        } else {
            "Not checked yet"
        }
        val statusDetail = if (account.isSignedOut) {
            "SIGNED OUT (${account.signedOutReason ?: "Auth Rejected"})"
        } else {
            "ACTIVE"
        }

        val msg = """
            Email: ${account.email}
            Account Status: $statusDetail
            Last Validated: $validatedDateStr (${account.lastValidationResult ?: "N/A"})
            Name: ${account.firstName ?: ""} ${account.lastName ?: ""}
            User ID: ${account.googleUserId ?: "N/A"}
            
            Device: ${account.deviceName}
            Model: ${account.deviceModel}
            Brand: ${account.deviceBrand}
            SDK Version: Android ${account.deviceSdk}
            Fingerprint: ${account.deviceFingerprint}
            
            GSF ID (GSM ID): ${account.androidId}
            Security Token: ${account.securityToken}
            
            Master AAS Token: ${account.aasToken ?: account.masterToken}
            
            Sync Status: ${account.syncStatus}
            Registered: ${try { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(account.registeredAt)) } catch (e: Exception) { "" }}
        """.trimIndent()

        MaterialAlertDialogBuilder(context)
            .setTitle("Account & Device Details")
            .setMessage(msg)
            .setPositiveButton("Copy All Details") { _, _ -> copyToClipboard("Account Details", msg) }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun showBackendUrlDialog() {
        val context = requireContext()
        val currentUrl = BackendSyncManager.getBackendUrl(context)
        val editText = EditText(context).apply {
            setText(currentUrl)
            setSelection(currentUrl.length)
            hint = BackendSyncManager.DEFAULT_BACKEND_URL
        }
        val container = FrameLayout(context).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(editText)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("Backend Database URL")
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newUrl = editText.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    BackendSyncManager.setBackendUrl(context, newUrl)
                    refreshDashboard()
                    view?.let { v -> Snackbar.make(v, "Backend URL updated: $newUrl", Snackbar.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String?) {
        val context = context ?: return
        if (text.isNullOrEmpty()) {
            Toast.makeText(context, "Nothing to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            view?.let { v -> Snackbar.make(v, "Copied $label to clipboard!", Snackbar.LENGTH_SHORT).show() }
                ?: Toast.makeText(context, "Copied $label to clipboard!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getCircleAvatar(account: TokenAccount): Drawable {
        val cacheKey = "${account.email}:${account.firstName ?: ""}"
        avatarCache[cacheKey]?.let { return it }

        val actContext = context ?: requireContext()
        val size = (40 * resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MaterialColors.getColor(actContext, androidx.appcompat.R.attr.colorPrimary, 0xFF3B82F6.toInt())
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        val initial = (account.firstName?.takeIf { it.isNotBlank() }?.take(1)
            ?: account.email.takeIf { it.isNotBlank() }?.take(1)
            ?: "G").uppercase(Locale.US)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MaterialColors.getColor(actContext, org.tokeng.gms.R.attr.colorOnPrimary, 0xFFFFFFFF.toInt())
            textSize = size * 0.48f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val bounds = Rect()
        textPaint.getTextBounds(initial, 0, initial.length, bounds)
        val y = size / 2f + bounds.height() / 2f
        canvas.drawText(initial, size / 2f, y, textPaint)

        val drawable = RoundedBitmapDrawableFactory.create(resources, bitmap).apply {
            isCircular = true
        }
        avatarCache[cacheKey] = drawable
        return drawable
    }
}
