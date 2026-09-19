/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tokeng.gms.database

data class TokenAccount constructor(
    val email: String,
    val masterToken: String,
    val aasToken: String? = null,
    val sid: String? = null,
    val lsid: String? = null,
    val androidId: String,
    val securityToken: String,
    val deviceName: String,
    val deviceModel: String,
    val deviceBrand: String,
    val deviceFingerprint: String,
    val deviceSdk: Int,
    val firstName: String? = null,
    val lastName: String? = null,
    val googleUserId: String? = null,
    val syncStatus: String = "PENDING",
    val registeredAt: Long = System.currentTimeMillis(),
    val lastSyncAt: Long = 0L,
    val accountStatus: String = "ACTIVE",
    val lastValidatedAt: Long = 0L,
    val lastValidationResult: String? = null,
    val signedOutReason: String? = null,
    val consecutiveAuthFailures: Int = 0,
    val instanceId: String = java.util.UUID.randomUUID().toString()
) {
    constructor(
        email: String,
        masterToken: String,
        aasToken: String?,
        sid: String?,
        lsid: String?,
        androidId: String,
        securityToken: String,
        deviceName: String,
        deviceModel: String,
        deviceBrand: String,
        deviceFingerprint: String,
        deviceSdk: Int,
        firstName: String?,
        lastName: String?,
        googleUserId: String?,
        syncStatus: String,
        registeredAt: Long,
        lastSyncAt: Long
    ) : this(
        email = email,
        masterToken = masterToken,
        aasToken = aasToken,
        sid = sid,
        lsid = lsid,
        androidId = androidId,
        securityToken = securityToken,
        deviceName = deviceName,
        deviceModel = deviceModel,
        deviceBrand = deviceBrand,
        deviceFingerprint = deviceFingerprint,
        deviceSdk = deviceSdk,
        firstName = firstName,
        lastName = lastName,
        googleUserId = googleUserId,
        syncStatus = syncStatus,
        registeredAt = registeredAt,
        lastSyncAt = lastSyncAt,
        accountStatus = "ACTIVE",
        lastValidatedAt = 0L,
        lastValidationResult = null,
        signedOutReason = null,
        consecutiveAuthFailures = 0,
        instanceId = java.util.UUID.randomUUID().toString()
    )

    constructor(
        email: String,
        masterToken: String,
        aasToken: String?,
        sid: String?,
        lsid: String?,
        androidId: String,
        securityToken: String,
        deviceName: String,
        deviceModel: String,
        deviceBrand: String,
        deviceFingerprint: String,
        deviceSdk: Int,
        firstName: String?,
        lastName: String?,
        googleUserId: String?,
        syncStatus: String,
        registeredAt: Long,
        lastSyncAt: Long,
        instanceId: String
    ) : this(
        email = email,
        masterToken = masterToken,
        aasToken = aasToken,
        sid = sid,
        lsid = lsid,
        androidId = androidId,
        securityToken = securityToken,
        deviceName = deviceName,
        deviceModel = deviceModel,
        deviceBrand = deviceBrand,
        deviceFingerprint = deviceFingerprint,
        deviceSdk = deviceSdk,
        firstName = firstName,
        lastName = lastName,
        googleUserId = googleUserId,
        syncStatus = syncStatus,
        registeredAt = registeredAt,
        lastSyncAt = lastSyncAt,
        accountStatus = "ACTIVE",
        lastValidatedAt = 0L,
        lastValidationResult = null,
        signedOutReason = null,
        consecutiveAuthFailures = 0,
        instanceId = instanceId
    )

    val isSignedOut: Boolean
        get() = accountStatus == "SIGNED_OUT"
}
