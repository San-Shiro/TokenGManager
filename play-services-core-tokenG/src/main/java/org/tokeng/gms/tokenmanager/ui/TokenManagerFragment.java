/*
 * SPDX-FileCopyrightText: 2024 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tokeng.gms.tokenmanager.ui;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.DropDownPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import org.microg.gms.auth.AuthConstants;
import org.tokeng.gms.auth.login.LoginActivity;
import org.tokeng.gms.tokenmanager.TokenConstants;
import org.tokeng.gms.tokenmanager.TokenManagerService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Token Manager Fragment with simplified UI.
 * 
 * Layout:
 * - ACCOUNTS: Add Account button + Account dropdown
 * - QUICK ACTIONS: Get GPhotos Token button
 * - TOKEN GENERATOR: App dropdown + Generate button
 * - RESULT: Token displays
 */
public class TokenManagerFragment extends PreferenceFragmentCompat {

    private TokenManagerService tokenService;
    private ExecutorService executor;

    // UI elements
    private DropDownPreference accountDropdown;
    private DropDownPreference appDropdown;
    private Preference addAccountPref;
    private Preference getPhotosTokenPref;
    private Preference generateTokenPref;
    private Preference masterTokenPref;
    private Preference appTokenPref;

    // State
    private Account selectedAccount;
    private String selectedAppPackage;
    private String fullMasterToken;
    private String fullAppToken;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        Context context = requireContext();
        tokenService = TokenManagerService.getInstance(context);
        executor = Executors.newSingleThreadExecutor();

