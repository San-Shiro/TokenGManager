/*
 * SPDX-FileCopyrightText: 2024 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.tokenmanager;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.util.Log;

import org.microg.gms.auth.AuthRequest;
import org.microg.gms.auth.AuthResponse;

import java.io.UnsupportedEncodingException;

/**
 * Core service for token operations.
 * Handles fetching master tokens, app tokens, and building auth request
 * strings.
 */
public class TokenManagerService {
    private static final String TAG = "TokenManagerService";
    private static volatile TokenManagerService instance;

    private final Context context;

    private TokenManagerService(Context context) {
        this.context = context.getApplicationContext();
    }

    public static TokenManagerService getInstance(Context context) {
        if (instance == null) {
            synchronized (TokenManagerService.class) {
                if (instance == null) {
                    instance = new TokenManagerService(context);
                }
            }
        }
        return instance;
    }

    /**
     * Get the master token stored for an account.
     */
    public String getMasterToken(Account account) {
        AccountManager accountManager = AccountManager.get(context);
        String masterToken = accountManager.getPassword(account);
        if (masterToken == null || masterToken.isEmpty()) {
            org.microg.gms.database.TokenAccount dbAcct = org.microg.gms.database.TokenDatabase.getInstance(context).getAccount(account.name);
            if (dbAcct != null) {
                masterToken = dbAcct.getMasterToken();
            }
        }

        if (masterToken == null || masterToken.isEmpty()) {
            return null;
        }

        Log.i(TAG, "Master token retrieved (length: " + masterToken.length() + ")");
        return masterToken;
    }

    /**
     * Fetch a token for a specific app by spoofing its identity.
     */
    public String fetchToken(Account account, String packageName, String scope) {
        String signature = TokenConstants.GOOGLE_SIGNATURE;
        return fetchToken(account, packageName, signature, scope);
    }

    /**
     * Fetch a token with custom signature.
     */
    public String fetchToken(Account account, String packageName, String signature, String scope) {
        Log.i(TAG, "Fetching token for: " + packageName + " | Scope: " + scope);

        try {
            String masterToken = getMasterToken(account);
            if (masterToken == null) {
                return "ERROR: No master token found. Please re-login.";
            }

            AuthRequest request = new AuthRequest()
                    .fromContext(context)
                    .email(account.name)
                    .token(masterToken)
                    .service(scope)
                    .app(packageName, signature)
                    .caller(packageName, signature)
                    .systemPartition(true)
                    .hasPermission(true);

            Log.i(TAG, "Sending request to Google auth server...");
            AuthResponse response = request.getResponse();

            if (response != null && response.auth != null && !response.auth.isEmpty()) {
                String tokenType = response.auth.startsWith("aas_et/") ? "AES"
                        : response.auth.startsWith("ya29.") ? "OAuth2" : "Unknown";
                Log.i(TAG, "Token fetched successfully! Type: " + tokenType);
                return response.auth;
            } else {
                Log.w(TAG, "Response received but auth token is null/empty");
                return "ERROR: Google returned empty token";
            }

        } catch (Exception e) {
            Log.e(TAG, "Token fetch failed", e);
            return "ERROR: " + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }

    /**
     * Fetch a token for Google Photos.
     */
    public String fetchPhotosToken(Account account) {
        return fetchToken(account, TokenConstants.PHOTOS_PACKAGE, TokenConstants.PHOTOS_SCOPE);
    }

    /**
     * Build auth request string for Google Photos.
     */
    public String buildPhotosAuthRequestString(Account account) throws UnsupportedEncodingException {
        String masterToken = getMasterToken(account);
        if (masterToken == null) {
            throw new IllegalStateException("No master token found");
        }

        return new TokenRequestBuilder(context)
                .account(account)
                .token(masterToken)
                .app(TokenConstants.PHOTOS_PACKAGE, TokenConstants.GOOGLE_SIGNATURE)
                .scope(TokenConstants.PHOTOS_SCOPE)
                .build();
    }

    /**
     * Build auth request string for any app.
     */
    public String buildAuthRequestString(Account account, String packageName, String scope)
            throws UnsupportedEncodingException {
        String masterToken = getMasterToken(account);
        if (masterToken == null) {
            throw new IllegalStateException("No master token found");
        }

        return new TokenRequestBuilder(context)
                .account(account)
                .token(masterToken)
                .app(packageName, TokenConstants.GOOGLE_SIGNATURE)
                .scope(scope)
                .build();
    }
}
