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

import org.tokeng.gms.R;
import org.tokeng.gms.ui.MainSettingsActivity;
import org.tokeng.gms.ui.UnifiedDashboardFragment;

/**
 * Standalone Token Manager Activity.
 * Launcher activity hosting the Unified Accounts & Device Dashboard.
 */
public class TokenManagerActivity extends AppCompatActivity {

    private com.google.android.material.tabs.TabLayoutMediator tabLayoutMediator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_token_manager);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Token Manager");
        }

        // Set up ViewPager2 with TabLayoutMediator for smooth, blink-free tab sliding
        androidx.viewpager2.widget.ViewPager2 viewPager = findViewById(R.id.view_pager);
        com.google.android.material.tabs.TabLayout tabLayout = findViewById(R.id.tab_layout);

        if (viewPager != null && tabLayout != null) {
            viewPager.setOffscreenPageLimit(1);
            viewPager.setAdapter(new androidx.viewpager2.adapter.FragmentStateAdapter(this) {
                @androidx.annotation.NonNull
                @Override
                public androidx.fragment.app.Fragment createFragment(int position) {
                    if (position == 0) {
                        return new UnifiedDashboardFragment();
                    } else {
                        return new org.tokeng.gms.ui.SyncStatusFragment();
                    }
                }

                @Override
                public int getItemCount() {
                    return 2;
                }
            });

            tabLayoutMediator = new com.google.android.material.tabs.TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
                if (position == 0) {
                    tab.setText("Accounts").setIcon(R.drawable.ic_accounts);
                } else {
                    tab.setText("Cloud Sync").setIcon(R.drawable.ic_sync);
                }
            });
            tabLayoutMediator.attach();
        }

        // Ensure background token validation job is registered
        org.tokeng.gms.sync.TokenValidationJobService.Companion.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!org.tokeng.gms.sync.BackendSyncManager.INSTANCE.isUnlocked(this)) {
            Intent gateIntent = new Intent(this, org.tokeng.gms.ui.AuthGateActivity.class);
            gateIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(gateIntent);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (tabLayoutMediator != null) {
            tabLayoutMediator.detach();
            tabLayoutMediator = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Lock / Sign Out button in toolbar
        MenuItem lockItem = menu.add(Menu.NONE, 9999, Menu.NONE, "Lock / Sign Out");
        lockItem.setIcon(android.R.drawable.ic_lock_power_off);
        lockItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);

        // Settings button in toolbar
        MenuItem settingsItem = menu.add(Menu.NONE, R.id.action_settings, Menu.NONE, "Settings");
        settingsItem.setIcon(android.R.drawable.ic_menu_preferences);
        settingsItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 9999) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Lock Vault & Sign Out")
                .setMessage("Are you sure you want to sign out and lock this vault? Local tokens and credentials will be cleared from this device.")
                .setPositiveButton("Sign Out & Lock", (dialog, which) -> {
                    org.tokeng.gms.sync.BackendSyncManager.INSTANCE.signOut(this);
                    Intent gateIntent = new Intent(this, org.tokeng.gms.ui.AuthGateActivity.class);
                    gateIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(gateIntent);
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return true;
        }
        if (item.getItemId() == R.id.action_settings) {
            // Navigate to microG Settings
            Intent intent = new Intent(this, MainSettingsActivity.class);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}

