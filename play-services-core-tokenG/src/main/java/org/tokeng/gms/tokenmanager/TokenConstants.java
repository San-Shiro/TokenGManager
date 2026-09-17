/*
 * SPDX-FileCopyrightText: 2024 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tokeng.gms.tokenmanager;

import java.util.HashMap;
import java.util.Map;

/**
 * Constants for Token Manager module.
 * Contains Google app packages, signatures, and OAuth2 scopes.
 */
public final class TokenConstants {

    private TokenConstants() {
    } // Prevent instantiation

    // ================== GOOGLE SIGNATURE ==================
    public static final String GOOGLE_SIGNATURE = "24bb24c05e47e0aefa68a58a766179d9b613a600";

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
    public static final String PHOTOS_SCOPE = "oauth2:openid https://www.googleapis.com/auth/mobileapps.native https://www.googleapis.com/auth/photos.native";
    public static final String YOUTUBE_SCOPE = "oauth2:https://www.googleapis.com/auth/youtube";
    public static final String GMAIL_SCOPE = "oauth2:https://mail.google.com/";
    public static final String DRIVE_SCOPE = "oauth2:https://www.googleapis.com/auth/drive";
    public static final String CALENDAR_SCOPE = "oauth2:https://www.googleapis.com/auth/calendar";

    // ================== APP -> SCOPE MAPPING ==================
    private static final Map<String, String> APP_SCOPES = new HashMap<>();
    static {
        APP_SCOPES.put(PHOTOS_PACKAGE, PHOTOS_SCOPE);
        APP_SCOPES.put(YOUTUBE_PACKAGE, YOUTUBE_SCOPE);
        APP_SCOPES.put(GMAIL_PACKAGE, GMAIL_SCOPE);
        APP_SCOPES.put(DRIVE_PACKAGE, DRIVE_SCOPE);
        APP_SCOPES.put(CALENDAR_PACKAGE, CALENDAR_SCOPE);
    }

    // ================== APP -> DISPLAY NAME ==================
    private static final Map<String, String> APP_NAMES = new HashMap<>();
    static {
        APP_NAMES.put(PHOTOS_PACKAGE, "📷 Google Photos");
        APP_NAMES.put(YOUTUBE_PACKAGE, "▶ YouTube");
        APP_NAMES.put(GMAIL_PACKAGE, "✉ Gmail");
        APP_NAMES.put(DRIVE_PACKAGE, "📁 Google Drive");
        APP_NAMES.put(CALENDAR_PACKAGE, "📅 Calendar");
    }

    /**
     * Get the OAuth2 scope for a given package name.
     */
    public static String getScopeForPackage(String packageName) {
        return APP_SCOPES.get(packageName);
    }

    /**
     * Get display name for a given package name.
     */
    public static String getAppName(String packageName) {
        return APP_NAMES.getOrDefault(packageName, packageName);
    }

    /**
     * Get all known app packages.
     */
    public static String[] getKnownAppPackages() {
        return APP_SCOPES.keySet().toArray(new String[0]);
    }
}
