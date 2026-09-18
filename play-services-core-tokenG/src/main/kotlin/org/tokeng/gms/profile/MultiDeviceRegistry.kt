/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tokeng.gms.profile

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.util.Log
import java.security.SecureRandom

object MultiDeviceRegistry {
    private const val TAG = "MultiDeviceRegistry"

    const val KEY_ANDROID_ID = "tokeng_android_id"
    const val KEY_SECURITY_TOKEN = "tokeng_security_token"
    const val KEY_DEVICE_NAME = "tokeng_device_name"
    const val KEY_DEVICE_FINGERPRINT = "tokeng_device_fingerprint"
    const val KEY_DEVICE_SDK = "tokeng_device_sdk"
    const val KEY_AAS_TOKEN = "tokeng_aas_token"
    const val KEY_SYNC_STATUS = "tokeng_sync_status"
    const val KEY_SYNC_TIME = "tokeng_sync_time"
    const val KEY_REGISTERED_TIME = "tokeng_registered_time"

    data class DevicePreset(
        val displayName: String,
        val model: String,
        val brand: String,
        val manufacturer: String,
        val device: String,
        val product: String,
        val hardware: String,
        val fingerprint: String,
        val bootloader: String,
        val buildId: String,
        val sdkVersion: Int,
        val buildTime: Long
    )

    val PRESETS: List<DevicePreset> = listOf(
        DevicePreset(
            displayName = "Google Pixel 8 Pro",
            model = "Pixel 8 Pro",
            brand = "google",
            manufacturer = "Google",
            device = "husky",
            product = "husky",
            hardware = "zuma",
            fingerprint = "google/husky/husky:14/UD1A.230803.041/10807258:user/release-keys",
            bootloader = "ripcurrent-1.1-10650974",
            buildId = "UD1A.230803.041",
            sdkVersion = 34,
            buildTime = 1693526400L
        ),
        DevicePreset(
            displayName = "Google Pixel 7",
            model = "Pixel 7",
            brand = "google",
            manufacturer = "Google",
            device = "panther",
            product = "panther",
            hardware = "tensor",
            fingerprint = "google/panther/panther:13/TQ3A.230901.001/10750268:user/release-keys",
            bootloader = "slider-1.2-9971768",
            buildId = "TQ3A.230901.001",
            sdkVersion = 33,
            buildTime = 1693440000L
        ),
        DevicePreset(
            displayName = "Samsung Galaxy S23",
            model = "SM-S911B",
            brand = "samsung",
            manufacturer = "samsung",
            device = "dm1q",
            product = "dm1qxeea",
            hardware = "qcom",
            fingerprint = "samsung/dm1qxeea/dm1q:14/UP1A.231005.007/S911BXXU3BWJM:user/release-keys",
            bootloader = "S911BXXU3BWJM",
            buildId = "UP1A.231005.007",
            sdkVersion = 34,
            buildTime = 1698710400L
        ),
        DevicePreset(
            displayName = "OnePlus 12",
            model = "CPH2581",
            brand = "OnePlus",
            manufacturer = "OnePlus",
            device = "OP5913L1",
            product = "CPH2581",
            hardware = "qcom",
            fingerprint = "OnePlus/CPH2581/OP5913L1:14/UKQ1.230924.001/U.18d3632_1-2:user/release-keys",
            bootloader = "unknown",
            buildId = "UKQ1.230924.001",
            sdkVersion = 34,
            buildTime = 1704067200L
        ),
        DevicePreset(
            displayName = "Google Pixel 3 (Legacy)",
            model = "Pixel 3",
            brand = "google",
            manufacturer = "Google",
            device = "blueline",
            product = "blueline",
            hardware = "blueline",
            fingerprint = "google/blueline/blueline:9/PQ3A.190801.002/5670241:user/release-keys",
            bootloader = "b1c1-0.1-5511053",
            buildId = "PQ3A.190801.002",
            sdkVersion = 28,
            buildTime = 1564617600L
        ),
        DevicePreset(
            displayName = "LG Nexus 5X (Legacy)",
            model = "Nexus 5X",
            brand = "google",
            manufacturer = "LGE",
            device = "bullhead",
            product = "bullhead",
            hardware = "bullhead",
            fingerprint = "google/bullhead/bullhead:6.0/MDB08M/2353240:user/release-keys",
            bootloader = "BHZ11e",
            buildId = "MDB08M",
            sdkVersion = 23,
            buildTime = 1451606400L
        )
    )

    /**
     * Allocate least-used preset across existing registered TokenAccounts.
     */
    fun allocatePreset(existingAccounts: List<org.tokeng.gms.database.TokenAccount>): DevicePreset {
        val usageCounts = mutableMapOf<String, Int>()
        for (preset in PRESETS) {
            usageCounts[preset.model] = 0
        }

        for (account in existingAccounts) {
            val assignedModel = account.deviceModel
            if (usageCounts.containsKey(assignedModel)) {
                usageCounts[assignedModel] = (usageCounts[assignedModel] ?: 0) + 1
            }
        }

        return PRESETS.minByOrNull { usageCounts[it.model] ?: 0 } ?: PRESETS[0]
    }

