package org.tokeng.gms.ui;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.DropDownPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import org.microg.gms.auth.AuthConstants;
import org.tokeng.gms.auth.SpoofTokenFetcher;
import org.tokeng.gms.checkin.LastCheckinInfo;
import org.microg.gms.common.Constants;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Token Generator UI Fragment
 * 
 * This fragment allows users to generate AES tokens (aas_et/) for Google
 * services
 * WITHOUT requiring the actual app to be installed. This is achieved by
 * spoofing
 * the app identity when making requests to Google's auth server.
 * 
 * HOW IT WORKS:
 * 1. The Master Token is stored when you log in (it's the account "password")
 * 2. When you click "Generate", we send a request to
 * https://android.googleapis.com/auth
 * 3. We spoof the app identity (package name + signature) to appear as the
 * target app
 * 4. Google returns an AES token specifically for that app's scope
 */
public class TokenGeneratorFragment extends PreferenceFragmentCompat {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Account[] availableAccounts;
    private Account selectedAccount;
    private String selectedAppPackage = SpoofTokenFetcher.PHOTOS_PACKAGE;
    private String customScope = null;

    // UI Preferences
    private Preference masterTokenPref;
    private Preference generatedTokenPref;
    private Preference generateButtonPref;
    private Preference generateRequestStringPref;
    private EditTextPreference customScopePref;

    // Store full tokens for clipboard copy
    private String fullMasterToken = null;
    private String fullGeneratedToken = null;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getPreferenceManager().getContext();
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(screen);

        // Get Google Accounts from TokenDatabase or AccountManager
        java.util.List<org.tokeng.gms.database.TokenAccount> dbAccounts = org.tokeng.gms.database.TokenDatabase.getInstance(context).getAllAccounts();
        if (!dbAccounts.isEmpty()) {
            availableAccounts = new Account[dbAccounts.size()];
            for (int i = 0; i < dbAccounts.size(); i++) {
                availableAccounts[i] = new Account(dbAccounts.get(i).getEmail(), AuthConstants.DEFAULT_ACCOUNT_TYPE);
            }
        } else {
            AccountManager am = AccountManager.get(context);
            availableAccounts = am.getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE);
        }

        if (availableAccounts.length == 0) {
            Preference errorPref = new Preference(context);
            errorPref.setTitle("No Google Account Found");
            errorPref.setSummary("Please add a Google account in microG settings first.");
            errorPref.setIcon(android.R.drawable.ic_dialog_alert);
            screen.addPreference(errorPref);
            return;
        }

        selectedAccount = availableAccounts[0];

        // =============== SECTION 1: ACCOUNT SELECTOR ===============
        PreferenceCategory accountCat = new PreferenceCategory(context);
        accountCat.setTitle("1. Select Account");
        screen.addPreference(accountCat);

        DropDownPreference accountDropdown = new DropDownPreference(context);
        accountDropdown.setTitle("Google Account");
        accountDropdown.setIcon(android.R.drawable.ic_menu_myplaces);

        String[] accountNames = new String[availableAccounts.length];
        for (int i = 0; i < availableAccounts.length; i++) {
            accountNames[i] = availableAccounts[i].name;
        }
        accountDropdown.setEntries(accountNames);
        accountDropdown.setEntryValues(accountNames);
        accountDropdown.setValueIndex(0);
        accountCat.addPreference(accountDropdown);

        // =============== SECTION 2: APP SELECTOR ===============
        PreferenceCategory appCat = new PreferenceCategory(context);
        appCat.setTitle("2. Select Target App");
        screen.addPreference(appCat);

        DropDownPreference appDropdown = new DropDownPreference(context);
        appDropdown.setTitle("App to Spoof");
        appDropdown.setIcon(android.R.drawable.ic_menu_manage);

        String[] appPackages = SpoofTokenFetcher.getKnownAppPackages();
        String[] appNames = new String[appPackages.length + 1];
        String[] appValues = new String[appPackages.length + 1];

        for (int i = 0; i < appPackages.length; i++) {
            appNames[i] = SpoofTokenFetcher.getAppName(appPackages[i]);
            appValues[i] = appPackages[i];
        }
        appNames[appPackages.length] = "⚙ Custom (Enter Scope Below)";
        appValues[appPackages.length] = "CUSTOM";

        appDropdown.setEntries(appNames);
        appDropdown.setEntryValues(appValues);
        appDropdown.setValueIndex(0);
        appDropdown.setSummary("Using scope: " + SpoofTokenFetcher.PHOTOS_SCOPE);
        appCat.addPreference(appDropdown);

        // Custom Scope Input
        customScopePref = new EditTextPreference(context);
        customScopePref.setKey("custom_scope");
        customScopePref.setTitle("Custom OAuth2 Scope");
        customScopePref.setSummary("Enter custom scope (only used when 'Custom' is selected)");
        customScopePref.setDefaultValue("oauth2:");
        customScopePref.setDialogTitle("Enter OAuth2 Scope");
        customScopePref.setIcon(android.R.drawable.ic_menu_edit);
        customScopePref.setEnabled(false);
        appCat.addPreference(customScopePref);

        // =============== SECTION 3: GENERATE BUTTON ===============
        PreferenceCategory actionCat = new PreferenceCategory(context);
        actionCat.setTitle("3. Generate Token");
        screen.addPreference(actionCat);

        generateButtonPref = new Preference(context);
        generateButtonPref.setTitle("🔐 GENERATE AES TOKEN");
        generateButtonPref.setSummary("Click to fetch the token from Google's servers");
        generateButtonPref.setIcon(android.R.drawable.ic_menu_send);
        actionCat.addPreference(generateButtonPref);

        generateRequestStringPref = new Preference(context);
        generateRequestStringPref.setTitle("📋 GENERATE AUTH REQUEST STRING");
        generateRequestStringPref.setSummary("Generates URL-encoded request string with device info");
        generateRequestStringPref.setIcon(android.R.drawable.ic_menu_share);
        actionCat.addPreference(generateRequestStringPref);

        // =============== SECTION 4: TOKEN RESULTS ===============
        PreferenceCategory resultCat = new PreferenceCategory(context);
        resultCat.setTitle("4. Token Results (Click to Copy)");
        screen.addPreference(resultCat);

        masterTokenPref = new Preference(context);
        masterTokenPref.setTitle("Master Token (Account Password)");
        masterTokenPref.setSummary("Tap Generate to view");
        masterTokenPref.setIcon(android.R.drawable.ic_lock_idle_lock);
        resultCat.addPreference(masterTokenPref);

        generatedTokenPref = new Preference(context);
        generatedTokenPref.setTitle("Generated App Token (AES)");
        generatedTokenPref.setSummary("Tap Generate to fetch");
        generatedTokenPref.setIcon(android.R.drawable.ic_menu_camera);
        resultCat.addPreference(generatedTokenPref);

        // =============== EVENT HANDLERS ===============

        // Account selection handler
        accountDropdown.setOnPreferenceChangeListener((preference, newValue) -> {
            String selectedName = (String) newValue;
            selectedAccount = getAccountByName(selectedName);
            // Clear previous results
            masterTokenPref.setSummary("Tap Generate to view");
            generatedTokenPref.setSummary("Tap Generate to fetch");
            return true;
        });

        // App selection handler
        appDropdown.setOnPreferenceChangeListener((preference, newValue) -> {
            selectedAppPackage = (String) newValue;

            if ("CUSTOM".equals(selectedAppPackage)) {
                customScopePref.setEnabled(true);
                appDropdown.setSummary("Using custom scope (enter below)");
            } else {
                customScopePref.setEnabled(false);
                String scope = getScopeForPackage(selectedAppPackage);
                appDropdown.setSummary("Using scope: " + scope);
            }

            // Clear previous results
            generatedTokenPref.setSummary("Tap Generate to fetch");
            return true;
        });

        // Custom scope handler
        customScopePref.setOnPreferenceChangeListener((preference, newValue) -> {
            customScope = (String) newValue;
            return true;
        });

        // GENERATE BUTTON click handler
        generateButtonPref.setOnPreferenceClickListener(preference -> {
            if (selectedAccount == null) {
                Toast.makeText(context, "Please select an account first", Toast.LENGTH_SHORT).show();
                return true;
            }

            // Show loading state
            generateButtonPref.setSummary("⏳ Generating... Please wait");
            generateButtonPref.setEnabled(false);
            masterTokenPref.setSummary("Loading...");
            generatedTokenPref.setSummary("Fetching from Google Servers...");

            executor.execute(() -> {
                // 1. Get Master Token (synchronous)
                String masterToken = SpoofTokenFetcher.getMasterToken(context, selectedAccount);

                // 2. Generate App Token
                String appToken;
                if ("CUSTOM".equals(selectedAppPackage) && customScope != null && !customScope.isEmpty()) {
                    appToken = SpoofTokenFetcher.fetchCustomToken(
                            context, selectedAccount,
                            SpoofTokenFetcher.GMS_PACKAGE, // Use GMS package for custom
                            SpoofTokenFetcher.GOOGLE_SIG,
                            customScope);
                } else {
                    appToken = SpoofTokenFetcher.fetchTokenForApp(context, selectedAccount, selectedAppPackage);
                }

                // Update UI on main thread
                final String finalMasterToken = masterToken;
                final String finalAppToken = appToken;

                requireActivity().runOnUiThread(() -> {
                    // Store full tokens for clipboard copy
                    fullMasterToken = finalMasterToken;
                    fullGeneratedToken = finalAppToken;

                    masterTokenPref.setSummary(truncateToken(finalMasterToken));
                    generatedTokenPref.setSummary(truncateToken(finalAppToken));
                    generateButtonPref.setSummary("Click to fetch the token from Google's servers");
                    generateButtonPref.setEnabled(true);

                    if (!finalAppToken.startsWith("ERROR")) {
                        Toast.makeText(context, "✓ Token generated successfully!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, "⚠ " + finalAppToken, Toast.LENGTH_LONG).show();
                    }
                });
            });

            return true;
        });

        // GENERATE REQUEST STRING button click handler
        generateRequestStringPref.setOnPreferenceClickListener(preference -> {
            if (selectedAccount == null) {
                Toast.makeText(context, "Please select an account first", Toast.LENGTH_SHORT).show();
                return true;
            }
            if (fullMasterToken == null || fullMasterToken.startsWith("ERROR")) {
                Toast.makeText(context, "Please generate a token first!", Toast.LENGTH_SHORT).show();
                return true;
            }

            try {
                // Build the request string with device info
                String requestString = buildAuthRequestString(context, selectedAccount, fullMasterToken);

                // Copy to clipboard
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("microG Auth Request", requestString);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, "✓ Auth Request String copied!", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(context, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }

            return true;
        });

        // Copy-to-clipboard handlers - now use the full stored tokens
        masterTokenPref.setOnPreferenceClickListener(preference -> {
            if (fullMasterToken != null && !fullMasterToken.isEmpty() && !fullMasterToken.startsWith("ERROR")) {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("microG Master Token", fullMasterToken);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, "✓ Full Master Token copied!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "No valid token to copy. Generate first!", Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        generatedTokenPref.setOnPreferenceClickListener(preference -> {
            if (fullGeneratedToken != null && !fullGeneratedToken.isEmpty()
                    && !fullGeneratedToken.startsWith("ERROR")) {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("microG Generated Token", fullGeneratedToken);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, "✓ Full Generated Token copied!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "No valid token to copy. Generate first!", Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        // =============== SECTION 5: HELP ===============
        PreferenceCategory helpCat = new PreferenceCategory(context);
        helpCat.setTitle("ℹ About AES Tokens");
        screen.addPreference(helpCat);

        Preference helpPref = new Preference(context);
        helpPref.setTitle("How This Works");
        helpPref.setSummary("AES tokens (aas_et/) are high-security tokens used by Google's first-party apps. " +
                "This generator spoofs app identity to fetch these tokens without needing the actual app installed.");
        helpPref.setSelectable(false);
        helpCat.addPreference(helpPref);
    }

    private Account getAccountByName(String name) {
        for (Account account : availableAccounts) {
            if (account.name.equals(name)) {
                return account;
            }
        }
        return null;
    }

    private String getScopeForPackage(String packageName) {
        switch (packageName) {
            case SpoofTokenFetcher.PHOTOS_PACKAGE:
                return SpoofTokenFetcher.PHOTOS_SCOPE;
            case SpoofTokenFetcher.YOUTUBE_PACKAGE:
                return SpoofTokenFetcher.YOUTUBE_SCOPE;
            case SpoofTokenFetcher.GMAIL_PACKAGE:
                return SpoofTokenFetcher.GMAIL_SCOPE;
            case SpoofTokenFetcher.DRIVE_PACKAGE:
                return SpoofTokenFetcher.DRIVE_SCOPE;
            case SpoofTokenFetcher.CALENDAR_PACKAGE:
                return SpoofTokenFetcher.CALENDAR_SCOPE;
            default:
                return "Unknown scope";
        }
    }

    private String truncateToken(String token) {
        if (token == null)
            return "null";
        if (token.startsWith("ERROR"))
            return token;
        if (token.length() > 80) {
            return token.substring(0, 40) + "..." + token.substring(token.length() - 20);
        }
        return token;
    }

    private void showFullTokenDialog(Context context, String title) {
        // This method could be expanded to show the full token in a dialog
        Toast.makeText(context, "Long-press to copy full token", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    /**
     * Builds a URL-encoded auth request string with device info.
     * This generates a string in the format used by Google's auth endpoint.
     */
    private String buildAuthRequestString(Context context, Account account, String token)
            throws UnsupportedEncodingException {
        // Get Android ID from LastCheckinInfo
        long androidId = LastCheckinInfo.read(context).getAndroidId();
        String androidIdHex = Long.toHexString(androidId);

        // Get the scope/service for the selected app
        String scope = "CUSTOM".equals(selectedAppPackage) && customScope != null ? customScope
                : getScopeForPackage(selectedAppPackage);

        // Get package and signature
        String packageName = "CUSTOM".equals(selectedAppPackage) ? SpoofTokenFetcher.GMS_PACKAGE : selectedAppPackage;
        String signature = SpoofTokenFetcher.GOOGLE_SIG;

        // Get locale
        Locale locale = Locale.getDefault();
        String lang = locale.getLanguage() + "_" + locale.getCountry();
        String country = locale.getCountry().toLowerCase();

        // Build the request string
        StringBuilder sb = new StringBuilder();
        sb.append("androidId=").append(enc(androidIdHex));
        sb.append("&app=").append(enc(packageName));
        sb.append("&client_sig=").append(enc(signature));
        sb.append("&callerPkg=").append(enc(packageName));
        sb.append("&callerSig=").append(enc(signature));
        sb.append("&device_country=").append(enc(country));
        sb.append("&Email=").append(enc(account.name));
        sb.append("&google_play_services_version=").append(Constants.GMS_VERSION_CODE);
        sb.append("&lang=").append(enc(lang));
        sb.append("&oauth2_foreground=1");
        sb.append("&operatorCountry=").append(enc(country));
        sb.append("&sdk_version=").append(android.os.Build.VERSION.SDK_INT);
        sb.append("&service=").append(enc(scope));
        sb.append("&source=android");
        sb.append("&Token=").append(enc(token));

        return sb.toString();
    }

    private String enc(String s) throws UnsupportedEncodingException {
        return URLEncoder.encode(s, "UTF-8");
    }
}