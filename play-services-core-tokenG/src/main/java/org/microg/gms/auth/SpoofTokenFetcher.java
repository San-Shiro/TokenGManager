package org.microg.gms.auth;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

public class SpoofTokenFetcher {
    private static final String TAG = "SpoofTokenFetcher";

    // ================== GOOGLE APP SIGNATURES ==================
    // These are the SHA-1 signatures of Google's signing keys for first-party apps
    public static final String GOOGLE_SIG = "24bb24c05e47e0aefa68a58a766179d9b613a600";

    // ================== GOOGLE APP PACKAGES ==================
    public static final String PHOTOS_PACKAGE = "com.google.android.apps.photos";
    public static final String YOUTUBE_PACKAGE = "com.google.android.youtube";
    public static final String GMAIL_PACKAGE = "com.google.android.gm";
    public static final String DRIVE_PACKAGE = "com.google.android.apps.docs";
    public static final String MAPS_PACKAGE = "com.google.android.apps.maps";
    public static final String CALENDAR_PACKAGE = "com.google.android.calendar";
    public static final String KEEP_PACKAGE = "com.google.android.keep";
    public static final String GMS_PACKAGE = "com.google.android.gms";

    // ================== OAUTH2 SCOPES ==================
    // These scopes return ya29. OAuth2 tokens (not aas_et/ AES tokens)
    // AES tokens are only obtainable with the master token which is already stored
    public static final String PHOTOS_SCOPE = "oauth2:openid https://www.googleapis.com/auth/mobileapps.native https://www.googleapis.com/auth/photos.native";
    public static final String YOUTUBE_SCOPE = "oauth2:https://www.googleapis.com/auth/youtube";
    public static final String GMAIL_SCOPE = "oauth2:https://mail.google.com/";
    public static final String DRIVE_SCOPE = "oauth2:https://www.googleapis.com/auth/drive";
    public static final String CALENDAR_SCOPE = "oauth2:https://www.googleapis.com/auth/calendar";

    // Standard service string used to get the Master Token in microG
    private static final String MASTER_TOKEN_SERVICE = "android";

    // Map of known apps and their scopes
    private static final Map<String, String> APP_SCOPES = new HashMap<>();
    static {
        APP_SCOPES.put(PHOTOS_PACKAGE, PHOTOS_SCOPE);
        APP_SCOPES.put(YOUTUBE_PACKAGE, YOUTUBE_SCOPE);
        APP_SCOPES.put(GMAIL_PACKAGE, GMAIL_SCOPE);
        APP_SCOPES.put(DRIVE_PACKAGE, DRIVE_SCOPE);
        APP_SCOPES.put(CALENDAR_PACKAGE, CALENDAR_SCOPE);
    }

    /**
     * Retrieves the base Master AES Token already stored in microG's database.
     * This is the master token used to derive all other service-specific tokens.
     */
    public static String getMasterToken(Context context, Account account) {
        Log.i(TAG, "Retrieving stored Master Token...");
        AccountManager accountManager = AccountManager.get(context);
        String masterToken = accountManager.getPassword(account);
        if (masterToken == null || masterToken.isEmpty()) {
            org.microg.gms.database.TokenAccount dbAcct = org.microg.gms.database.TokenDatabase.getInstance(context).getAccount(account.name);
            if (dbAcct != null) {
                masterToken = dbAcct.getMasterToken();
            }
        }

        if (masterToken != null && !masterToken.isEmpty()) {
            Log.i(TAG, "Master Token retrieved successfully (length: " + masterToken.length() + ")");
            return masterToken;
        }
        return "ERROR: No master token found. Please re-login to your Google account.";
    }

    /**
     * Fetches the Google Photos specific AES Token via network spoofing.
     */
    public static String fetchPhotosNativeToken(Context context, Account account) {
        return fetchCustomToken(context, account, PHOTOS_PACKAGE, GOOGLE_SIG, PHOTOS_SCOPE);
    }

