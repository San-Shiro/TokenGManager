/*
 * Copyright (C) 2013-2017 microG Project Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.microg.gms.checkin;

import static org.microg.gms.checkin.CheckinPreferences.isSpoofingEnabled;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.os.Build;
import android.content.ContentResolver;
import android.content.Context;

import org.microg.gms.auth.AuthConstants;
import org.microg.gms.auth.AuthRequest;
import org.microg.gms.common.Constants;
import org.microg.gms.common.DeviceConfiguration;
import org.microg.gms.common.Utils;
import org.microg.gms.gservices.GServices;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class CheckinManager {
    private static final long MIN_CHECKIN_INTERVAL = 3 * 60 * 60 * 1000; // 3 hours

    @SuppressWarnings("MissingPermission")
    public static synchronized LastCheckinInfo checkin(Context context, boolean force) throws IOException {
        return checkin(context, force, null);
    }

    @SuppressWarnings("MissingPermission")
    public static synchronized LastCheckinInfo checkin(Context context, boolean force, org.microg.gms.profile.MultiDeviceRegistry.DevicePreset preset) throws IOException {
        LastCheckinInfo info = LastCheckinInfo.read(context);
        if (!force && info.getLastCheckin() > System.currentTimeMillis() - MIN_CHECKIN_INTERVAL)
            return null;
        if (!CheckinPreferences.isEnabled(context))
            return null;
        List<CheckinClient.Account> accounts = new ArrayList<CheckinClient.Account>();
        try {
            List<org.microg.gms.database.TokenAccount> dbAccounts = org.microg.gms.database.TokenDatabase.getInstance(context).getAllAccounts();
            for (org.microg.gms.database.TokenAccount account : dbAccounts) {
                String token = account.getLsid();
                if (token == null || token.isEmpty()) {
                    token = new AuthRequest()
                            .email(account.getEmail()).token(account.getMasterToken())
                            .hasPermission(true).service("ac2dm")
                            .app("com.google.android.gsf", Constants.GMS_PACKAGE_SIGNATURE_SHA1)
                            .getResponse().LSid;
                }
                if (token != null) {
                    accounts.add(new CheckinClient.Account(account.getEmail(), token));
                }
            }
        } catch (Exception ignored) {}

        if (accounts.isEmpty()) {
            AccountManager accountManager = AccountManager.get(context);
            String accountType = AuthConstants.DEFAULT_ACCOUNT_TYPE;
            for (Account account : accountManager.getAccountsByType(accountType)) {
                String token = new AuthRequest()
                        .email(account.name).token(accountManager.getPassword(account))
                        .hasPermission(true).service("ac2dm")
                        .app("com.google.android.gsf", Constants.GMS_PACKAGE_SIGNATURE_SHA1)
                        .getResponse().LSid;
                if (token != null) {
                    accounts.add(new CheckinClient.Account(account.name, token));
                }
            }
        }
        CheckinRequest request = CheckinClient.makeRequest(context,
                new DeviceConfiguration(context), Utils.getDeviceIdentifier(context),
                Utils.getPhoneInfo(context), info, Utils.getLocale(context), accounts, preset, isSpoofingEnabled(context));
        return handleResponse(context, CheckinClient.request(request));
    }


    /**
     * Perform fresh device check-in (androidId=0) to obtain a brand-new GSF ID and security token
     * bound to the allocated device preset.
     */
    @SuppressWarnings("MissingPermission")
    public static synchronized LastCheckinInfo checkinFresh(Context context, org.microg.gms.profile.MultiDeviceRegistry.DevicePreset preset) throws IOException {
        LastCheckinInfo freshInfo = new LastCheckinInfo(0, 0, 0, org.microg.gms.settings.SettingsContract.CheckIn.INITIAL_DIGEST, "", "");
        List<CheckinClient.Account> accounts = new ArrayList<CheckinClient.Account>();
        CheckinRequest request = CheckinClient.makeRequest(context,
                new DeviceConfiguration(context), Utils.getDeviceIdentifier(context),
                Utils.getPhoneInfo(context), freshInfo, Utils.getLocale(context), accounts, preset, true);
        return handleResponse(context, CheckinClient.request(request));
    }

    private static LastCheckinInfo handleResponse(Context context, CheckinResponse response) {
        LastCheckinInfo info = new LastCheckinInfo(response);
        info.write(context);

        ContentResolver resolver = context.getContentResolver();
        for (CheckinResponse.GservicesSetting setting : response.setting) {
            GServices.setString(resolver, setting.name.utf8(), setting.value_.utf8());
        }

        return info;
    }
}
