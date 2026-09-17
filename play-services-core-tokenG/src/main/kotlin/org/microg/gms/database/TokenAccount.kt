/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.database

data class TokenAccount @JvmOverloads constructor(
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
    val consecutiveAuthFailures: Int = 0
) {
    val isSignedOut: Boolean
        get() = accountStatus == "SIGNED_OUT"
}