    /**
     * Fetches a token for any Google service by spoofing the specified app
     * identity.
     * 
     * @param context     Android context
     * @param account     The Google account
     * @param packageName Package name to spoof (e.g.,
     *                    "com.google.android.apps.photos")
     * @param signature   SHA-1 signature of the app (use GOOGLE_SIG for all Google
     *                    apps)
     * @param scope       OAuth2 scope string (e.g.,
     *                    "oauth2:https://www.googleapis.com/auth/photos.native")
     * @return The fetched token or error message
     */
    public static String fetchCustomToken(Context context, Account account,
            String packageName, String signature, String scope) {
        Log.i(TAG, "Fetching custom token for: " + packageName + " | Scope: " + scope);

        try {
            // 1. Get Master token from AccountManager or TokenDatabase
            org.microg.gms.database.TokenAccount dbAcct = org.microg.gms.database.TokenDatabase.getInstance(context).getAccount(account.name);
            AccountManager accountManager = AccountManager.get(context);
            String masterToken = accountManager.getPassword(account);
            if ((masterToken == null || masterToken.isEmpty()) && dbAcct != null) {
                masterToken = dbAcct.getMasterToken();
            }

            if (masterToken == null || masterToken.isEmpty()) {
                return "ERROR: No master token found. Please re-login to your Google account.";
            }

            Log.i(TAG, "Using master token (length: " + masterToken.length() + ")");

            // 2. Construct the spoofed request with full app identity
            org.microg.gms.profile.MultiDeviceRegistry.DevicePreset preset = null;
            if (dbAcct != null) {
                preset = org.microg.gms.profile.MultiDeviceRegistry.INSTANCE.getPresetByModel(dbAcct.getDeviceModel());
            }
            AuthRequest request = new AuthRequest()
                    .fromContext(context, preset) // Auto-sets Android ID, SDK version, locale, etc.
                    .email(account.name) // Account email
                    .token(masterToken) // Master AES token for authentication
                    .service(scope) // The OAuth2 scope we're requesting
                    .app(packageName, signature) // Spoof as this app
                    .caller(packageName, signature) // Also set caller to same app
                    .systemPartition(true) // Claim to be system app
                    .hasPermission(true); // Already have permission
            if (dbAcct != null && dbAcct.getAndroidId() != null && !dbAcct.getAndroidId().isEmpty()) {
                request.androidIdHex = dbAcct.getAndroidId();
            }

            // 3. Execute the request to Google's auth server
            Log.i(TAG, "Sending request to Google auth server...");
            AuthResponse response = request.getResponse();

            // 4. Parse and return the response
            if (response != null) {
                if (response.auth != null && !response.auth.isEmpty()) {
                    String tokenType = response.auth.startsWith("aas_et/") ? "AES (1P)"
                            : response.auth.startsWith("ya29.") ? "OAuth2" : "Unknown";
                    Log.i(TAG, "Token fetched successfully! Type: " + tokenType);
                    return response.auth;
                } else {
                    Log.w(TAG, "Response received but auth token is null/empty");
                    Log.w(TAG, "Full response: " + response.toString());
                    return "ERROR: Google returned empty token. Response: " + response.toString();
                }
            } else {
                return "ERROR: Null response from Google server";
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception during token fetch", e);
            return "ERROR: " + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }

    /**
     * Fetches a token for a known app (uses predefined scope)
     */
    public static String fetchTokenForApp(Context context, Account account, String packageName) {
        String scope = APP_SCOPES.get(packageName);
        if (scope == null) {
            return "ERROR: Unknown app package. Use fetchCustomToken() with explicit scope.";
        }
        return fetchCustomToken(context, account, packageName, GOOGLE_SIG, scope);
    }

    /**
     * Returns the list of known app packages that can be used for token generation
     */
    public static String[] getKnownAppPackages() {
        return new String[] {
                PHOTOS_PACKAGE,
                YOUTUBE_PACKAGE,
                GMAIL_PACKAGE,
                DRIVE_PACKAGE,
                MAPS_PACKAGE,
                CALENDAR_PACKAGE,
                KEEP_PACKAGE
        };
    }

    /**
     * Returns a human-readable name for a package
     */
    public static String getAppName(String packageName) {
        switch (packageName) {
            case PHOTOS_PACKAGE:
                return "Google Photos";
            case YOUTUBE_PACKAGE:
                return "YouTube";
            case GMAIL_PACKAGE:
                return "Gmail";
            case DRIVE_PACKAGE:
                return "Google Drive";
            case MAPS_PACKAGE:
                return "Google Maps";
            case CALENDAR_PACKAGE:
                return "Google Calendar";
            case KEEP_PACKAGE:
                return "Google Keep";
            case GMS_PACKAGE:
                return "Google Play Services";
            default:
                return packageName;
        }
    }
}