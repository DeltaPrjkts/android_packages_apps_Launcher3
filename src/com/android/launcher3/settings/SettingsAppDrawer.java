/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.settings;

import static androidx.preference.PreferenceFragmentCompat.ARG_PREFERENCE_ROOT;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.ListPreference;
import androidx.preference.TwoStatePreference;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherFiles;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.search.universal.CalendarProvider;
import com.android.launcher3.search.universal.ContactProvider;
import com.android.launcher3.search.universal.MediaProvider;
import com.android.launcher3.search.universal.PhotoIndex;
import com.android.launcher3.search.universal.SearchHistory;
import com.android.launcher3.allapps.AppDrawerStyle;
import com.android.launcher3.util.SettingsCache;

/**
 * Settings activity for Launcher.
 */
public class SettingsAppDrawer extends ComposeSettingsActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static final String EXTRA_FRAGMENT_ARGS = ":settings:fragment_args";

    // Intent extra to indicate the pref-key to highlighted when opening the settings activity
    public static final String EXTRA_FRAGMENT_HIGHLIGHT_KEY = ":settings:fragment_args_key";
    // Intent extra to indicate the pref-key of the root screen when opening the settings activity
    public static final String EXTRA_FRAGMENT_ROOT_KEY = ARG_PREFERENCE_ROOT;

    private static final int DELAY_HIGHLIGHT_DURATION_MILLIS = 600;
    public static final String SAVE_HIGHLIGHTED_KEY = "android:preference_highlighted";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();

        if (savedInstanceState == null) {
            Bundle args = intent.getBundleExtra(EXTRA_FRAGMENT_ARGS);
            if (args == null) {
                args = new Bundle();
            }

            String highlight = intent.getStringExtra(EXTRA_FRAGMENT_HIGHLIGHT_KEY);
            if (!TextUtils.isEmpty(highlight)) {
                args.putString(EXTRA_FRAGMENT_HIGHLIGHT_KEY, highlight);
            }
            String root = intent.getStringExtra(EXTRA_FRAGMENT_ROOT_KEY);
            if (!TextUtils.isEmpty(root)) {
                args.putString(EXTRA_FRAGMENT_ROOT_KEY, root);
            }

            final FragmentManager fm = getSupportFragmentManager();
            final Fragment f = fm.getFragmentFactory().instantiate(getClassLoader(),
                    getString(R.string.app_drawer_settings_fragment_name));
            f.setArguments(args);
            // Display the fragment as the main content.
            fm.beginTransaction().replace(R.id.content_frame, f).commit();
        }
        LauncherPrefs.getPrefs(this).registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        LauncherPrefs.getPrefs(this).unregisterOnSharedPreferenceChangeListener(this);
    }

