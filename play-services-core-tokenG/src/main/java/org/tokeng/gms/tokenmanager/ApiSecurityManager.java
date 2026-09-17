/*
 * SPDX-FileCopyrightText: 2024 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tokeng.gms.tokenmanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.Log;

import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/**
 * Manages API security for external app access.
 * Supports EITHER signature verification OR password authentication.
 */
public class ApiSecurityManager {
    private static final String TAG = "ApiSecurityManager";
    private static final String PREFS_NAME = "token_manager_security";
    private static final String KEY_AUTH_MODE = "auth_mode";
    private static final String KEY_PASSWORD_HASH = "password_hash";
    private static final String KEY_ALLOWED_SIGNATURES = "allowed_signatures";

    public enum AuthMode {
        SIGNATURE, // Validate calling app's signature
        PASSWORD // Validate shared secret password
    }

    private final Context context;
    private final SharedPreferences prefs;

    public ApiSecurityManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Get current authentication mode.
     */
    public AuthMode getAuthMode() {
        String mode = prefs.getString(KEY_AUTH_MODE, AuthMode.SIGNATURE.name());
        return AuthMode.valueOf(mode);
    }

    /**
     * Set authentication mode.
     */
    public void setAuthMode(AuthMode mode) {
        prefs.edit().putString(KEY_AUTH_MODE, mode.name()).apply();
        Log.i(TAG, "Auth mode set to: " + mode);
    }

    /**
     * Set the password for password-based authentication.
     */
    public void setPassword(String password) {
        String hash = hashPassword(password);
        prefs.edit().putString(KEY_PASSWORD_HASH, hash).apply();
        Log.i(TAG, "Password updated");
    }

    /**
     * Check if a password is set.
     */
    public boolean hasPassword() {
        return prefs.getString(KEY_PASSWORD_HASH, null) != null;
    }

    /**
     * Verify password.
     */
    public boolean verifyPassword(String password) {
        String storedHash = prefs.getString(KEY_PASSWORD_HASH, null);
        if (storedHash == null) {
            Log.w(TAG, "No password set");
            return false;
        }
        String inputHash = hashPassword(password);
        boolean valid = storedHash.equals(inputHash);
        Log.i(TAG, "Password verification: " + (valid ? "SUCCESS" : "FAILED"));
        return valid;
    }

    /**
     * Add an allowed signature for signature-based auth.
     */
    public void addAllowedSignature(String signatureHash) {
        Set<String> signatures = getAllowedSignatures();
        signatures.add(signatureHash.toLowerCase());
        prefs.edit().putStringSet(KEY_ALLOWED_SIGNATURES, signatures).apply();
        Log.i(TAG, "Added allowed signature: " + signatureHash);
    }

    /**
     * Remove an allowed signature.
     */
    public void removeAllowedSignature(String signatureHash) {
        Set<String> signatures = getAllowedSignatures();
        signatures.remove(signatureHash.toLowerCase());
        prefs.edit().putStringSet(KEY_ALLOWED_SIGNATURES, signatures).apply();
    }

    /**
     * Get all allowed signatures.
     */
    public Set<String> getAllowedSignatures() {
        return new HashSet<>(prefs.getStringSet(KEY_ALLOWED_SIGNATURES, new HashSet<>()));
    }

    /**
     * Verify calling app's signature.
     */
    public boolean verifyCallerSignature(String callingPackage) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo packageInfo = pm.getPackageInfo(callingPackage, PackageManager.GET_SIGNATURES);

            if (packageInfo.signatures == null || packageInfo.signatures.length == 0) {
                Log.w(TAG, "No signatures found for: " + callingPackage);
                return false;
            }

            Set<String> allowedSignatures = getAllowedSignatures();

            for (Signature signature : packageInfo.signatures) {
                String signatureHash = getSignatureHash(signature);
                if (allowedSignatures.contains(signatureHash.toLowerCase())) {
                    Log.i(TAG, "Signature verified for: " + callingPackage);
                    return true;
                }
            }

            Log.w(TAG, "Signature not in allowed list for: " + callingPackage);
            return false;

        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Package not found: " + callingPackage, e);
            return false;
        }
    }

    /**
     * Check if request is authorized based on current auth mode.
     */
    public boolean isAuthorized(String callingPackage, String password) {
        AuthMode mode = getAuthMode();

        if (mode == AuthMode.SIGNATURE) {
            return verifyCallerSignature(callingPackage);
        } else {
            return verifyPassword(password);
        }
    }

    /**
     * Get SHA-1 hash of a signature.
     */
    private String getSignatureHash(Signature signature) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(signature.toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error hashing signature", e);
            return "";
        }
    }

    /**
     * Hash a password using SHA-256.
     */
    private String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(password.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error hashing password", e);
            return "";
        }
    }
}
