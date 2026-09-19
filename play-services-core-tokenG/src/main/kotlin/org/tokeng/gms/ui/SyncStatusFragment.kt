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
import androidx.core.widget.ImageViewCompat
import org.tokeng.gms.R
import com.google.android.material.button.MaterialButtonToggleGroup
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
        val localAccounts = TokenDatabase.getInstance(context).getLocalOnlyAccounts()
        val userEmail = BackendSyncManager.getUserEmail(context)
        val currentSignature = "$userEmail:" + localAccounts.joinToString("|") {
            "${it.email}:${it.syncStatus}:${it.lastSyncAt}"
        }
        if (!force && currentSignature == lastSyncSignature && (preferenceScreen?.preferenceCount ?: 0) > 0) {
            return
        }
        lastSyncSignature = currentSignature

        val screen = preferenceManager.createPreferenceScreen(context)
        preferenceScreen = screen

        // 0. BANNERS (Local Mode)
        if (BackendSyncManager.isLocalMode(context)) {
            val localModeBanner = Preference(context).apply {
                layoutResource = R.layout.preference_material_information
                isSelectable = false
                title = "Local Mode Active"
                summary = "Vault is operating locally on this device. Sign in to enable cloud sync."
            }
            screen.addPreference(localModeBanner)
        }

        // 1. CLOUD ACCOUNT & SESSION
        if (BackendSyncManager.isLoggedIn(context)) {
            val email = userEmail ?: "Signed In"
            val emailPref = CleanEmailPreference(context, email)
            screen.addPreference(emailPref)
        } else {
            val loginCategory = PreferenceCategory(context).apply {
                title = "ACCOUNT"
                layoutResource = R.layout.preference_material_category
                isIconSpaceReserved = false
            }
            screen.addPreference(loginCategory)

            val loginPref = Preference(context).apply {
                layoutResource = R.layout.preference_material_single
                key = "pref_cloud_login"
                title = "Sign In to Cloud"
                summary = "Sync your token vault across devices"
                icon = AppCompatResources.getDrawable(context, R.drawable.ic_accounts)
                isIconSpaceReserved = true
                setOnPreferenceClickListener {
                    val intent = Intent(context, AuthGateActivity::class.java)
                    startActivity(intent)
                    true
                }
            }
            loginCategory.addPreference(loginPref)
        }

        // 2. LOCAL ACCOUNTS PENDING SYNC (Only show if local-only accounts exist)
        if (localAccounts.isNotEmpty()) {
            val localCategory = PreferenceCategory(context).apply {
                title = "LOCAL ACCOUNTS PENDING SYNC (${localAccounts.size})"
                layoutResource = R.layout.preference_material_category
                isIconSpaceReserved = false
            }
            screen.addPreference(localCategory)

            val syncLocalPref = Preference(context).apply {
                layoutResource = R.layout.preference_material_single
                key = "pref_sync_local"
                title = "Sync Local Accounts to Cloud"
                summary = "Upload ${localAccounts.size} account(s) to cloud vault"
                icon = AppCompatResources.getDrawable(context, R.drawable.ic_sync)
                isIconSpaceReserved = true
                setOnPreferenceClickListener {
                    view?.let { v -> Snackbar.make(v, "Syncing local accounts...", Snackbar.LENGTH_SHORT).show() }
                    BackendSyncManager.syncAccountsBatch(context, localAccounts) { success, err ->
                        val msg = if (success) "✓ Successfully synced local accounts!" else BackendSyncManager.sanitizeErrorMessage(err)
                        view?.let { v -> Snackbar.make(v, msg, Snackbar.LENGTH_LONG).show() }
                        refreshSyncDashboard(force = true)
                    }
                    true
                }
            }
            localCategory.addPreference(syncLocalPref)

            localAccounts.forEach { account ->
                val activeToken = if (!account.aasToken.isNullOrEmpty()) account.aasToken!! else account.masterToken
                val cardPref = SyncAccountCardPreference(
                    context = context,
                    account = account,
                    obfuscatedToken = obfuscateToken(activeToken),
                    circleAvatar = getCircleAvatar(account),
                    onResyncAccount = {
                        view?.let { v -> Snackbar.make(v, "Syncing ${account.email}...", Snackbar.LENGTH_SHORT).show() }
                        BackendSyncManager.syncAccount(context, account) { success, err ->
                            val msg = if (success) "✓ Synced ${account.email}!" else BackendSyncManager.sanitizeErrorMessage(err)
                            view?.let { v -> Snackbar.make(v, msg, Snackbar.LENGTH_LONG).show() }
                            refreshSyncDashboard(force = true)
                        }
                    }
                )
                localCategory.addPreference(cardPref)
            }
        }

        // 3. APPEARANCE (Inline Theme Selector Panel)
        val appearanceCategory = PreferenceCategory(context).apply {
            title = "APPEARANCE"
            layoutResource = R.layout.preference_material_category
            isIconSpaceReserved = false
        }
        screen.addPreference(appearanceCategory)

        val themeSelectorPref = ThemeSelectorPreference(context)
        appearanceCategory.addPreference(themeSelectorPref)

        // 4. ABOUT
        val aboutCategory = PreferenceCategory(context).apply {
            title = "ABOUT"
            layoutResource = R.layout.preference_material_category
            isIconSpaceReserved = false
        }
        screen.addPreference(aboutCategory)

        val versionPref = AboutPreference(context)
        aboutCategory.addPreference(versionPref)

        // 5. SIGN OUT (Bottom Red Pill Button)
        if (BackendSyncManager.isLoggedIn(context)) {
            val signOutCategory = PreferenceCategory(context).apply {
                layoutResource = R.layout.preference_material_category
                isIconSpaceReserved = false
            }
            screen.addPreference(signOutCategory)

            val redPillPref = SignOutPillPreference(context) {
                MaterialAlertDialogBuilder(context)
                    .setTitle("Sign Out")
                    .setMessage("Are you sure you want to sign out? Your cloud vault remains safely preserved.")
                    .setPositiveButton("Sign Out") { _, _ ->
                        // Debounced/forced auto sync before sign-out
                        BackendSyncManager.triggerAutoSync(context, force = true) {
                            BackendSyncManager.signOut(context)
                            val intent = Intent(context, AuthGateActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(intent)
                            activity?.finish()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            signOutCategory.addPreference(redPillPref)
        }
    }

    inner class ThemeSelectorPreference(
        context: Context
    ) : Preference(context) {
        init {
            layoutResource = R.layout.preference_theme_selector
            key = "pref_theme_selector"
            isSelectable = false
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val itemView = holder.itemView
            val summaryView = itemView.findViewById<TextView>(R.id.theme_summary)
            val toggleGroup = itemView.findViewById<MaterialButtonToggleGroup>(R.id.theme_toggle_group)

            fun updateSummary(mode: String) {
                summaryView?.text = when (mode) {
                    ThemeManager.THEME_LIGHT -> "Light"
                    ThemeManager.THEME_DARK -> "Dark"
                    else -> "System default"
                }
            }

            val currentMode = ThemeManager.getThemeMode(context)
            updateSummary(currentMode)

            val checkedId = when (currentMode) {
                ThemeManager.THEME_LIGHT -> R.id.btn_theme_light
                ThemeManager.THEME_DARK -> R.id.btn_theme_dark
                else -> R.id.btn_theme_system
            }

            toggleGroup?.clearOnButtonCheckedListeners()
            toggleGroup?.check(checkedId)

            toggleGroup?.addOnButtonCheckedListener { _, id, isChecked ->
                if (isChecked) {
                    val targetMode = when (id) {
                        R.id.btn_theme_light -> ThemeManager.THEME_LIGHT
                        R.id.btn_theme_dark -> ThemeManager.THEME_DARK
                        else -> ThemeManager.THEME_SYSTEM
                    }
                    if (targetMode != ThemeManager.getThemeMode(context)) {
                        ThemeManager.setThemeMode(context, targetMode)
                        updateSummary(targetMode)
                        activity?.recreate()
                    }
                }
            }
        }
    }

    inner class AboutPreference(context: Context) : Preference(context) {
        init {
            layoutResource = R.layout.preference_material_single
            key = "pref_app_version"
            title = "TokenG"
            summary = "Version ${org.tokeng.gms.BuildConfig.VERSION_NAME} (Build ${org.tokeng.gms.BuildConfig.VERSION_CODE})"
            isIconSpaceReserved = true
            isSelectable = false
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val iconView = holder.itemView.findViewById<ImageView>(android.R.id.icon)
            if (iconView != null) {
                // Clear any layout-level colorOnSurfaceVariant tint so the official Google colors shine untinted
                iconView.imageTintList = null
                ImageViewCompat.setImageTintList(iconView, null)

                val size = (44 * context.resources.displayMetrics.density).toInt()
                val lp = iconView.layoutParams
                if (lp != null) {
                    lp.width = size
                    lp.height = size
                    iconView.layoutParams = lp
                }
                iconView.setImageDrawable(AppCompatResources.getDrawable(context, R.mipmap.ic_launcher))
            }
        }
    }

    inner class CleanEmailPreference(
        context: Context,
        private val email: String
    ) : Preference(context) {
        init {
            layoutResource = R.layout.preference_clean_email
            key = "pref_clean_email"
            isSelectable = false
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val textEmail = holder.itemView.findViewById<TextView>(R.id.text_user_email)
            val textStatus = holder.itemView.findViewById<TextView>(R.id.text_connection_status)
            textEmail?.text = email
            textStatus?.text = "Connected • Auto-sync active"
        }
    }

    inner class SignOutPillPreference(
        context: Context,
        private val onSignOut: () -> Unit
    ) : Preference(context) {
        init {
            layoutResource = R.layout.preference_red_pill_sign_out
            key = "pref_red_pill_sign_out"
            isSelectable = false
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            holder.itemView.findViewById<View>(R.id.btn_sign_out_pill)?.setOnClickListener {
                onSignOut()
            }
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