        // Create preference screen
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);

        // ==================== ACCOUNTS SECTION ====================
        PreferenceCategory accountsCat = new PreferenceCategory(context);
        accountsCat.setTitle("ACCOUNTS");
        screen.addPreference(accountsCat);

        // Add Account button
        addAccountPref = new Preference(context);
        addAccountPref.setTitle("+ Add Account");
        addAccountPref.setSummary("Sign in to a Google account");
        addAccountPref.setOnPreferenceClickListener(pref -> {
            Intent intent = new Intent(context, LoginActivity.class);
            startActivity(intent);
            return true;
        });
        accountsCat.addPreference(addAccountPref);

        // Account dropdown
        accountDropdown = new DropDownPreference(context);
        accountDropdown.setTitle("Selected Account");
        populateAccountDropdown();
        accountDropdown.setOnPreferenceChangeListener((preference, newValue) -> {
            String email = (String) newValue;
            selectedAccount = findAccountByEmail(email);
            accountDropdown.setSummary(email);
            clearResults();
            return true;
        });
        accountsCat.addPreference(accountDropdown);

        // ==================== QUICK ACTIONS SECTION ====================
        PreferenceCategory quickCat = new PreferenceCategory(context);
        quickCat.setTitle("QUICK ACTIONS");
        screen.addPreference(quickCat);

        // Get GPhotos Token button
        getPhotosTokenPref = new Preference(context);
        getPhotosTokenPref.setTitle("Get GPhotos Token");
        getPhotosTokenPref.setSummary("Copies auth request string to clipboard");
        getPhotosTokenPref.setOnPreferenceClickListener(pref -> {
            if (selectedAccount == null) {
                Toast.makeText(context, "Please select an account first", Toast.LENGTH_SHORT).show();
                return true;
            }
            fetchPhotosAuthString();
            return true;
        });
        quickCat.addPreference(getPhotosTokenPref);

        // ==================== TOKEN GENERATOR SECTION ====================
        PreferenceCategory genCat = new PreferenceCategory(context);
        genCat.setTitle("TOKEN GENERATOR");
        screen.addPreference(genCat);

        // App dropdown
        appDropdown = new DropDownPreference(context);
        appDropdown.setTitle("App to Spoof");
        populateAppDropdown();
        appDropdown.setOnPreferenceChangeListener((preference, newValue) -> {
            selectedAppPackage = (String) newValue;
            String scope = TokenConstants.getScopeForPackage(selectedAppPackage);
            appDropdown.setSummary(TokenConstants.getAppName(selectedAppPackage));
            return true;
        });
        genCat.addPreference(appDropdown);

        // Generate Token button
        generateTokenPref = new Preference(context);
        generateTokenPref.setTitle("Generate Token");
        generateTokenPref.setSummary("Fetch token for selected app");
        generateTokenPref.setOnPreferenceClickListener(pref -> {
            if (selectedAccount == null) {
                Toast.makeText(context, "Please select an account first", Toast.LENGTH_SHORT).show();
                return true;
            }
            if (selectedAppPackage == null) {
                Toast.makeText(context, "Please select an app", Toast.LENGTH_SHORT).show();
                return true;
            }
            fetchToken();
            return true;
        });
        genCat.addPreference(generateTokenPref);

        // ==================== RESULT SECTION ====================
        PreferenceCategory resultCat = new PreferenceCategory(context);
        resultCat.setTitle("RESULT");
        screen.addPreference(resultCat);

        // Master Token
        masterTokenPref = new Preference(context);
        masterTokenPref.setTitle("Master Token");
        masterTokenPref.setSummary("Tap to copy");
        masterTokenPref.setOnPreferenceClickListener(pref -> {
            copyToClipboard("Master Token", fullMasterToken);
            return true;
        });
        resultCat.addPreference(masterTokenPref);

        // App Token
        appTokenPref = new Preference(context);
        appTokenPref.setTitle("App Token");
        appTokenPref.setSummary("Tap to copy");
        appTokenPref.setOnPreferenceClickListener(pref -> {
            copyToClipboard("App Token", fullAppToken);
            return true;
        });
        resultCat.addPreference(appTokenPref);

        setPreferenceScreen(screen);
    }

    private void populateAccountDropdown() {
        AccountManager am = AccountManager.get(requireContext());
        Account[] accounts = am.getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE);

        if (accounts.length == 0) {
            accountDropdown.setEnabled(false);
            accountDropdown.setSummary("No accounts. Tap Add Account.");
            return;
        }

        // Enable dropdown when accounts exist
        accountDropdown.setEnabled(true);

        String[] names = new String[accounts.length];
        String[] values = new String[accounts.length];
        for (int i = 0; i < accounts.length; i++) {
            names[i] = accounts[i].name;
            values[i] = accounts[i].name;
        }

        accountDropdown.setEntries(names);
        accountDropdown.setEntryValues(values);
        accountDropdown.setValueIndex(0);
        accountDropdown.setSummary(accounts[0].name);
        selectedAccount = accounts[0];
    }

    private void populateAppDropdown() {
        String[] packages = TokenConstants.getKnownAppPackages();
        String[] names = new String[packages.length];

        for (int i = 0; i < packages.length; i++) {
            names[i] = TokenConstants.getAppName(packages[i]);
        }

        appDropdown.setEntries(names);
        appDropdown.setEntryValues(packages);
        appDropdown.setValueIndex(0);
        appDropdown.setSummary(names[0]);
        selectedAppPackage = packages[0];
    }

    private Account findAccountByEmail(String email) {
        AccountManager am = AccountManager.get(requireContext());
        for (Account acc : am.getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE)) {
            if (acc.name.equals(email)) {
                return acc;
            }
        }
        return null;
    }

    private void fetchPhotosAuthString() {
        Context context = requireContext();
        getPhotosTokenPref.setSummary("Fetching...");

        executor.execute(() -> {
            try {
                String authString = tokenService.buildPhotosAuthRequestString(selectedAccount);

                requireActivity().runOnUiThread(() -> {
                    copyToClipboard("GPhotos Auth", authString);
                    getPhotosTokenPref.setSummary("Copied to clipboard!");
                    Toast.makeText(context, "Auth string copied!", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
                    getPhotosTokenPref.setSummary("Error: " + e.getMessage());
                    Toast.makeText(context, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void fetchToken() {
        Context context = requireContext();
        generateTokenPref.setSummary("Fetching...");

        executor.execute(() -> {
            // Get master token
            String masterToken = tokenService.getMasterToken(selectedAccount);
            fullMasterToken = masterToken;

            // Get app token
            String scope = TokenConstants.getScopeForPackage(selectedAppPackage);
            String appToken = tokenService.fetchToken(selectedAccount, selectedAppPackage, scope);
            fullAppToken = appToken;

            requireActivity().runOnUiThread(() -> {
                masterTokenPref.setSummary(truncate(masterToken));
                appTokenPref.setSummary(truncate(appToken));
                generateTokenPref.setSummary("Done! Tap tokens to copy");

                if (appToken.startsWith("ERROR")) {
                    Toast.makeText(context, appToken, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(context, "Token fetched!", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void clearResults() {
        fullMasterToken = null;
        fullAppToken = null;
        masterTokenPref.setSummary("Tap to copy");
        appTokenPref.setSummary("Tap to copy");
    }

    private String truncate(String s) {
        if (s == null)
            return "null";
        if (s.startsWith("ERROR"))
            return s;
        if (s.length() > 60) {
            return s.substring(0, 30) + "..." + s.substring(s.length() - 15);
        }
        return s;
    }

    private void copyToClipboard(String label, String text) {
        if (text == null || text.isEmpty()) {
            Toast.makeText(requireContext(), "Nothing to copy", Toast.LENGTH_SHORT).show();
            return;
        }
        if (text.startsWith("ERROR")) {
            Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(requireContext(), label + " copied!", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh accounts in case user added one
        populateAccountDropdown();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdown();
        }
    }
}
