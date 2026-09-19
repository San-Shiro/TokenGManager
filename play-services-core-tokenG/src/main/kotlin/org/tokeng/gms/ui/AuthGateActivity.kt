/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tokeng.gms.ui

import android.content.Intent
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.tokeng.gms.R
import org.tokeng.gms.crypto.TokenCryptoManager
import org.tokeng.gms.database.TokenDatabase
import org.tokeng.gms.sync.BackendSyncManager
import org.tokeng.gms.tokenmanager.ui.TokenManagerActivity

class AuthGateActivity : AppCompatActivity() {

    private lateinit var layoutHeader: View
    private lateinit var tvNetworkStatus: TextView
    private lateinit var layoutAuthCard: LinearLayout
    private lateinit var layoutTabs: LinearLayout
    private lateinit var tabSignIn: TextView
    private lateinit var tabRegister: TextView
    private lateinit var tilEmail: TextInputLayout
    private lateinit var etEmail: TextInputEditText
    private lateinit var tilPassword: TextInputLayout
    private lateinit var etPassword: TextInputEditText
    private lateinit var tilConfirmPassword: TextInputLayout
    private lateinit var etConfirmPassword: TextInputEditText
    private lateinit var btnPrimaryAuth: Button
    private lateinit var progressAuth: ProgressBar
    private lateinit var tvAuthError: TextView

    private lateinit var layoutReturnLocalMode: View
    private lateinit var tvReturnLocalModeTitle: TextView
    private lateinit var switchReturnLocalMode: com.google.android.material.materialswitch.MaterialSwitch

    private lateinit var layoutOfflineUnlockSection: LinearLayout
    private lateinit var tilOfflinePassword: TextInputLayout
    private lateinit var etOfflinePassword: TextInputEditText
    private lateinit var btnUnlockOffline: Button

    private lateinit var layoutOfflineBlockedSection: LinearLayout
    private lateinit var btnRetryConnectivity: Button

    private lateinit var layoutLoadingScreen: LinearLayout

    private var isRegisterMode = false
    private var currentReachability = BackendSyncManager.Reachability.OFFLINE
    private var isNavigating = false

    companion object {
        const val EXTRA_CONNECT_CLOUD = "org.tokeng.gms.extra.CONNECT_CLOUD"
        const val EXTRA_START_REGISTER = "org.tokeng.gms.extra.START_REGISTER"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_auth_gate)

