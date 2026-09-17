/*
 * SPDX-FileCopyrightText: 2024 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tokeng.gms.ui

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
// import com.google.android.gms.R
import org.tokeng.gms.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tokeng.gms.account.AccountPreference
import org.microg.gms.auth.AuthConstants
import org.tokeng.gms.auth.login.LoginActivity
import org.tokeng.gms.database.TokenAccount
import org.tokeng.gms.database.TokenDatabase
import org.tokeng.gms.profile.MultiDeviceRegistry
import org.tokeng.gms.sync.BackendSyncManager
// import org.microg.gms.people.DatabaseHelper
// import org.microg.gms.people.PeopleManager

class AccountsFragment : PreferenceFragmentCompat() {

    private val tag = "AccountsFragment"
    private lateinit var fab: ExtendedFloatingActionButton

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_accounts)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setBackgroundColor(MaterialColors.getColor(view, android.R.attr.colorBackground))

        addAccountFab()
        setupPreferenceListeners()
    }

    override fun onStart() {
        super.onStart()
        fab.show()
    }

    override fun onStop() {
        super.onStop()
        fab.hide()
    }

    override fun onResume() {
        super.onResume()
        refreshAccountSettings()
        fab.show()
    }

    private fun setupPreferenceListeners() {
        findPreference<Preference>("pref_sync_all")?.setOnPreferenceClickListener {
            val rootView = view ?: return@setOnPreferenceClickListener true
            Snackbar.make(rootView, "Syncing all accounts to database...", Snackbar.LENGTH_SHORT).show()
            BackendSyncManager.syncAllAccounts(requireContext()) { successCount, failCount ->
                val msg = if (failCount == 0) {
                    "✓ Synced $successCount account(s) to database!"
                } else {
                    "Synced $successCount account(s), $failCount failed"
                }
                Snackbar.make(rootView, msg, Snackbar.LENGTH_LONG).show()
                refreshAccountSettings()
            }
            true
        }

        findPreference<Preference>("pref_backend_url")?.setOnPreferenceClickListener {
            showBackendUrlDialog()
            true
        }

        findPreference<PreferenceCategory>("prefcat_account_settings")?.isVisible = false
        findPreference<Preference>("pref_manage_accounts")?.isVisible = false
        findPreference<Preference>("pref_manage_history")?.setOnPreferenceClickListener {
            openUrl("https://myactivity.google.com/product/youtube")
            true
        }
        findPreference<Preference>("pref_your_data")?.setOnPreferenceClickListener {
            openUrl("https://myaccount.google.com/yourdata/youtube")
            true
        }
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
                    refreshAccountSettings()
                    view?.let {
                        Snackbar.make(it, "Backend URL updated: $newUrl", Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshAccountSettings() {
        val context = requireContext()
        val accounts = TokenDatabase.getInstance(context).getAllAccounts()

        findPreference<Preference>("pref_backend_url")?.summary = BackendSyncManager.getBackendUrl(context)

        val category = findPreference<PreferenceCategory>("prefcat_current_accounts") ?: return
        category.removeAll()
        category.isVisible = accounts.isNotEmpty()

        accounts.forEachIndexed { index, account ->
            val displayName = when {
                !account.firstName.isNullOrEmpty() && !account.lastName.isNullOrEmpty() -> "${account.firstName} ${account.lastName}"
                !account.firstName.isNullOrEmpty() -> account.firstName
                else -> account.email
            }
            val deviceName = account.deviceName
            val gsfId = account.androidId
            val aasToken = account.aasToken ?: account.masterToken
            val syncStatus = account.syncStatus

            val preference = AccountPreference(context).apply {
                title = displayName
                summary = account.email
                key = "account:${account.email}"
                position = index
                itemCount = accounts.size

                accountAvatar = getCircleDrawable(null)
                deviceBadge = "📱 $deviceName • GSF: ${if (gsfId.length > 8) gsfId.take(8) + "..." else if (gsfId.isNotEmpty()) gsfId else "Pending"}"
                tokenSnippet = if (aasToken.isNotEmpty()) "🔑 ${aasToken.take(18)}... [Tap to Copy]" else "🔑 No Token"
                this.syncStatus = when (syncStatus) {
                    "SYNCED" -> "✓ Synced to DB"
                    "FAILED" -> "⚠ Sync failed (tap sync to retry)"
                    else -> "⏳ Sync pending"
                }

                onCopyTokenListener = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (clipboard != null && aasToken.isNotEmpty()) {
                        val clip = ClipData.newPlainText("AAS Token", aasToken)
                        clipboard.setPrimaryClip(clip)
                        view?.let { v -> Snackbar.make(v, "Copied token to clipboard!", Snackbar.LENGTH_SHORT).show() }
                    }
                }

                onSyncListener = {
                    view?.let { v -> Snackbar.make(v, "Syncing ${account.email}...", Snackbar.LENGTH_SHORT).show() }
                    BackendSyncManager.syncAccount(context, account) { success, err ->
                        val msg = if (success) "✓ Synced ${account.email} to DB!" else "⚠ Sync failed: ${err ?: "unknown"}"
                        view?.let { v -> Snackbar.make(v, msg, Snackbar.LENGTH_LONG).show() }
                        refreshAccountSettings()
                    }
                }

                onRemoveListener = { showRemovalDialog(account) }
            }
            category.addPreference(preference)
        }
    }

    private fun showRemovalDialog(account: TokenAccount) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.account_remove_dialog, null)

        val displayName = when {
            !account.firstName.isNullOrEmpty() && !account.lastName.isNullOrEmpty() -> "${account.firstName} ${account.lastName}"
            !account.firstName.isNullOrEmpty() -> account.firstName
            else -> account.email
        }

        dialogView.findViewById<MaterialTextView>(R.id.account_name).text = displayName
        dialogView.findViewById<MaterialTextView>(R.id.account_email).text = account.email

        dialogView.findViewById<MaterialTextView>(R.id.dialog_title).text = getString(R.string.dialog_title_remove_account)
        dialogView.findViewById<MaterialTextView>(R.id.dialog_remove_message).text = getString(R.string.dialog_message_remove_account)
        dialogView.findViewById<MaterialButton>(R.id.dialog_remove_button).text = getString(R.string.dialog_confirm_button)
        dialogView.findViewById<MaterialButton>(R.id.dialog_cancel_button).text = getString(R.string.dialog_cancel_button)

        val buttonRemove = dialogView.findViewById<MaterialButton>(R.id.dialog_remove_button)
        val buttonCancel = dialogView.findViewById<MaterialButton>(R.id.dialog_cancel_button)

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                dialogView.findViewById<ShapeableImageView>(R.id.account_avatar)
                    .setImageDrawable(getCircleDrawable(null))
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext()).setView(dialogView).create()
        buttonRemove.setOnClickListener {
            removeAccount(account)
            dialog.dismiss()
        }
        buttonCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun removeAccount(account: TokenAccount) {
        val rootView = view ?: return
        var undoRequested = false

        val snack = Snackbar.make(
            rootView,
            getString(R.string.snackbar_remove_account, account.email),
            Snackbar.LENGTH_LONG
        ).setAction(R.string.snackbar_undo_button) { undoRequested = true }

        snack.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (!undoRequested && isAdded) {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        TokenDatabase.getInstance(requireContext()).deleteAccount(account.email)
                        withContext(Dispatchers.Main) { refreshAccountSettings() }
                    }
                }
            }
        })
        snack.show()
    }

    private fun getDisplayName(account: Account): String? {
        // val dbHelper = DatabaseHelper(requireContext())
        return null
        /*
        return try {
            dbHelper.getOwner(account.name).use { cursor ->
                if (cursor.moveToNext()) {
                    val idx = cursor.getColumnIndex("display_name")
                    if (idx >= 0) cursor.getString(idx)?.takeIf { it.isNotBlank() } else null
                } else null
            }
        } catch (_: Exception) {
            null
        } finally {
            dbHelper.close()
        }
        */
    }

    private fun getCircleDrawable(bmp: Bitmap?): Drawable {
        return bmp?.let {
            RoundedBitmapDrawableFactory.create(resources, it).apply { isCircular = true }
        } ?: AppCompatResources.getDrawable(requireContext(), R.drawable.ic_account_avatar)!!
    }

    private fun addAccountFab() {
        fab = requireActivity().findViewById(R.id.preference_fab)
        fab.text = getString(R.string.auth_add_account)
        fab.setIconResource(R.drawable.ic_add)
        fab.setOnClickListener {
            startActivity(Intent(requireContext(), LoginActivity::class.java))
        }
    }


    private fun openUrl(url: String) {
        startActivityIntent(Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    private fun startActivityIntent(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(tag, "Failed to launch intent", e)
        }
    }
}