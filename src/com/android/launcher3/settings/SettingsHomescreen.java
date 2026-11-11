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
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherFiles;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.SessionCommitReceiver;
import com.android.launcher3.allapps.AppDrawerStyle;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.search.universal.CalendarProvider;
import com.android.launcher3.smartspace.SmartspaceEvent;
import com.android.launcher3.notification.NotificationListener;
import com.android.launcher3.util.SettingsCache;
import com.android.launcher3.util.VibratorWrapper;

/**
 * Settings activity for Launcher.
 */
public class SettingsHomescreen extends ComposeSettingsActivity
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
                    getString(R.string.home_screen_settings_fragment_name));
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
        if (LauncherPrefs.COS_APP_ANIMATIONS.getSharedPrefKey().equals(key) ||
                LauncherPrefs.DOCK_SEARCH.getSharedPrefKey().equals(key) ||
                LauncherPrefs.DOCK_SEARCH_WIDGET.getSharedPrefKey().equals(key) ||
                LauncherPrefs.DOCK_SEARCH_WIDGET_PENDING_CONFIG.getSharedPrefKey().equals(key) ||
                LauncherPrefs.COMPACT_SEARCH_BAR.getSharedPrefKey().equals(key) ||
                LauncherPrefs.COMPACT_SEARCH_BAR_ACTION.getSharedPrefKey().equals(key) ||
                LauncherPrefs.QSB_STYLE_GOOGLE.getSharedPrefKey().equals(key) ||
                LauncherPrefs.DOCK_THEME.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SEARCH_RADIUS_SIZE.getSharedPrefKey().equals(key) ||
                LauncherPrefs.DOCK_MUSIC_SEARCH.getSharedPrefKey().equals(key) ||
                LauncherPrefs.DOCK_UNIVERSAL_SEARCH.getSharedPrefKey().equals(key) ||
                LauncherPrefs.HOTSEAT_QSB_OPACITY.getSharedPrefKey().equals(key) ||
                LauncherPrefs.HOTSEAT_QSB_STROKE_WIDTH.getSharedPrefKey().equals(key) ||
                LauncherPrefs.HOTSEAT_QSB_HEIGHT.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SHOW_STATUS_BAR.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SHORT_PARALLAX.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SINGLE_PAGE_CENTER.getSharedPrefKey().equals(key) ||
                LauncherPrefs.DARK_STATUS_BAR.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SHOW_QUICKSPACE.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SHOW_QUICKSPACE_ALT.getSharedPrefKey().equals(key) ||
                LauncherPrefs.QUICKSPACE_UI_STYLE.getSharedPrefKey().equals(key) ||
                LauncherPrefs.QUICKSPACE_CLOCK_SYSTEM_FONT.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SHOW_QUICKSPACE_PSONALITY.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SHOW_QUICKSPACE_NOWPLAYING.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SHOW_QUICKSPACE_WEATHER.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SHOW_QUICKSPACE_WEATHER_CITY.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SHOW_QUICKSPACE_WEATHER_PROVIDER.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SHOW_QUICKSPACE_WEATHER_TEXT.getSharedPrefKey().equals(key) ||
                LauncherPrefs.AUTO_HIDE_DOTS.getSharedPrefKey().equals(key) ||
                LauncherPrefs.HOTSEAT_BOTTOM_SPACE_DEFAULT.getSharedPrefKey().equals(key)) {
            LauncherAppState.INSTANCE.executeIfCreated(app -> app.setNeedsRestart());
        }
    }

    /**
     * This fragment shows the launcher preferences.
     */
    public static class HomescreenSettingsFragment extends ComposePreferenceFragment implements
            SettingsCache.OnChangeListener, SharedPreferences.OnSharedPreferenceChangeListener {

        private boolean mRestartOnResume = false;

        private static final String KEY_MINUS_ONE = "pref_enable_minus_one";
        private static final String KEY_GENERAL_CATEGORY = "general_category";
        private static final String KEY_CLEAR_HOME_SCREEN = "pref_clear_home_screen";

        private Preference mShowGoogleAppPref;
        private Preference mShowGoogleBarPref;
        private SwitchPreferenceCompat mAutoAddIconsPref;
        private Preference mCalendarPermissionPref;
        private Preference mBluetoothPermissionPref;
        private Preference mNotificationPermissionPref;
        private static final int REQUEST_SMARTSPACE_CALENDAR = 42;
        private static final int REQUEST_SMARTSPACE_BLUETOOTH = 43;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {

            // Migrate renamed preferences before XML defaults are persisted.
            LauncherPrefs.getPrefs(requireContext());
            getPreferenceManager().setSharedPreferencesName(LauncherFiles.SHARED_PREFERENCES_KEY);
            setPreferencesFromResource(R.xml.launcher_home_screen_preferences, rootKey);
            addSmartspacePreferences();

            PreferenceScreen screen = getPreferenceScreen();

            mShowGoogleAppPref = screen.findPreference(KEY_MINUS_ONE);
            mShowGoogleBarPref = screen.findPreference(LauncherPrefs.DOCK_SEARCH.getSharedPrefKey());
            mAutoAddIconsPref = screen.findPreference(SessionCommitReceiver.ADD_ICON_PREFERENCE_KEY);

            SwitchPreferenceCompat cosAnimationsPref = screen.findPreference(
                    LauncherPrefs.COS_APP_ANIMATIONS.getSharedPrefKey());
            if (cosAnimationsPref != null) {
                cosAnimationsPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    if (!Boolean.TRUE.equals(newValue)) {
                        return true;
                    }
                    getPreferenceManager().showDialog(preference);
                    return false;
                });
            }

            Preference clearHomeScreenPref = screen.findPreference(KEY_CLEAR_HOME_SCREEN);
            if (clearHomeScreenPref != null) {
                clearHomeScreenPref.setOnPreferenceClickListener(pref -> {
                    new AlertDialog.Builder(getContext())
                            .setMessage(R.string.remove_all_views_from_home_screen_desc)
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                LauncherAppState.getInstance(getContext())
                                        .clearAllViewsFromHomeScreen();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                    return true;
                });
            }

            updateIsGoogleAppEnabled();
            updateAutoAddIconsPreferenceState();

            if (!VibratorWrapper.INSTANCE.get(getContext()).hasVibrator()) {
                PreferenceCategory generalCategory = (PreferenceCategory) findPreference(KEY_GENERAL_CATEGORY);
                Preference d2SHaptic = screen.findPreference(LauncherPrefs.SLEEP_GESTURE_HAPTIC.getSharedPrefKey());
                if (generalCategory != null && d2SHaptic != null) {
                    generalCategory.removePreference(d2SHaptic);
                }
            }

            if (getActivity() != null && !TextUtils.isEmpty(getPreferenceScreen().getTitle())) {
                getActivity().setTitle(getPreferenceScreen().getTitle());
            }

            updateQsbStylePrefs();
            updateQuickspaceClockColorPrefs();

            getPreferenceManager().getSharedPreferences()
                    .registerOnSharedPreferenceChangeListener(this);
        }

        private void updateIsGoogleAppEnabled() {
            if (mShowGoogleAppPref != null) {
                mShowGoogleAppPref.setEnabled(Utilities.isGSAEnabled(getContext()));
            }
            if (mShowGoogleBarPref != null) {
                mShowGoogleBarPref.setEnabled(Utilities.isGSAEnabled(getContext()));
            }
        }

        @Override
        public void onResume() {
            super.onResume();

            updateIsGoogleAppEnabled();
            updateAutoAddIconsPreferenceState();

            if (mRestartOnResume) {
                recreateActivityNow();
            }
            updateQsbStylePrefs();
            updateQuickspaceClockColorPrefs();
            LauncherPrefs.getPrefs(getContext())
                    .registerOnSharedPreferenceChangeListener(mPrefListener);
        }

        @Override
        public void onPause() {
            super.onPause();
            LauncherPrefs.getPrefs(getContext())
                    .unregisterOnSharedPreferenceChangeListener(mPrefListener);
        }

        private final SharedPreferences.OnSharedPreferenceChangeListener mPrefListener =
                (prefs, key) -> {
                    if (LauncherPrefs.DOCK_SEARCH.getSharedPrefKey().equals(key)
                            || LauncherPrefs.DOCK_SEARCH_WIDGET.getSharedPrefKey().equals(key)
                            || LauncherPrefs.QSB_STYLE_GOOGLE.getSharedPrefKey().equals(key)
                            || LauncherPrefs.COMPACT_SEARCH_BAR.getSharedPrefKey().equals(key)) {
                        updateQsbStylePrefs();
                    }
                    if (LauncherPrefs.QUICKSPACE_UI_STYLE.getSharedPrefKey().equals(key)) {
                        updateQuickspaceClockColorPrefs();
                    }
                };

        private void updateQsbStylePrefs() {
            boolean isGoogleStyle = LauncherPrefs.get(getContext())
                    .get(LauncherPrefs.QSB_STYLE_GOOGLE);
            boolean isCompact = LauncherPrefs.get(getContext())
                    .get(LauncherPrefs.COMPACT_SEARCH_BAR);
            boolean isDockSearch = LauncherPrefs.get(getContext())
                    .get(LauncherPrefs.DOCK_SEARCH);
            boolean customWidget = com.android.launcher3.qsb.DockSearchWidgetHelper
                    .isCustomWidgetEnabled(getContext());
            setQsbPrefEnabled(LauncherPrefs.QSB_STYLE_GOOGLE.getSharedPrefKey(),
                    !isCompact && !customWidget);
            setQsbPrefEnabled(LauncherPrefs.SEARCH_BAR_BLUR.getSharedPrefKey(),
                    isDockSearch && !customWidget);
            setQsbPrefEnabled(LauncherPrefs.DOCK_SEARCH_WIDGET.getSharedPrefKey(),
                    !isCompact);
            setQsbPrefEnabled(LauncherPrefs.COMPACT_SEARCH_BAR_ACTION.getSharedPrefKey(),
                    isDockSearch && isCompact);
            setQsbPrefEnabled(LauncherPrefs.DOCK_UNIVERSAL_SEARCH.getSharedPrefKey(),
                    isDockSearch && !isCompact && !customWidget);
            setQsbPrefEnabled(LauncherPrefs.HOTSEAT_QSB_OPACITY.getSharedPrefKey(),
                    (!isGoogleStyle || isCompact) && !customWidget);
            setQsbPrefEnabled(LauncherPrefs.HOTSEAT_QSB_STROKE_WIDTH.getSharedPrefKey(),
                    (!isGoogleStyle || isCompact) && !customWidget);
            setQsbPrefEnabled(LauncherPrefs.SEARCH_RADIUS_SIZE.getSharedPrefKey(),
                    (!isGoogleStyle || isCompact) && !customWidget);
        }

        private void setQsbPrefEnabled(String key, boolean enabled) {
            Preference pref = findPreference(key);
            if (pref != null) {
                pref.setEnabled(enabled);
            }
        }

        private void updateQuickspaceClockColorPrefs() {
            if (getContext() == null) {
                return;
            }
            boolean smartspace = LauncherPrefs.USE_SMARTSPACE.get(getContext());
            PreferenceGroup glance = findPreference("at_a_glance_category");
            if (glance != null) {
                for (int i = 0; i < glance.getPreferenceCount(); i++) {
                    Preference pref = glance.getPreference(i);
                    String key = pref.getKey();
                    if (key != null && key.startsWith("pref_quickspace_")) {
                        pref.setVisible(!smartspace);
                    }
                }
            }
            Preference events = findPreference("smartspace_events");
            if (events != null) {
                events.setVisible(smartspace);
                events.setEnabled(smartspace && LauncherPrefs.SHOW_QUICKSPACE.get(getContext()));
            }
            if (mCalendarPermissionPref != null) {
                mCalendarPermissionPref.setVisible(smartspace
                        && LauncherPrefs.SMARTSPACE_CALENDAR.get(getContext())
                        && !CalendarProvider.hasPermission(getContext()));
            }
            if (mBluetoothPermissionPref != null) {
                mBluetoothPermissionPref.setVisible(smartspace
                        && SmartspaceEvent.BLUETOOTH.isEnabled(getContext())
                        && getContext().checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED);
            }
            if (mNotificationPermissionPref != null) {
                NotificationManager manager = getContext().getSystemService(NotificationManager.class);
                mNotificationPermissionPref.setVisible(smartspace
                        && SmartspaceEvent.TIMERS.isEnabled(getContext()) && manager != null
                        && !manager.isNotificationListenerAccessGranted(
                                new ComponentName(getContext(), NotificationListener.class)));
            }
            int style;
            try {
                style = Integer.parseInt(
                        LauncherPrefs.get(getContext()).get(LauncherPrefs.QUICKSPACE_UI_STYLE));
            } catch (NumberFormatException e) {
                style = LauncherPrefs.get(getContext()).get(LauncherPrefs.SHOW_QUICKSPACE_ALT)
                        ? 1 : 0;
            }
            boolean isOosClock = style == 2 && !smartspace;
            setQuickspacePrefVisible(
                    LauncherPrefs.QUICKSPACE_CLOCK_SYSTEM_FONT.getSharedPrefKey(), isOosClock);
            setQuickspacePrefVisible(
                    LauncherPrefs.QUICKSPACE_CLOCK_CUSTOM_COLOR.getSharedPrefKey(), isOosClock);
            setQuickspacePrefVisible(
                    LauncherPrefs.QUICKSPACE_CLOCK_COLOR.getSharedPrefKey(), isOosClock);
        }

        private void setQuickspacePrefVisible(String key, boolean visible) {
            Preference pref = findPreference(key);
            if (pref != null) {
                pref.setVisible(visible);
            }
        }

        private void addSmartspacePreferences() {
            PreferenceGroup category = findPreference("smartspace_events");
            if (category == null) return;
            Preference info = new Preference(getPreferenceManager().getContext());
            info.setSummary(R.string.smartspace_events_summary);
            info.setSelectable(false);
            info.setIconSpaceReserved(false);
            info.setOrder(-1);
            category.addPreference(info);
            for (SmartspaceEvent event : SmartspaceEvent.values()) {
                SwitchPreferenceCompat pref = new SwitchPreferenceCompat(
                        getPreferenceManager().getContext());
                pref.setKey(event.preference.getSharedPrefKey());
                pref.setTitle(event.title);
                pref.setDefaultValue(true);
                pref.setPersistent(true);
                pref.setIconSpaceReserved(false);
                pref.setOrder(event.ordinal() * 2);
                category.addPreference(pref);
            }
            mCalendarPermissionPref = new Preference(getPreferenceManager().getContext());
            mCalendarPermissionPref.setTitle(R.string.smartspace_calendar_permission);
            mCalendarPermissionPref.setSummary(R.string.smartspace_calendar_permission_summary);
            mCalendarPermissionPref.setIconSpaceReserved(false);
            mCalendarPermissionPref.setOrder(SmartspaceEvent.CALENDAR.ordinal() * 2 + 1);
            mCalendarPermissionPref.setOnPreferenceClickListener(pref -> {
                requestPermissions(new String[] {android.Manifest.permission.READ_CALENDAR},
                        REQUEST_SMARTSPACE_CALENDAR);
                return true;
            });
            category.addPreference(mCalendarPermissionPref);
            mBluetoothPermissionPref = new Preference(getPreferenceManager().getContext());
            mBluetoothPermissionPref.setTitle(R.string.smartspace_bluetooth_permission);
            mBluetoothPermissionPref.setSummary(R.string.smartspace_bluetooth_permission_summary);
            mBluetoothPermissionPref.setIconSpaceReserved(false);
            mBluetoothPermissionPref.setOrder(SmartspaceEvent.BLUETOOTH.ordinal() * 2 + 1);
            mBluetoothPermissionPref.setOnPreferenceClickListener(pref -> {
                requestPermissions(new String[] {android.Manifest.permission.BLUETOOTH_CONNECT},
                        REQUEST_SMARTSPACE_BLUETOOTH);
                return true;
            });
            category.addPreference(mBluetoothPermissionPref);
            mNotificationPermissionPref = new Preference(getPreferenceManager().getContext());
            mNotificationPermissionPref.setTitle(R.string.smartspace_notification_permission);
            mNotificationPermissionPref.setSummary(R.string.smartspace_notification_permission_summary);
            mNotificationPermissionPref.setIconSpaceReserved(false);
            mNotificationPermissionPref.setOrder(SmartspaceEvent.TIMERS.ordinal() * 2 + 1);
            mNotificationPermissionPref.setOnPreferenceClickListener(pref -> {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                new ComponentName(getContext(), NotificationListener.class)
                                        .flattenToString()));
                return true;
            });
            category.addPreference(mNotificationPermissionPref);
        }

        @Override
        public void onRequestPermissionsResult(int requestCode, String[] permissions,
                int[] grantResults) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            if (requestCode == REQUEST_SMARTSPACE_CALENDAR
                    || requestCode == REQUEST_SMARTSPACE_BLUETOOTH) updateQuickspaceClockColorPrefs();
        }

        @Override
        public void onSettingsChanged(boolean isEnabled) {
            // Developer options changed, try recreate
            tryRecreateActivity();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            getPreferenceManager().getSharedPreferences()
                    .unregisterOnSharedPreferenceChangeListener(this);
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

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (key == null || key.startsWith("pref_smartspace")
                    || LauncherPrefs.SHOW_QUICKSPACE.getSharedPrefKey().equals(key)) {
                updateQuickspaceClockColorPrefs();
            }
            if (LauncherPrefs.APP_DRAWER_STYLE.getSharedPrefKey().equals(key)) {
                updateAutoAddIconsPreferenceState();
            }
        }

        private void updateAutoAddIconsPreferenceState() {
            if (mAutoAddIconsPref == null || getContext() == null) {
                return;
            }
            boolean iosStyle = AppDrawerStyle.isIos(AppDrawerStyle.get(getContext()));
            if (iosStyle) {
                mAutoAddIconsPref.setChecked(true);
                LauncherPrefs.getPrefs(getContext()).edit()
                        .putBoolean(SessionCommitReceiver.ADD_ICON_PREFERENCE_KEY, true)
                        .apply();
                mAutoAddIconsPref.setEnabled(false);
                mAutoAddIconsPref.setSummary(R.string.auto_add_shortcuts_forced_ios_summary);
            } else {
                mAutoAddIconsPref.setEnabled(true);
                mAutoAddIconsPref.setSummary(R.string.auto_add_shortcuts_description);
            }
        }
    }
}
