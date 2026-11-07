/*
 * Copyright 2024-2026 Lunaris AOSP
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

package com.android.launcher3.qsb;

import static com.android.launcher3.BaseActivity.EVENT_RESUMED;
import static com.android.launcher3.LauncherState.ALL_APPS;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.Launcher;
import com.android.launcher3.R;
import com.android.launcher3.util.ContextTracker;
import com.android.launcher3.views.ActivityContext;

final class UniversalSearchLauncher {

    private UniversalSearchLauncher() { }

    static void launch(Context context) {
        ActivityContext activityContext = ActivityContext.lookupContextNoThrow(context);
        if (activityContext instanceof Launcher launcher) {
            showSearch(launcher);
            return;
        }

        ContextTracker.SchedulerCallback<Launcher> callback = (launcher, isHomeStarted) -> {
            launcher.addEventCallback(EVENT_RESUMED, () -> showSearch(launcher));
            return false;
        };
        Launcher.ACTIVITY_TRACKER.registerCallback(callback, "UniversalSearchLauncher.launch");
        try {
            context.startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(context.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Launcher.ACTIVITY_TRACKER.unregisterCallback(callback,
                    "UniversalSearchLauncher.launch failed");
            Toast.makeText(context, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    private static void showSearch(Launcher launcher) {
        AbstractFloatingView.closeAllOpenViews(launcher);
        launcher.getOverlayManager().hideOverlay(true);
        launcher.getAppsView().setUniversalSearchActive(true);
        // Search shares the All Apps container, but should open directly from home.
        launcher.getStateManager().goToState(ALL_APPS, true);
        launcher.getAppsView().getSearchUiManager().focusSearchField();
    }
}
