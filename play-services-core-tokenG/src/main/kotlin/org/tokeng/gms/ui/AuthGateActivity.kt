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

    private lateinit var layoutLocalModeSection: LinearLayout
    private lateinit var btnEnterLocalMode: Button

    private lateinit var layoutOfflineUnlockSection: LinearLayout
    private lateinit var tilOfflinePassword: TextInputLayout
    private lateinit var etOfflinePassword: TextInputEditText
    private lateinit var btnUnlockOffline: Button

    private lateinit var layoutOfflineBlockedSection: LinearLayout
    private lateinit var btnRetryConnectivity: Button

    private lateinit var layoutLoadingScreen: LinearLayout

    private var isRegisterMode = false
    private var currentReachability = BackendSyncManager.Reachability.OFFLINE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_auth_gate)

        initViews()
        setupListeners()
        startEntranceAnimations()
    }

    override fun onResume() {
        super.onResume()
        // If already unlocked, proceed straight into TokenManager
        if (BackendSyncManager.isUnlocked(this)) {
            navigateToDashboard()
            return
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

        layoutLocalModeSection = findViewById(R.id.layout_local_mode_section)
        btnEnterLocalMode = findViewById(R.id.btn_enter_local_mode)

        layoutOfflineUnlockSection = findViewById(R.id.layout_offline_unlock_section)
        tilOfflinePassword = findViewById(R.id.til_offline_password)
        etOfflinePassword = findViewById(R.id.et_offline_password)
        btnUnlockOffline = findViewById(R.id.btn_unlock_offline)

        layoutOfflineBlockedSection = findViewById(R.id.layout_offline_blocked_section)
        btnRetryConnectivity = findViewById(R.id.btn_retry_connectivity)

        layoutLoadingScreen = findViewById(R.id.layout_loading_screen)
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

        btnEnterLocalMode.setOnClickListener {
            promptLocalModePassword()
        }

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
        when (reachability) {
            BackendSyncManager.Reachability.ONLINE_SERVER_UP -> {
                tvNetworkStatus.visibility = View.GONE
                layoutAuthCard.visibility = View.VISIBLE
                layoutLocalModeSection.visibility = View.GONE
                layoutOfflineUnlockSection.visibility = View.GONE
                layoutOfflineBlockedSection.visibility = View.GONE
            }
            BackendSyncManager.Reachability.ONLINE_SERVER_DOWN -> {
                tvNetworkStatus.text = "● Server Temporarily Offline"
                tvNetworkStatus.setTextColor(0xFFF59E0B.toInt())
                tvNetworkStatus.visibility = View.VISIBLE
                layoutAuthCard.visibility = View.VISIBLE
                layoutLocalModeSection.visibility = View.VISIBLE
                layoutOfflineUnlockSection.visibility = View.GONE
                layoutOfflineBlockedSection.visibility = View.GONE
            }
            BackendSyncManager.Reachability.OFFLINE -> {
                tvNetworkStatus.text = "● No Internet Connection"
                tvNetworkStatus.setTextColor(0xFFEF4444.toInt())
                tvNetworkStatus.visibility = View.VISIBLE
                layoutAuthCard.visibility = View.GONE
                layoutLocalModeSection.visibility = View.GONE

                if (TokenCryptoManager.isVaultInitialized(this)) {
                    layoutOfflineUnlockSection.visibility = View.VISIBLE
                    layoutOfflineBlockedSection.visibility = View.GONE
                } else {
                    layoutOfflineUnlockSection.visibility = View.GONE
                    layoutOfflineBlockedSection.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun handlePrimaryAuth() {
        val email = etEmail.text?.toString()?.trim() ?: ""
        val password = etPassword.text?.toString() ?: ""

        if (email.isEmpty() || !email.contains("@")) {
            showError("Please enter a valid Gmail address.")
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
                // Fade out auth form and display sleek loading screen
                layoutAuthCard.animate().alpha(0f).setDuration(200).withEndAction {
                    layoutAuthCard.visibility = View.GONE
                    layoutLoadingScreen.alpha = 0f
                    layoutLoadingScreen.visibility = View.VISIBLE
                    layoutLoadingScreen.animate().alpha(1f).setDuration(250).start()
                }.start()

                // Initialize/unlock local crypto vault with master password
                val pwdChars = password.toCharArray()
                if (!TokenCryptoManager.isVaultInitialized(this)) {
                    TokenCryptoManager.initializeVault(this, pwdChars)
                } else {
                    TokenCryptoManager.unlockVault(this, pwdChars)
                }
                BackendSyncManager.setLocalMode(this, false)

                // Smooth transition straight to home dashboard
                layoutLoadingScreen.postDelayed({
                    navigateToDashboard()
                }, 500)
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
        val til = TextInputLayout(this).apply {
            hint = "Local Vault Password"
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val input = TextInputEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        til.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle("Enter Local Mode")
            .setMessage("Server is temporarily offline. Access and manage your tokens locally on this device.")
            .setView(til)
            .setPositiveButton("Enter Local Mode") { _, _ ->
                val password = input.text?.toString() ?: ""
                if (password.length < 4) {
                    showError("Local password must be at least 4 characters.")
                    return@setPositiveButton
                }
                val pwdChars = password.toCharArray()
                val unlocked = if (TokenCryptoManager.isVaultInitialized(this)) {
                    TokenCryptoManager.unlockVault(this, pwdChars)
                } else {
                    TokenCryptoManager.initializeVault(this, pwdChars)
                }

                if (unlocked) {
                    BackendSyncManager.setLocalMode(this, true)
                    navigateToDashboard()
                } else {
                    showError("Incorrect local unlocking password.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        val intent = Intent(this, TokenManagerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun setLoading(loading: Boolean) {
        progressAuth.visibility = if (loading) View.VISIBLE else View.GONE
        btnPrimaryAuth.isEnabled = !loading
        if (loading) tvAuthError.visibility = View.GONE
    }

    private fun showError(msg: String) {
        tvAuthError.text = BackendSyncManager.sanitizeErrorMessage(msg, isRegisterMode)
        tvAuthError.visibility = View.VISIBLE
    }
}
