/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tokeng.gms.ui

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
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceViewHolder
import org.tokeng.gms.R
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import org.tokeng.gms.database.TokenAccount
import org.tokeng.gms.database.TokenDatabase
import org.tokeng.gms.sync.BackendSyncManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncStatusFragment : PreferenceFragmentCompat() {

    private val expandedSyncAccounts = mutableSetOf<String>()
    private var lastSyncSignature: String? = null
    private val avatarCache = mutableMapOf<String, Drawable>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = context ?: return
        val screen = preferenceManager.createPreferenceScreen(context)
        preferenceScreen = screen
        refreshSyncDashboard(force = true)
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
        refreshSyncDashboard(force = false)
    }

    fun refreshSyncDashboard(force: Boolean = true) {
        val context = context ?: return
        val accounts = TokenDatabase.getInstance(context).getAllAccounts()
        val lastError = BackendSyncManager.getLastError(context)
        val backendUrl = BackendSyncManager.getBackendUrl(context)
        val currentSignature = "$lastError:$backendUrl:" + accounts.joinToString("|") {
            "${it.email}:${it.syncStatus}:${it.accountStatus}:${it.lastSyncAt}:${it.androidId}:${it.securityToken}"
        }
        if (!force && currentSignature == lastSyncSignature && (preferenceScreen?.preferenceCount ?: 0) > 0) {
            return
        }
        lastSyncSignature = currentSignature

        val screen = preferenceManager.createPreferenceScreen(context)
        preferenceScreen = screen

        // 0. BANNERS (Local Mode & Server Inaccessible)
        if (BackendSyncManager.isLocalMode(context)) {
            val localModeBanner = Preference(context).apply {
                layoutResource = R.layout.preference_material_information
                isSelectable = false
                title = "⚠ LOCAL MODE ACTIVE"
                summary = "You are currently running in offline Local Mode. Connecting to a cloud account will replace your local accounts completely. Cloud merge will be added in a future update."
            }
            screen.addPreference(localModeBanner)
        }

        if (lastError != null && lastError.contains("refused", ignoreCase = true) || (lastError != null && lastError.contains("unreachable", ignoreCase = true))) {
            val serverDownBanner = Preference(context).apply {
                layoutResource = R.layout.preference_material_information
                isSelectable = false
                title = "⚠ Server Inaccessible"
                summary = "Upstream server is temporarily inaccessible. Please update to the latest version or try again later."
            }
            screen.addPreference(serverDownBanner)
        }

        // 1. CLOUD ACCOUNT & SESSION
        val accountCategory = PreferenceCategory(context).apply {
            title = "TOKEN-G CLOUD ACCOUNT"
            layoutResource = R.layout.preference_material_category
            isIconSpaceReserved = false
        }
        screen.addPreference(accountCategory)

        if (!BackendSyncManager.isLoggedIn(context)) {
            val loginPref = Preference(context).apply {
                layoutResource = R.layout.preference_material_top
                key = "pref_cloud_login"
                title = "Sign In / Register Cloud Account"
                summary = "Connect to TokenG backend server to sync your credential pool"
                icon = AppCompatResources.getDrawable(context, R.drawable.ic_accounts)
                isIconSpaceReserved = true
                setOnPreferenceClickListener {
                    showAuthDialog()
                    true
                }
            }
            accountCategory.addPreference(loginPref)
        } else {
            val userEmail = BackendSyncManager.getUserEmail(context) ?: "Cloud User"
            val sessionPref = Preference(context).apply {
                layoutResource = R.layout.preference_material_top
                key = "pref_cloud_session"
                title = "Signed In: $userEmail"
                summary = "Cloud Account Connected (tokeng.sanshiro.qzz.io)"
                icon = AppCompatResources.getDrawable(context, R.drawable.ic_accounts)
                isIconSpaceReserved = true
            }
            accountCategory.addPreference(sessionPref)

            // Pull Delta (Download instances)
            val pullDeltaPref = Preference(context).apply {
                layoutResource = R.layout.preference_material_bottom
                key = "pref_pull_delta"
                title = "Pull Delta from Cloud"
                summary = "Download new or updated token instances from your pool"
                icon = AppCompatResources.getDrawable(context, R.drawable.ic_sync)
                isIconSpaceReserved = true
                setOnPreferenceClickListener {
                    view?.let { v -> Snackbar.make(v, "Pulling delta from cloud...", Snackbar.LENGTH_SHORT).show() }
                    BackendSyncManager.pullDelta(context) { success, count, err ->
                        val msg = if (success) {
                            "✓ Pulled $count instance(s) from cloud!"
                        } else {
                            "Pull failed: ${err ?: "unknown"}"
                        }
                        view?.let { v -> Snackbar.make(v, msg, Snackbar.LENGTH_LONG).show() }
                        refreshSyncDashboard(force = true)
                    }
                    true
                }
            }
            accountCategory.addPreference(pullDeltaPref)
        }

        // 2. CLOUD SYNC CONTROLS
        val controlsCategory = PreferenceCategory(context).apply {
            title = "CLOUD SYNC CONTROLS"
            layoutResource = R.layout.preference_material_category
            isIconSpaceReserved = false
        }
        screen.addPreference(controlsCategory)

        // Sync All Accounts (Top Card)
        val syncAllPref = Preference(context).apply {
            layoutResource = R.layout.preference_material_top
            key = "pref_sync_all"
            title = "Push All Accounts to Cloud"
            summary = "Push accounts and tokens to your central cloud pool"
            icon = AppCompatResources.getDrawable(context, R.drawable.ic_sync)
            isIconSpaceReserved = true
            setOnPreferenceClickListener {
                view?.let { v -> Snackbar.make(v, "Syncing all accounts to cloud...", Snackbar.LENGTH_SHORT).show() }
                BackendSyncManager.syncAllAccounts(context) { successCount, failCount ->
                    val msg = if (failCount == 0) {
                        "✓ Successfully synced $successCount account(s) to cloud!"
                    } else {
                        "Synced $successCount, failed $failCount account(s)"
                    }
                    view?.let { v -> Snackbar.make(v, msg, Snackbar.LENGTH_LONG).show() }
                    refreshSyncDashboard()
                }
                true
            }
        }
        controlsCategory.addPreference(syncAllPref)

        // Sign Out & Wipe Local Vault (Bottom Card)
        val signOutPref = Preference(context).apply {
            layoutResource = R.layout.preference_material_bottom
            key = "pref_sign_out"
            title = "Sign Out & Wipe Local Vault"
            summary = "Clear all local accounts, tokens, and encryption keys from this device"
            icon = AppCompatResources.getDrawable(context, android.R.drawable.ic_lock_power_off)
            isIconSpaceReserved = true
            setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(context)
                    .setTitle("Wipe Local Vault & Sign Out")
                    .setMessage("Are you sure? This will delete all local accounts, tokens, and encryption keys from this device.")
                    .setPositiveButton("Wipe & Sign Out") { _, _ ->
                        BackendSyncManager.signOut(context)
                        val intent = Intent(context, AuthGateActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(intent)
                        activity?.finish()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }
        controlsCategory.addPreference(signOutPref)

        // 2. ACCOUNT SYNC STATUS
        val statusCategory = PreferenceCategory(context).apply {
            title = "ACCOUNT SYNC STATUS"
            layoutResource = R.layout.preference_material_category
            isIconSpaceReserved = false
        }
        screen.addPreference(statusCategory)

        if (accounts.isEmpty()) {
            val emptyPref = Preference(context).apply {
                layoutResource = R.layout.preference_material_information
                isSelectable = false
                title = "No Accounts to Sync"
                summary = "Sign in to a Google account in the Accounts tab first. Accounts registered will appear here with their central database sync status."
            }
            statusCategory.addPreference(emptyPref)
            return
        }

        accounts.forEach { account ->
            val activeToken = if (!account.aasToken.isNullOrEmpty()) account.aasToken!! else account.masterToken
            val cardPref = SyncAccountCardPreference(
                context = context,
                account = account,
                obfuscatedToken = obfuscateToken(activeToken),
                circleAvatar = getCircleAvatar(account),
                onResyncAccount = {
                    view?.let { v -> Snackbar.make(v, "Syncing ${account.email}...", Snackbar.LENGTH_SHORT).show() }
                    BackendSyncManager.syncAccount(context, account) { success, err ->
                        val msg = if (success) "✓ Synced ${account.email} to database!" else "⚠ Sync failed: ${err ?: "unknown"}"
                        view?.let { v -> Snackbar.make(v, msg, Snackbar.LENGTH_LONG).show() }
                        refreshSyncDashboard()
                    }
                }
            )
            statusCategory.addPreference(cardPref)
        }
    }

    inner class SyncAccountCardPreference(
        context: Context,
        private val account: TokenAccount,
        private val obfuscatedToken: String,
        private val circleAvatar: Drawable,
        private val onResyncAccount: () -> Unit
    ) : Preference(context) {

        private var isExpanded = expandedSyncAccounts.contains(account.email)

        init {
            layoutResource = R.layout.preference_sync_account_card
            key = "sync_card_${account.email}"
            isSelectable = false
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val itemView = holder.itemView
            val avatarView = itemView.findViewById<ImageView>(R.id.sync_account_avatar)
            val emailView = itemView.findViewById<TextView>(R.id.sync_account_email)
            val timeView = itemView.findViewById<TextView>(R.id.sync_account_time)
            val badge = itemView.findViewById<ImageView>(R.id.sync_status_badge)
            val chevron = itemView.findViewById<ImageView>(R.id.sync_chevron_arrow)
            val expandableContainer = itemView.findViewById<LinearLayout>(R.id.sync_expandable_container)
            val textSyncedPairs = itemView.findViewById<TextView>(R.id.text_synced_pairs)
            val btnResync = itemView.findViewById<View>(R.id.btn_resync_account)
            val cardHeader = itemView.findViewById<View>(R.id.sync_card_header)

            val lastSyncStr = if (account.lastSyncAt > 0L) {
                try {
                    SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(account.lastSyncAt))
                } catch (e: Exception) { "" }
            } else ""

            val badgeRes = when (account.syncStatus) {
                "SYNCED" -> R.drawable.ic_sync_check
                "FAILED" -> R.drawable.ic_sync_failed
                else -> R.drawable.ic_sync_pending
            }

            avatarView?.setImageDrawable(circleAvatar)
            emailView?.text = account.email
            if (account.isSignedOut) {
                timeView?.text = "⚠️ Signed Out • ${if (lastSyncStr.isNotEmpty()) "Last sync: $lastSyncStr" else "Not synced"}"
                timeView?.setTextColor(0xFFD32F2F.toInt())
            } else {
                timeView?.text = if (lastSyncStr.isNotEmpty()) "Last sync: $lastSyncStr" else "Not synced yet"
                timeView?.setTextColor(MaterialColors.getColor(itemView, android.R.attr.textColorSecondary, 0x8A000000.toInt()))
            }
            badge?.setImageResource(badgeRes)

            textSyncedPairs?.text = "email: ${account.email}\n" +
                    "auth_status: ${account.accountStatus}${if (account.signedOutReason != null) " (${account.signedOutReason})" else ""}\n" +
                    "gsm_id: ${if (account.androidId.isNotEmpty()) account.androidId else "Pending"}\n" +
                    "device: ${account.deviceName} (${account.deviceModel})\n" +
                    "master_token: $obfuscatedToken\n" +
                    "sec_token: ${if (account.securityToken.isNotEmpty()) account.securityToken else "None"}\n" +
                    "sync_status: ${account.syncStatus}"

            expandableContainer?.visibility = if (isExpanded) View.VISIBLE else View.GONE
            chevron?.rotation = if (isExpanded) 180f else 0f

            btnResync?.setOnClickListener { onResyncAccount() }

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
                    expandedSyncAccounts.add(account.email)
                } else {
                    expandedSyncAccounts.remove(account.email)
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

    private fun showConnectionDiagnosticsDialog() {
        val context = requireContext()
        val backendUrl = BackendSyncManager.getBackendUrl(context)
        val lastError = BackendSyncManager.getLastError(context) ?: "No errors recorded. Connection is healthy."

        val message = "Target Server: $backendUrl\n\n" +
                "Connection Log:\n$lastError\n\n" +
                "Troubleshooting:\n" +
                "• Ensure backend database server is running.\n" +
                "• Verify Android device & host machine are on the same Wi-Fi.\n" +
                "• Check host firewall allows incoming connections on the port.\n" +
                "• Update Backend Database URL if server IP changed."

        MaterialAlertDialogBuilder(context)
            .setTitle("Connection Diagnostics")
            .setMessage(message)
            .setPositiveButton("Test Connection Now") { _, _ ->
                view?.let { v -> Snackbar.make(v, "Testing connection to $backendUrl...", Snackbar.LENGTH_SHORT).show() }
                BackendSyncManager.testConnection(context) { success, msg ->
                    val resultMsg = if (success) "✓ Connection Successful: $msg" else "⚠ Connection Failed: $msg"
                    view?.let { v -> Snackbar.make(v, resultMsg, Snackbar.LENGTH_LONG).show() }
                    refreshSyncDashboard()
                }
            }
            .setNegativeButton("Close", null)
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
                    refreshSyncDashboard()
                    view?.let { v -> Snackbar.make(v, "Backend URL updated: $newUrl", Snackbar.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAuthDialog() {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val emailInput = EditText(context).apply {
            hint = "Gmail Address (e.g. user@gmail.com)"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(BackendSyncManager.getUserEmail(context) ?: "")
        }
        val passInput = EditText(context).apply {
            hint = "Password (min 8 chars)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(emailInput)
        layout.addView(passInput)

        MaterialAlertDialogBuilder(context)
            .setTitle("TokenG Cloud Sign In / Register")
            .setView(layout)
            .setPositiveButton("Sign In") { _, _ ->
                val email = emailInput.text.toString().trim()
                val pass = passInput.text.toString()
                if (email.isNotEmpty() && pass.isNotEmpty()) {
                    view?.let { v -> Snackbar.make(v, "Signing in...", Snackbar.LENGTH_SHORT).show() }
                    BackendSyncManager.login(context, email, pass) { success, err ->
                        val msg = if (success) "✓ Signed in as $email" else "⚠ Sign in failed: ${err ?: "unknown"}"
                        view?.let { v -> Snackbar.make(v, msg, Snackbar.LENGTH_LONG).show() }
                        refreshSyncDashboard(force = true)
                    }
                }
            }
            .setNeutralButton("Register") { _, _ ->
                val email = emailInput.text.toString().trim()
                val pass = passInput.text.toString()
                if (email.isNotEmpty() && pass.isNotEmpty()) {
                    view?.let { v -> Snackbar.make(v, "Registering account...", Snackbar.LENGTH_SHORT).show() }
                    BackendSyncManager.register(context, email, pass) { success, err ->
                        val msg = if (success) "✓ Registered & signed in as $email" else "⚠ Registration failed: ${err ?: "unknown"}"
                        view?.let { v -> Snackbar.make(v, msg, Snackbar.LENGTH_LONG).show() }
                        refreshSyncDashboard(force = true)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