        initViews()
        setupListeners()
        startEntranceAnimations()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val isConnectingCloud = intent?.getBooleanExtra(EXTRA_CONNECT_CLOUD, false) == true
        // If already unlocked and not explicitly requested to connect to cloud, proceed straight into TokenManager
        if (BackendSyncManager.isUnlocked(this) && !isConnectingCloud) {
            navigateToDashboard()
            return
        }
        isNavigating = false
        if (intent?.getBooleanExtra(EXTRA_START_REGISTER, false) == true && !isRegisterMode) {
            isRegisterMode = true
            animateTabSwitch(toRegister = true)
        }
        checkConnectivity()
    }

    private fun initViews() {
        layoutHeader = findViewById(R.id.layout_header)
        tvNetworkStatus = findViewById(R.id.tv_network_status)
        layoutAuthCard = findViewById(R.id.layout_auth_card)
        layoutTabs = findViewById(R.id.layout_tabs)
        tabSignIn = findViewById(R.id.tab_sign_in)
        tabRegister = findViewById(R.id.tab_register)
        tilEmail = findViewById(R.id.til_email)
        etEmail = findViewById(R.id.et_email)
        tilPassword = findViewById(R.id.til_password)
        etPassword = findViewById(R.id.et_password)
        tilConfirmPassword = findViewById(R.id.til_confirm_password)
        etConfirmPassword = findViewById(R.id.et_confirm_password)
        btnPrimaryAuth = findViewById(R.id.btn_primary_auth)
        progressAuth = findViewById(R.id.progress_auth)
        tvAuthError = findViewById(R.id.tv_auth_error)

        layoutReturnLocalMode = findViewById(R.id.layout_return_local_mode)
        tvReturnLocalModeTitle = findViewById(R.id.tv_return_local_mode_title)
        switchReturnLocalMode = findViewById(R.id.switch_return_local_mode)

        layoutOfflineUnlockSection = findViewById(R.id.layout_offline_unlock_section)
        tilOfflinePassword = findViewById(R.id.til_offline_password)
        etOfflinePassword = findViewById(R.id.et_offline_password)
        btnUnlockOffline = findViewById(R.id.btn_unlock_offline)

        layoutOfflineBlockedSection = findViewById(R.id.layout_offline_blocked_section)
        btnRetryConnectivity = findViewById(R.id.btn_retry_connectivity)

        layoutLoadingScreen = findViewById(R.id.layout_loading_screen)

        findViewById<TextView>(R.id.tv_app_version)?.text = "TokenG v${org.tokeng.gms.BuildConfig.VERSION_NAME} (Build ${org.tokeng.gms.BuildConfig.VERSION_CODE})"
    }

    private fun startEntranceAnimations() {
        layoutHeader.alpha = 0f
        layoutHeader.translationY = -30f
        layoutHeader.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(450)
            .setInterpolator(DecelerateInterpolator())
            .start()

        layoutAuthCard.alpha = 0f
        layoutAuthCard.translationY = 50f
        layoutAuthCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(500)
            .setStartDelay(100)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun setupListeners() {
        tabSignIn.setOnClickListener {
            if (isRegisterMode) {
                isRegisterMode = false
                animateTabSwitch(toRegister = false)
            }
        }

        tabRegister.setOnClickListener {
            if (!isRegisterMode) {
                isRegisterMode = true
                animateTabSwitch(toRegister = true)
            }
        }

        btnPrimaryAuth.setOnClickListener {
            handlePrimaryAuth()
        }

        val onReturnLocalModeClick = View.OnClickListener {
            returnToLocalMode()
        }
        layoutReturnLocalMode.setOnClickListener(onReturnLocalModeClick)
        switchReturnLocalMode.setOnClickListener(onReturnLocalModeClick)

        btnUnlockOffline.setOnClickListener {
            handleOfflineUnlock()
        }

        btnRetryConnectivity.setOnClickListener {
            checkConnectivity()
        }
    }

    private fun animateTabSwitch(toRegister: Boolean) {
        TransitionManager.beginDelayedTransition(
            layoutAuthCard,
            AutoTransition().apply {
                duration = 240
                interpolator = AccelerateDecelerateInterpolator()
            }
        )

        val onPrimaryColor = MaterialColors.getColor(this, R.attr.colorOnPrimary, 0xFFFFFFFF.toInt())
        val secondaryTextColor = MaterialColors.getColor(this, android.R.attr.textColorSecondary, 0xFF64748B.toInt())

        if (toRegister) {
            tabRegister.setBackgroundResource(R.drawable.tab_pill_active)
            tabRegister.setTextColor(onPrimaryColor)
            tabSignIn.setBackgroundResource(R.drawable.tab_pill_inactive)
            tabSignIn.setTextColor(secondaryTextColor)
            tilConfirmPassword.visibility = View.VISIBLE
            btnPrimaryAuth.text = "Create Cloud Account"
        } else {
            tabSignIn.setBackgroundResource(R.drawable.tab_pill_active)
            tabSignIn.setTextColor(onPrimaryColor)
            tabRegister.setBackgroundResource(R.drawable.tab_pill_inactive)
            tabRegister.setTextColor(secondaryTextColor)
            tilConfirmPassword.visibility = View.GONE
            btnPrimaryAuth.text = "Sign In"
        }
        tvAuthError.visibility = View.GONE
    }

    private fun checkConnectivity() {
        tvNetworkStatus.text = "● Connecting..."
        tvNetworkStatus.setTextColor(MaterialColors.getColor(this, R.attr.colorPrimary, 0xFF38BDF8.toInt()))

        BackendSyncManager.checkConnectivity(this) { reachability ->
            currentReachability = reachability
            applyReachabilityState(reachability)
        }
    }

    private fun applyReachabilityState(reachability: BackendSyncManager.Reachability) {
        val isConnectingCloud = intent?.getBooleanExtra(EXTRA_CONNECT_CLOUD, false) == true
        layoutReturnLocalMode.visibility = View.VISIBLE
        if (isConnectingCloud || BackendSyncManager.isLocalMode(this)) {
            tvReturnLocalModeTitle.text = "Return to Local Mode"
            switchReturnLocalMode.isChecked = true
        } else {
            tvReturnLocalModeTitle.text = "Use Local Mode"
            switchReturnLocalMode.isChecked = false
        }
        layoutOfflineUnlockSection.visibility = View.GONE
        layoutOfflineBlockedSection.visibility = View.GONE

        when (reachability) {
            BackendSyncManager.Reachability.ONLINE_SERVER_UP -> {
                tvNetworkStatus.visibility = View.GONE
                layoutAuthCard.visibility = View.VISIBLE
            }
            BackendSyncManager.Reachability.ONLINE_SERVER_DOWN -> {
                tvNetworkStatus.text = "● Cloud Service Temporarily Offline"
                tvNetworkStatus.setTextColor(0xFFF59E0B.toInt())
                tvNetworkStatus.visibility = View.VISIBLE
                layoutAuthCard.visibility = View.VISIBLE
            }
            BackendSyncManager.Reachability.OFFLINE -> {
                tvNetworkStatus.text = "● No Internet Connection"
                tvNetworkStatus.setTextColor(0xFFEF4444.toInt())
                tvNetworkStatus.visibility = View.VISIBLE
                layoutAuthCard.visibility = View.VISIBLE
            }
        }
    }

    private fun handlePrimaryAuth() {
        if (currentReachability == BackendSyncManager.Reachability.OFFLINE) {
            showError("No internet connection. Please use Local Mode to manage tokens offline.")
            return
        }

        val email = etEmail.text?.toString()?.trim() ?: ""
        val password = etPassword.text?.toString() ?: ""

        if (email.isEmpty() || !email.lowercase().endsWith("@gmail.com") || email.substringBefore("@gmail.com").isBlank()) {
            showError("Please enter a valid @gmail.com address.")
            return
        }
        if (password.length < 6) {
            showError("Password must be at least 6 characters.")
            return
        }

        if (isRegisterMode) {
            val confirmPassword = etConfirmPassword.text?.toString() ?: ""
            if (password != confirmPassword) {
                showError("Passwords do not match.")
                return
            }
        }

        setLoading(true)
        val callback: (Boolean, String?) -> Unit = { success, msg ->
            if (success) {
                // Initialize/unlock local crypto vault with user password
                val pwdChars = password.toCharArray()
                val vaultOk = if (isRegisterMode) {
                    TokenCryptoManager.wipeVault(this)
                    TokenCryptoManager.initializeVault(this, pwdChars)
                } else {
                    if (TokenCryptoManager.isVaultInitialized(this)) {
                        if (!TokenCryptoManager.unlockVault(this, pwdChars)) {
                            // Re-initialize local vault with validated credentials
                            TokenCryptoManager.wipeVault(this)
                            TokenCryptoManager.initializeVault(this, pwdChars)
                        } else {
                            true
                        }
                    } else {
                        TokenCryptoManager.initializeVault(this, pwdChars)
                    }
                }

                if (!vaultOk || !TokenCryptoManager.isUnlocked()) {
                    setLoading(false)
                    showError("Failed to initialize security vault. Please try again.")
                } else {
                    BackendSyncManager.setLocalMode(this, false)

                    // Fade out auth form and display sleek loading screen
                    layoutReturnLocalMode.visibility = View.GONE
                    layoutAuthCard.animate().alpha(0f).setDuration(200).withEndAction {
                        layoutAuthCard.visibility = View.GONE
                        layoutLoadingScreen.alpha = 0f
                        layoutLoadingScreen.visibility = View.VISIBLE
                        layoutLoadingScreen.animate().alpha(1f).setDuration(250).start()
                    }.start()

                    // Smooth transition straight to home dashboard
                    layoutLoadingScreen.postDelayed({
                        navigateToDashboard()
                    }, 500)
                }
            } else {
                setLoading(false)
                showError(BackendSyncManager.sanitizeErrorMessage(msg, isRegisterMode))
            }
        }

        if (isRegisterMode) {
            BackendSyncManager.register(this, email, password, callback)
        } else {
            BackendSyncManager.login(this, email, password, callback)
        }
    }

    private fun promptLocalModePassword() {
        val isInitialized = TokenCryptoManager.isVaultInitialized(this)
        val title = if (isInitialized) "Unlock Local Vault" else "Create Local Vault"
        val message = if (isInitialized) {
            "Enter your vault password to access your local tokens."
        } else {
            "Set a password (minimum 4 characters) to encrypt and protect your local tokens on this device."
        }
        val btnLabel = if (isInitialized) "Unlock Vault" else "Create & Enter"

        val til = TextInputLayout(this).apply {
            hint = if (isInitialized) "Local Vault Password" else "Set Vault Password"
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val input = TextInputEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        til.addView(input)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(til)
            .setPositiveButton(btnLabel, null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val password = input.text?.toString() ?: ""
            if (password.length < 4) {
                til.error = "Password must be at least 4 characters."
                return@setOnClickListener
            }
            til.error = null
            dialog.dismiss()
            enterLocalMode(password)
        }
    }

    private fun returnToLocalMode() {
        BackendSyncManager.setLocalMode(this, true)
        switchReturnLocalMode.isChecked = true
        val isConnectingCloud = intent?.getBooleanExtra(EXTRA_CONNECT_CLOUD, false) == true
        if (isConnectingCloud && !isTaskRoot) {
            finish()
        } else if (TokenCryptoManager.isUnlocked()) {
            navigateToDashboard()
        } else {
            val pwd = etPassword.text?.toString() ?: ""
            if (pwd.length >= 4) {
                enterLocalMode(pwd)
            } else if (TokenCryptoManager.isVaultInitialized(this)) {
                promptLocalModePassword()
            } else {
                enterLocalMode("tokeng_default_offline_key")
            }
        }
    }

    private fun enterLocalMode(password: String) {
        val pwdChars = password.toCharArray()
        try {
            val unlocked = if (TokenCryptoManager.isVaultInitialized(this)) {
                TokenCryptoManager.unlockVault(this, pwdChars)
            } else {
                TokenCryptoManager.initializeVault(this, pwdChars)
            }

            if (unlocked) {
                BackendSyncManager.setLocalMode(this, true)
                navigateToDashboard()
            } else {
                showError("Incorrect password. Unable to unlock local vault.")
            }
        } finally {
            pwdChars.fill('\u0000')
        }
    }

    private fun handleOfflineUnlock() {
        val password = etOfflinePassword.text?.toString() ?: ""
        if (password.isEmpty()) {
            showError("Please enter your vault password.")
            return
        }

        val unlocked = TokenCryptoManager.unlockVault(this, password.toCharArray())
        if (unlocked) {
            BackendSyncManager.setLocalMode(this, true)
            navigateToDashboard()
        } else {
            showError("Incorrect password. Unable to unlock local vault.")
        }
    }

    private fun navigateToDashboard() {
        if (isNavigating) return
        isNavigating = true
        val intent = Intent(this, TokenManagerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun setLoading(loading: Boolean) {
        progressAuth.visibility = if (loading) View.VISIBLE else View.GONE
        btnPrimaryAuth.isEnabled = !loading
        layoutReturnLocalMode.isEnabled = !loading
        switchReturnLocalMode.isEnabled = !loading
        if (loading) tvAuthError.visibility = View.GONE
    }

    private fun showError(msg: String) {
        tvAuthError.text = msg
        tvAuthError.visibility = View.VISIBLE
    }
}