@Override
public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
    if (LauncherPrefs.SEARCH_PHOTOS.getSharedPrefKey().equals(key)
            && !LauncherPrefs.SEARCH_PHOTOS.get(this)) {
        PhotoIndex.cancel(this);
    }
    if (LauncherPrefs.ALL_APPS_SEARCH_PLACEMENT.getSharedPrefKey().equals(key) ||
        LauncherPrefs.ALL_APPS_DARK_TEXT.getSharedPrefKey().equals(key) ||
        LauncherPrefs.APP_DRAWER_STYLE.getSharedPrefKey().equals(key) ||
        LauncherPrefs.APP_DRAWER_CUSTOM_COLOR_ENABLED.getSharedPrefKey().equals(key) ||
        LauncherPrefs.APP_DRAWER_CUSTOM_COLOR_LIGHT.getSharedPrefKey().equals(key) ||
        LauncherPrefs.APP_DRAWER_CUSTOM_COLOR_DARK.getSharedPrefKey().equals(key) ||
        LauncherPrefs.DRAWER_SCROLLBAR.getSharedPrefKey().equals(key) ||
        LauncherPrefs.APP_DRAWER_SORT_MODE.getSharedPrefKey().equals(key)) {
        LauncherAppState.INSTANCE.executeIfCreated(app -> app.setNeedsRestart());
    }
    
    if (LauncherPrefs.DRAWER_LIST.getSharedPrefKey().equals(key)) {
        // Trigger a refresh of the app list without requiring a restart
        // This will cause onAppsUpdated() to be called, which will recategorize apps
        try {
            LauncherAppState appState = LauncherAppState.getInstance(this);
            appState.getModel().rebindCallbacks();
        } catch (Exception e) {
            // Fallback to restart if rebind fails
            LauncherAppState.INSTANCE
                    .get(this)
                    .setNeedsRestart();
        }
    }
}

    /**
     * This fragment shows the launcher preferences.
     */
    public static class AppDrawerSettingsFragment extends ComposePreferenceFragment implements
            SettingsCache.OnChangeListener,
            SharedPreferences.OnSharedPreferenceChangeListener {

        private static final int REQUEST_READ_CONTACTS = 40;
        private static final int REQUEST_READ_CALENDAR = 41;
        private static final int REQUEST_READ_MEDIA = 42;
        private static final int REQUEST_READ_MEDIA_PHOTOS = 43;
        private static final int REQUEST_READ_SCREENSHOTS = 44;
        private static final int REQUEST_READ_DOWNLOADS = 45;

        private boolean mRestartOnResume = false;

        private static final String KEY_SEARCH_PLACEMENT = "pref_allapps_search_placement";
        private static final String KEY_OPEN_KEYBOARD = "pref_drawer_open_keyboard";
        private static final String KEY_APP_DRAWER_STYLE = "pref_app_drawer_style";

        private ListPreference mSearchPlacementPref;
        private ListPreference mDrawerStylePref;
        private Preference mOpenKeyboardPref;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {

            getPreferenceManager().setSharedPreferencesName(LauncherFiles.SHARED_PREFERENCES_KEY);
            setPreferencesFromResource(R.xml.launcher_app_drawer_preferences, rootKey);
            getPreferenceManager().getSharedPreferences()
                    .registerOnSharedPreferenceChangeListener(this);

            PreferenceScreen screen = getPreferenceScreen();
            wireSearchPreferences(screen);

            mSearchPlacementPref = screen.findPreference(KEY_SEARCH_PLACEMENT);
            mDrawerStylePref = screen.findPreference(KEY_APP_DRAWER_STYLE);
            mOpenKeyboardPref = screen.findPreference(KEY_OPEN_KEYBOARD);
            updateOpenKeyboardEnabled();
            updateDrawerStyleSummary();

            Preference pinnedAppsPref = screen.findPreference("pref_pinned_apps_list");
            if (pinnedAppsPref != null) {
                pinnedAppsPref.setOnPreferenceClickListener(preference -> {
                    Intent intent = new Intent(getActivity(), PinnedAppsActivity.class);
                    startActivity(intent);
                    return true;
                });
            }

            if (getActivity() != null && !TextUtils.isEmpty(getPreferenceScreen().getTitle())) {
                getActivity().setTitle(getPreferenceScreen().getTitle());
            }

        }

        private void wireSearchPreferences(PreferenceScreen screen) {
            Preference suggestions = screen.findPreference(
                    LauncherPrefs.SEARCH_WEB_SUGGESTIONS.getSharedPrefKey());
            if (suggestions != null) {
                suggestions.setEnabled(!"default".equals(
                        LauncherPrefs.SEARCH_ENGINE.get(requireContext())));
            }
            wirePermissionToggle(screen, LauncherPrefs.SEARCH_CONTACTS.getSharedPrefKey(),
                    REQUEST_READ_CONTACTS, new String[]{android.Manifest.permission.READ_CONTACTS},
                    () -> ContactProvider.hasPermission(requireContext()));
            wirePermissionToggle(screen, LauncherPrefs.SEARCH_EVENTS.getSharedPrefKey(),
                    REQUEST_READ_CALENDAR, new String[]{android.Manifest.permission.READ_CALENDAR},
                    () -> CalendarProvider.hasPermission(requireContext()));
            wirePermissionToggle(screen, LauncherPrefs.SEARCH_FILES.getSharedPrefKey(),
                    REQUEST_READ_MEDIA, MediaProvider.PERMISSIONS,
                    () -> MediaProvider.hasPermission(requireContext()));
            wirePermissionToggle(screen, LauncherPrefs.SEARCH_PHOTOS.getSharedPrefKey(),
                    REQUEST_READ_MEDIA_PHOTOS, MediaProvider.IMAGE_PERMISSIONS,
                    () -> MediaProvider.hasImagePermission(requireContext()));
            wirePermissionToggle(screen, LauncherPrefs.SEARCH_RECENT_SCREENSHOTS.getSharedPrefKey(),
                    REQUEST_READ_SCREENSHOTS, MediaProvider.IMAGE_PERMISSIONS,
                    () -> MediaProvider.hasImagePermission(requireContext()));
            wirePermissionToggle(screen, LauncherPrefs.SEARCH_RECENT_DOWNLOADS.getSharedPrefKey(),
                    REQUEST_READ_DOWNLOADS, MediaProvider.PERMISSIONS,
                    () -> MediaProvider.hasPermission(requireContext()));
            Preference clear = screen.findPreference("pref_search_clear_history");
            if (clear != null) {
                clear.setOnPreferenceClickListener(preference -> {
                    SearchHistory.clear(requireContext());
                    return true;
                });
            }
        }

        private void wirePermissionToggle(PreferenceScreen screen, String key, int requestCode,
                String[] permissions, java.util.function.BooleanSupplier hasPermission) {
            Preference pref = screen.findPreference(key);
            if (!(pref instanceof TwoStatePreference toggle)) {
                return;
            }
            if (toggle.isChecked() && !hasPermission.getAsBoolean()) {
                toggle.setChecked(false);
            }
            toggle.setOnPreferenceChangeListener((preference, value) -> {
                if (!((Boolean) value) || hasPermission.getAsBoolean()) {
                    return true;
                }
                requestPermissions(permissions, requestCode);
                return false;
            });
        }

        @Override
        public void onRequestPermissionsResult(int requestCode, String[] permissions,
                int[] grantResults) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            if (getPreferenceScreen() == null || !isAdded()) {
                return;
            }
            String key;
            boolean granted;
            if (requestCode == REQUEST_READ_CONTACTS) {
                key = LauncherPrefs.SEARCH_CONTACTS.getSharedPrefKey();
                granted = ContactProvider.hasPermission(requireContext());
            } else if (requestCode == REQUEST_READ_CALENDAR) {
                key = LauncherPrefs.SEARCH_EVENTS.getSharedPrefKey();
                granted = CalendarProvider.hasPermission(requireContext());
            } else if (requestCode == REQUEST_READ_MEDIA) {
                key = LauncherPrefs.SEARCH_FILES.getSharedPrefKey();
                granted = MediaProvider.hasPermission(requireContext());
            } else if (requestCode == REQUEST_READ_MEDIA_PHOTOS) {
                key = LauncherPrefs.SEARCH_PHOTOS.getSharedPrefKey();
                granted = MediaProvider.hasImagePermission(requireContext());
            } else if (requestCode == REQUEST_READ_SCREENSHOTS) {
                key = LauncherPrefs.SEARCH_RECENT_SCREENSHOTS.getSharedPrefKey();
                granted = MediaProvider.hasImagePermission(requireContext());
            } else if (requestCode == REQUEST_READ_DOWNLOADS) {
                key = LauncherPrefs.SEARCH_RECENT_DOWNLOADS.getSharedPrefKey();
                granted = MediaProvider.hasPermission(requireContext());
            } else {
                return;
            }
            Preference pref = getPreferenceScreen().findPreference(key);
            if (pref instanceof TwoStatePreference toggle) {
                toggle.setChecked(granted);
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            wireSearchPreferences(getPreferenceScreen());

            if (mRestartOnResume) {
                recreateActivityNow();
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            getPreferenceManager().getSharedPreferences()
                    .unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            if (LauncherPrefs.SEARCH_ENGINE.getSharedPrefKey().equals(key)) {
                wireSearchPreferences(getPreferenceScreen());
            }
            if (KEY_SEARCH_PLACEMENT.equals(key) || KEY_APP_DRAWER_STYLE.equals(key)) {
                updateOpenKeyboardEnabled();
                updateDrawerStyleSummary();
            }
        }

        private void updateOpenKeyboardEnabled() {
            if (mOpenKeyboardPref == null || mSearchPlacementPref == null) return;
            boolean searchVisible = !"hidden".equals(mSearchPlacementPref.getValue());
            String style = mDrawerStylePref == null
                    ? AppDrawerStyle.NORMAL : mDrawerStylePref.getValue();
            mOpenKeyboardPref.setEnabled(searchVisible && !AppDrawerStyle.isIos(style));
        }

        private void updateDrawerStyleSummary() {
            if (mDrawerStylePref == null) {
                return;
            }
            String style = mDrawerStylePref.getValue();
            mDrawerStylePref.setSummary(mDrawerStylePref.getEntry());
        }

        @Override
        public void onSettingsChanged(boolean isEnabled) {
            // Developer options changed, try recreate
            tryRecreateActivity();
        }

        /**
         * Tries to recreate the preference
         */
        protected void tryRecreateActivity() {
            if (isResumed()) {
                recreateActivityNow();
            } else {
                mRestartOnResume = true;
            }
        }

        private void recreateActivityNow() {
            Activity activity = getActivity();
            if (activity != null) {
                activity.recreate();
            }
        }

    }
}
