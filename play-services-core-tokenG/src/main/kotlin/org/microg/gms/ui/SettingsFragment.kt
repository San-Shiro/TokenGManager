/*
 * SPDX-FileCopyrightText: 2020, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.google.android.gms.tokeng.R
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.launch
import org.microg.gms.checkin.CheckinPreferences
import org.microg.gms.ui.settings.SettingsProvider
import org.microg.gms.ui.settings.getAllSettingsProviders
import org.microg.tools.ui.ResourceSettingsFragment

class SettingsFragment : ResourceSettingsFragment() {

    companion object {
        private const val TAG = "SettingsFragment"

        const val PREF_CHECKIN = "pref_checkin"
        const val PREF_ACCOUNTS = "pref_accounts"
        const val PREF_HIDE_LAUNCHER_ICON = "pref_hide_launcher_icon"

        private const val ACTIVITY_LAUNCHER_CONTROL = "org.microg.gms.ui.SettingsActivityLauncher"
    }

    private val createdPreferences = mutableListOf<Preference>()

    init {
        preferencesResource = R.xml.preferences_start
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        setupStaticPreferenceClickListeners()
        updateLauncherIconSwitchState()
        loadStaticEntries()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onResume() {
        super.onResume()
        activity?.findViewById<ExtendedFloatingActionButton>(R.id.preference_fab)?.visibility =
            View.GONE
        updateLauncherIconSwitchState()
        updateCheckinSummary()
        updateDynamicEntries()
    }

    private fun setupStaticPreferenceClickListeners() {
        findPreference<Preference>(PREF_ACCOUNTS)?.setOnPreferenceClickListener {
            findNavController().navigate(requireContext(), R.id.accountManagerFragment)
            true
        }
        findPreference<Preference>(PREF_CHECKIN)?.setOnPreferenceClickListener {
            findNavController().navigate(requireContext(), R.id.openCheckinSettings)
            true
        }
        findPreference<SwitchPreferenceCompat>(PREF_HIDE_LAUNCHER_ICON)?.setOnPreferenceChangeListener { _, newValue ->
            val shouldHide = newValue as Boolean
            toggleLauncherIconVisibility(hide = shouldHide)
            true
        }
    }

    private fun loadStaticEntries() {
        val ctx = context ?: return
        getAllSettingsProviders(ctx).flatMap { it.getEntriesStatic(ctx) }
            .forEach { entry -> entry.createPreference(ctx) }
    }

    private fun updateDynamicEntries() {
        lifecycleScope.launch {
            val ctx = context ?: return@launch
            val entries = getAllSettingsProviders(ctx).flatMap { it.getEntriesDynamic(ctx) }

            createdPreferences.forEach { preference ->
                if (entries.none { it.key == preference.key }) preference.isVisible = false
            }

            entries.forEach { entry ->
                val preference = createdPreferences.find { it.key == entry.key }
                if (preference != null) preference.fillFromEntry(entry)
                else entry.createPreference(ctx)
            }
        }
    }

    private fun toggleLauncherIconVisibility(hide: Boolean) {
        val newState = if (hide) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }

        val ctx = context ?: return
        val component = ComponentName(ctx, ACTIVITY_LAUNCHER_CONTROL)

        ctx.packageManager.setComponentEnabledSetting(
            component, newState, PackageManager.DONT_KILL_APP
        )
    }

    private fun updateLauncherIconSwitchState() {
        val ctx = context ?: return
        val component = ComponentName(ctx, ACTIVITY_LAUNCHER_CONTROL)
        val state = ctx.packageManager.getComponentEnabledSetting(component)

        val isHidden = state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED

        findPreference<SwitchPreferenceCompat>(PREF_HIDE_LAUNCHER_ICON)?.isChecked = isHidden
    }

    private fun updateCheckinSummary() {
        val summaryRes =
            if (CheckinPreferences.isEnabled(requireContext())) org.microg.gms.base.core.R.string.service_status_enabled_short
            else org.microg.gms.base.core.R.string.service_status_disabled_short
        findPreference<Preference>(PREF_CHECKIN)?.setSummary(summaryRes)
    }

    private fun SettingsProvider.Companion.Entry.createPreference(context: Context): Preference? {
        val preference = Preference(context).fillFromEntry(this)
        val categoryKey = when (group) {
            SettingsProvider.Companion.Group.HEADER -> "prefcat_header"
            SettingsProvider.Companion.Group.GOOGLE -> "prefcat_google_services"
            SettingsProvider.Companion.Group.OTHER -> "prefcat_other_services"
            SettingsProvider.Companion.Group.FOOTER -> "prefcat_footer"
        }
        return try {
            findPreference<PreferenceCategory>(categoryKey)?.addPreference(preference)?.let {
                if (it) createdPreferences.add(preference)
                preference
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed adding preference $key", e)
            null
        }
    }

    private fun Preference.fillFromEntry(entry: SettingsProvider.Companion.Entry): Preference {
        key = entry.key
        title = entry.title
        summary = entry.summary
        icon = entry.icon
        isPersistent = false
        isVisible = true
        setOnPreferenceClickListener {
            findNavController().navigate(context, entry.navigationId)
            true
        }
        return this
    }
}