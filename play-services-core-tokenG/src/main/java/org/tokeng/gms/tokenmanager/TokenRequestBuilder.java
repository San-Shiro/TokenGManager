/*
 * SPDX-FileCopyrightText: 2024 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tokeng.gms.tokenmanager;

import android.accounts.Account;
import android.content.Context;
import android.os.Build;

import org.tokeng.gms.checkin.LastCheckinInfo;
import org.microg.gms.common.Constants;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Builds URL-encoded auth request strings for Google's auth endpoint.
 */
public class TokenRequestBuilder {

    private final Context context;
    private Account account;
    private String token;
    private String packageName;
    private String signature;
    private String scope;

    public TokenRequestBuilder(Context context) {
        this.context = context;
    }

    public TokenRequestBuilder account(Account account) {
        this.account = account;
        return this;
    }

    public TokenRequestBuilder token(String token) {
        this.token = token;
        return this;
    }

    public TokenRequestBuilder app(String packageName, String signature) {
        this.packageName = packageName;
        this.signature = signature;
        return this;
    }

    public TokenRequestBuilder scope(String scope) {
        this.scope = scope;
        return this;
    }

    /**
     * Build the URL-encoded auth request string.
     */
    public String build() throws UnsupportedEncodingException {
        if (account == null || token == null) {
            throw new IllegalStateException("Account and token are required");
        }

        // Get Android ID
        long androidId = LastCheckinInfo.read(context).getAndroidId();
        String androidIdHex = Long.toHexString(androidId);

        // Get locale
        Locale locale = Locale.getDefault();
        String lang = locale.getLanguage() + "_" + locale.getCountry();
        String country = locale.getCountry().toLowerCase();

        // Use defaults if not set
        String pkg = packageName != null ? packageName : TokenConstants.PHOTOS_PACKAGE;
        String sig = signature != null ? signature : TokenConstants.GOOGLE_SIGNATURE;
        String svc = scope != null ? scope : TokenConstants.PHOTOS_SCOPE;

        // Build request string
        StringBuilder sb = new StringBuilder();
        sb.append("androidId=").append(enc(androidIdHex));
        sb.append("&app=").append(enc(pkg));
        sb.append("&client_sig=").append(enc(sig));
        sb.append("&callerPkg=").append(enc(pkg));
        sb.append("&callerSig=").append(enc(sig));
        sb.append("&device_country=").append(enc(country));
        sb.append("&Email=").append(enc(account.name));
        sb.append("&google_play_services_version=").append(Constants.GMS_VERSION_CODE);
        sb.append("&lang=").append(enc(lang));
        sb.append("&oauth2_foreground=1");
        sb.append("&operatorCountry=").append(enc(country));
        sb.append("&sdk_version=").append(Build.VERSION.SDK_INT);
        sb.append("&service=").append(enc(svc));
        sb.append("&source=android");
        sb.append("&Token=").append(enc(token));

        return sb.toString();
    }

    private String enc(String s) throws UnsupportedEncodingException {
        return URLEncoder.encode(s, "UTF-8");
    }
}
