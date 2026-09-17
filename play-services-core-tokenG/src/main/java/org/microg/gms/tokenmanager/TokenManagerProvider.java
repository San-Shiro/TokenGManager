/*
 * SPDX-FileCopyrightText: 2024 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.tokenmanager;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.microg.gms.auth.AuthConstants;

/**
 * ContentProvider for external app access to token operations.
 * Protected by ApiSecurityManager (signature OR password).
 * 
 * URIs:
 * content://[authority]/token/photos?account=email@gmail.com
 * content://[authority]/authstring/photos?account=email@gmail.com
 * 
 * Methods can also be called via:
 * provider.call("getPhotosToken", email, extras)
 * provider.call("getPhotosAuthString", email, extras)
 */
public class TokenManagerProvider extends ContentProvider {
    private static final String TAG = "TokenManagerProvider";

    // Authority will be set based on package
    public static final String AUTHORITY_SUFFIX = ".gms.tokenmanager";

    // URI paths
    private static final int TOKEN_PHOTOS = 1;
    private static final int AUTH_STRING_PHOTOS = 2;
    private static final int TOKEN_CUSTOM = 3;

    private UriMatcher uriMatcher;
    private ApiSecurityManager securityManager;
    private TokenManagerService tokenService;

    @Override
    public boolean onCreate() {
        String authority = getContext().getPackageName().replace(".android.gms", "") + AUTHORITY_SUFFIX;

        uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        uriMatcher.addURI(authority, "token/photos", TOKEN_PHOTOS);
        uriMatcher.addURI(authority, "authstring/photos", AUTH_STRING_PHOTOS);
        uriMatcher.addURI(authority, "token/*", TOKEN_CUSTOM);

        securityManager = new ApiSecurityManager(getContext());
        tokenService = TokenManagerService.getInstance(getContext());

        Log.i(TAG, "TokenManagerProvider initialized with authority: " + authority);
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        Bundle result = new Bundle();

        // Security check
        String callingPackage = getCallerPackageName();
        String password = extras != null ? extras.getString("password") : null;

        if (!securityManager.isAuthorized(callingPackage, password)) {
            Log.w(TAG, "Unauthorized access attempt from: " + callingPackage);
            result.putBoolean("success", false);
            result.putString("error", "Unauthorized. Check signature or password.");
            return result;
        }

        try {
            Account account = getAccountFromArg(arg);
            if (account == null) {
                result.putBoolean("success", false);
                result.putString("error", "Account not found: " + arg);
                return result;
            }

            switch (method) {
                case "getPhotosToken":
                    String token = tokenService.fetchPhotosToken(account);
                    result.putBoolean("success", !token.startsWith("ERROR"));
                    result.putString("token", token);
                    break;

                case "getPhotosAuthString":
                    String authString = tokenService.buildPhotosAuthRequestString(account);
                    result.putBoolean("success", true);
                    result.putString("authString", authString);
                    break;

                case "getMasterToken":
                    String masterToken = tokenService.getMasterToken(account);
                    result.putBoolean("success", masterToken != null);
                    result.putString("token", masterToken);
                    break;

                case "getCustomToken":
                    String packageName = extras.getString("packageName", TokenConstants.PHOTOS_PACKAGE);
                    String scope = extras.getString("scope", TokenConstants.PHOTOS_SCOPE);
                    String customToken = tokenService.fetchToken(account, packageName, scope);
                    result.putBoolean("success", !customToken.startsWith("ERROR"));
                    result.putString("token", customToken);
                    break;

                default:
                    result.putBoolean("success", false);
                    result.putString("error", "Unknown method: " + method);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in call()", e);
            result.putBoolean("success", false);
            result.putString("error", e.getMessage());
        }

        return result;
    }

    private Account getAccountFromArg(String email) {
        if (email == null || email.isEmpty()) {
            return null;
        }

        org.microg.gms.database.TokenAccount dbAccount = org.microg.gms.database.TokenDatabase.getInstance(getContext()).getAccount(email);
        if (dbAccount != null) {
            return new Account(dbAccount.getEmail(), AuthConstants.DEFAULT_ACCOUNT_TYPE);
        }

        AccountManager am = AccountManager.get(getContext());
        Account[] accounts = am.getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE);

        for (Account account : accounts) {
            if (account.name.equalsIgnoreCase(email)) {
                return account;
            }
        }
        return null;
    }

    private String getCallerPackageName() {
        String[] packages = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        return packages != null && packages.length > 0 ? packages[0] : "unknown";
    }

    // Standard ContentProvider methods - not used for our API
    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
            @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[] { "info" });
        cursor.addRow(new Object[] { "Use call() method for token operations" });
        return cursor;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return "vnd.android.cursor.item/token";
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new UnsupportedOperationException("Insert not supported");
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("Delete not supported");
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
            @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("Update not supported");
    }
}
