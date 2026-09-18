/*
 * SPDX-FileCopyrightText: 2024 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tokeng.gms.tokenmanager.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.tokeng.gms.R;
import org.tokeng.gms.ui.MainSettingsActivity;
import org.tokeng.gms.ui.UnifiedDashboardFragment;

/**
 * Standalone Token Manager Activity.
 * Launcher activity hosting the Unified Accounts & Device Dashboard.
 */
public class TokenManagerActivity extends AppCompatActivity {

    private SwipeRefreshLayout swipeRefresh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        org.tokeng.gms.ui.ThemeManager.INSTANCE.applyTheme(this);
        super.onCreate(savedInstanceState);
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_token_manager);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Token Manager");
        }

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, new UnifiedDashboardFragment(), "dashboard_fragment")
                .commit();
        }

        swipeRefresh = findViewById(R.id.swipe_refresh);
        if (swipeRefresh != null) {
            swipeRefresh.setColorSchemeResources(R.color.md_theme_primary);
            swipeRefresh.setOnRefreshListener(() -> {
                org.tokeng.gms.sync.BackendSyncManager.INSTANCE.triggerAutoSync(this, true, (success) -> {
                    swipeRefresh.setRefreshing(false);
                    refreshCurrentDashboard();
                    return null;
                });
            });
        }

        // Ensure background token validation job is registered
        org.tokeng.gms.sync.TokenValidationJobService.Companion.schedule(this);
    }

    private void refreshCurrentDashboard() {
        androidx.fragment.app.Fragment frag = getSupportFragmentManager().findFragmentByTag("dashboard_fragment");
        if (frag instanceof UnifiedDashboardFragment) {
            ((UnifiedDashboardFragment) frag).refreshDashboard(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!org.tokeng.gms.sync.BackendSyncManager.INSTANCE.isUnlocked(this)) {
            Intent gateIntent = new Intent(this, org.tokeng.gms.ui.AuthGateActivity.class);
            gateIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(gateIntent);
            finish();
            return;
        }

        // Periodic debounced auto-sync on app open
        org.tokeng.gms.sync.BackendSyncManager.INSTANCE.triggerAutoSync(this, false, (success) -> {
            if (Boolean.TRUE.equals(success)) {
                refreshCurrentDashboard();
            }
            return null;
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Debounced auto-sync on app close / background
        if (org.tokeng.gms.sync.BackendSyncManager.INSTANCE.isUnlocked(this)) {
            org.tokeng.gms.sync.BackendSyncManager.INSTANCE.triggerAutoSync(this, false, null);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem settingsItem = menu.add(Menu.NONE, R.id.action_settings, Menu.NONE, "Settings");
        settingsItem.setIcon(R.drawable.ic_settings);
        settingsItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            Intent intent = new Intent(this, org.tokeng.gms.ui.CloudSettingsActivity.class);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}

