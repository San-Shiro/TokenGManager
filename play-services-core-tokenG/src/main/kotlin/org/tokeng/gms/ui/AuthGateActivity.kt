/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tokeng.gms.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.tokeng.gms.R
import org.tokeng.gms.crypto.TokenCryptoManager
import org.tokeng.gms.sync.BackendSyncManager
import org.tokeng.gms.tokenmanager.ui.TokenManagerActivity

class AuthGateActivity : AppCompatActivity() {

    private lateinit var tvNetworkStatus: TextView
    private lateinit var layoutAuthCard: LinearLayout
    private lateinit var layoutTabs: LinearLayout
    private lateinit var tabSignIn: TextView
    private lateinit var tabRegister: TextView
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var etConfirmPassword: EditText
    private lateinit var btnPrimaryAuth: Button
    private lateinit var progressAuth: ProgressBar
    private lateinit var tvAuthError: TextView

    private lateinit var layoutLocalModeSection: LinearLayout
    private lateinit var btnEnterLocalMode: Button

    private lateinit var layoutOfflineUnlockSection: LinearLayout
    private lateinit var etOfflinePassword: EditText
    private lateinit var btnUnlockOffline: Button

    private lateinit var layoutOfflineBlockedSection: LinearLayout
    private lateinit var btnRetryConnectivity: Button

    private var isRegisterMode = false
    private var currentReachability = BackendSyncManager.Reachability.OFFLINE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_auth_gate)

        initViews()
        setupListeners()
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
        tvNetworkStatus = findViewById(R.id.tv_network_status)
        layoutAuthCard = findViewById(R.id.layout_auth_card)
        layoutTabs = findViewById(R.id.layout_tabs)
        tabSignIn = findViewById(R.id.tab_sign_in)
        tabRegister = findViewById(R.id.tab_register)
        etEmail = findViewById(R.id.et_email)
        etPassword = findViewById(R.id.et_password)
        etConfirmPassword = findViewById(R.id.et_confirm_password)
        btnPrimaryAuth = findViewById(R.id.btn_primary_auth)
        progressAuth = findViewById(R.id.progress_auth)
        tvAuthError = findViewById(R.id.tv_auth_error)

        layoutLocalModeSection = findViewById(R.id.layout_local_mode_section)
        btnEnterLocalMode = findViewById(R.id.btn_enter_local_mode)

        layoutOfflineUnlockSection = findViewById(R.id.layout_offline_unlock_section)
        etOfflinePassword = findViewById(R.id.et_offline_password)
        btnUnlockOffline = findViewById(R.id.btn_unlock_offline)

        layoutOfflineBlockedSection = findViewById(R.id.layout_offline_blocked_section)
        btnRetryConnectivity = findViewById(R.id.btn_retry_connectivity)
    }

    private fun setupListeners() {
        tabSignIn.setOnClickListener {
            if (isRegisterMode) {
                isRegisterMode = false
                tabSignIn.setBackgroundColor(0xFF1E293B.toInt())
                tabSignIn.setTextColor(0xFFFFFFFF.toInt())
                tabRegister.setBackgroundColor(0x00000000)
                tabRegister.setTextColor(0xFF64748B.toInt())
                etConfirmPassword.visibility = View.GONE
                btnPrimaryAuth.text = "Sign In to Cloud"
                tvAuthError.visibility = View.GONE
            }
        }

        tabRegister.setOnClickListener {
            if (!isRegisterMode) {
                isRegisterMode = true
                tabRegister.setBackgroundColor(0xFF1E293B.toInt())
                tabRegister.setTextColor(0xFFFFFFFF.toInt())
                tabSignIn.setBackgroundColor(0x00000000)
                tabSignIn.setTextColor(0xFF64748B.toInt())
                etConfirmPassword.visibility = View.VISIBLE
                btnPrimaryAuth.text = "Create Cloud Account"
                tvAuthError.visibility = View.GONE
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

    private fun checkConnectivity() {
        tvNetworkStatus.text = "● Checking connectivity..."
        tvNetworkStatus.setTextColor(0xFF38BDF8.toInt())

        BackendSyncManager.checkConnectivity(this) { reachability ->
            currentReachability = reachability
            applyReachabilityState(reachability)
        }
    }

    private fun applyReachabilityState(reachability: BackendSyncManager.Reachability) {
        when (reachability) {
            BackendSyncManager.Reachability.ONLINE_SERVER_UP -> {
                tvNetworkStatus.text = "● Cloud Connected (tokeng.sanshiro.qzz.io)"
                tvNetworkStatus.setTextColor(0xFF10B981.toInt())
                layoutAuthCard.visibility = View.VISIBLE
                layoutLocalModeSection.visibility = View.GONE
                layoutOfflineUnlockSection.visibility = View.GONE
                layoutOfflineBlockedSection.visibility = View.GONE
            }
            BackendSyncManager.Reachability.ONLINE_SERVER_DOWN -> {
                tvNetworkStatus.text = "⚠ Upstream Server Inaccessible (Local Mode Available)"
                tvNetworkStatus.setTextColor(0xFFF59E0B.toInt())
                layoutAuthCard.visibility = View.VISIBLE
                layoutLocalModeSection.visibility = View.VISIBLE
                layoutOfflineUnlockSection.visibility = View.GONE
                layoutOfflineBlockedSection.visibility = View.GONE
            }
            BackendSyncManager.Reachability.OFFLINE -> {
                tvNetworkStatus.text = "✕ Offline (No Internet Connection)"
                tvNetworkStatus.setTextColor(0xFFEF4444.toInt())
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
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()

        if (email.isEmpty() || !email.contains("@")) {
            showError("Please enter a valid Gmail address.")
            return
        }
        if (password.length < 6) {
            showError("Password must be at least 6 characters.")
            return
        }

        if (isRegisterMode) {
            val confirmPassword = etConfirmPassword.text.toString()
            if (password != confirmPassword) {
                showError("Passwords do not match.")
                return
            }
        }

        setLoading(true)
        val callback = { success: Boolean, msg: String? ->
            setLoading(false)
            if (success) {
                // Initialize/unlock local crypto vault with master password
                val pwdChars = password.toCharArray()
                if (!TokenCryptoManager.isVaultInitialized(this)) {
                    TokenCryptoManager.initializeVault(this, pwdChars)
                } else {
                    TokenCryptoManager.unlockVault(this, pwdChars)
                }
                BackendSyncManager.setLocalMode(this, false)
                navigateToDashboard()
            } else {
                showError(msg ?: "Authentication failed. Check your credentials.")
            }
        }

        if (isRegisterMode) {
            BackendSyncManager.register(this, email, password, callback)
        } else {
            BackendSyncManager.login(this, email, password, callback)
        }
    }

    private fun promptLocalModePassword() {
        val input = EditText(this).apply {
            hint = "Unlocking Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 32, 48, 32)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Enter Local Mode")
            .setMessage("The upstream server is down. You can manage tokens locally on this device.\n\n⚠️ Notice: Connecting to a cloud account later will replace your local vault.")
            .setView(input)
            .setPositiveButton("Enter Local Mode") { _, _ ->
                val password = input.text.toString()
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
        val password = etOfflinePassword.text.toString()
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
        tvAuthError.text = msg
        tvAuthError.visibility = View.VISIBLE
    }
}
