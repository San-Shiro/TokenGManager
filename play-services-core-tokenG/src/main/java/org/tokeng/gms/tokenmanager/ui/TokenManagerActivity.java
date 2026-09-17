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
    protected void onDestroy() {
        if (tabLayoutMediator != null) {
            tabLayoutMediator.detach();
            tabLayoutMediator = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Settings button in toolbar
        MenuItem settingsItem = menu.add(Menu.NONE, R.id.action_settings, Menu.NONE, "Settings");
        settingsItem.setIcon(android.R.drawable.ic_menu_preferences);
        settingsItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            // Navigate to microG Settings
            Intent intent = new Intent(this, MainSettingsActivity.class);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
