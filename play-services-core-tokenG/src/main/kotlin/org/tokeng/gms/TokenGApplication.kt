/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tokeng.gms

import android.app.Application
import org.tokeng.gms.ui.ThemeManager

class TokenGApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.applyTheme(this)
    }
}