    /**
     * Allocate least-used preset across existing registered accounts.
     */
    fun allocatePreset(existingAccounts: List<Account>, am: AccountManager): DevicePreset {
        val usageCounts = mutableMapOf<String, Int>()
        for (preset in PRESETS) {
            usageCounts[preset.model] = 0
        }

        for (account in existingAccounts) {
            val assignedModel = am.getUserData(account, KEY_DEVICE_NAME)
            if (assignedModel != null && usageCounts.containsKey(assignedModel)) {
                usageCounts[assignedModel] = (usageCounts[assignedModel] ?: 0) + 1
            }
        }

        return PRESETS.minByOrNull { usageCounts[it.model] ?: 0 } ?: PRESETS[0]
    }

    /**
     * Get preset by model or display name.
     */
    fun getPresetByModel(model: String?): DevicePreset? {
        if (model == null) return null
        return PRESETS.firstOrNull { it.model.equals(model, ignoreCase = true) || it.displayName.equals(model, ignoreCase = true) }
    }

    /**
     * Create or adapt preset with existing account hardware profile.
     */
    @JvmStatic
    fun createPreset(
        displayName: String,
        model: String,
        brand: String,
        fingerprint: String,
        sdkVersion: Int
    ): DevicePreset {
        val existing = getPresetByModel(model)
        if (existing != null) {
            return existing.copy(
                displayName = displayName,
                model = model,
                brand = brand,
                fingerprint = if (fingerprint.isNotEmpty()) fingerprint else existing.fingerprint,
                sdkVersion = if (sdkVersion > 0) sdkVersion else existing.sdkVersion
            )
        }
        val defaultPreset = PRESETS[0]
        return defaultPreset.copy(
            displayName = displayName,
            model = model,
            brand = brand,
            fingerprint = if (fingerprint.isNotEmpty()) fingerprint else defaultPreset.fingerprint,
            sdkVersion = if (sdkVersion > 0) sdkVersion else defaultPreset.sdkVersion
        )
    }

    /**
     * Get next preset in rotation after current model.
     */
    fun getNextPreset(currentModel: String?): DevicePreset {
        if (currentModel == null) return PRESETS[0]
        val idx = PRESETS.indexOfFirst { it.model.equals(currentModel, ignoreCase = true) }
        return if (idx >= 0 && idx < PRESETS.size - 1) {
            PRESETS[idx + 1]
        } else {
            PRESETS[0]
        }
    }

    /**
     * Store complete device & token metadata for an account in AccountManager.
     */
    fun saveAccountDeviceInfo(
        am: AccountManager,
        account: Account,
        androidIdHex: String,
        securityToken: String?,
        preset: DevicePreset,
        aasToken: String?
    ) {
        try {
            am.setUserData(account, KEY_ANDROID_ID, androidIdHex)
            if (securityToken != null) {
                am.setUserData(account, KEY_SECURITY_TOKEN, securityToken)
            }
            am.setUserData(account, KEY_DEVICE_NAME, preset.displayName)
            am.setUserData(account, KEY_DEVICE_FINGERPRINT, preset.fingerprint)
            am.setUserData(account, KEY_DEVICE_SDK, preset.sdkVersion.toString())
            if (aasToken != null) {
                am.setUserData(account, KEY_AAS_TOKEN, aasToken)
            }
            am.setUserData(account, KEY_REGISTERED_TIME, System.currentTimeMillis().toString())
            am.setUserData(account, KEY_SYNC_STATUS, "PENDING")
            Log.d(TAG, "Saved device info for account ${account.name}: model=${preset.displayName}, gsf=$androidIdHex")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save device info for account ${account.name}", e)
        }
    }

    fun getAccountAndroidId(am: AccountManager, account: Account): String? {
        return am.getUserData(account, KEY_ANDROID_ID)
    }

    fun getAccountDeviceName(am: AccountManager, account: Account): String {
        return am.getUserData(account, KEY_DEVICE_NAME) ?: "Default MicroG Device"
    }

    fun getAccountAasToken(am: AccountManager, account: Account): String? {
        return am.getUserData(account, KEY_AAS_TOKEN)
    }

    fun getAccountSyncStatus(am: AccountManager, account: Account): String {
        return am.getUserData(account, KEY_SYNC_STATUS) ?: "PENDING"
    }

    fun setAccountSyncStatus(am: AccountManager, account: Account, status: String) {
        am.setUserData(account, KEY_SYNC_STATUS, status)
        am.setUserData(account, KEY_SYNC_TIME, System.currentTimeMillis().toString())
    }

    /**
     * Generate random 16-character GSF hex ID.
     */
    fun generateRandomGsfHex(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        val sb = java.lang.StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
