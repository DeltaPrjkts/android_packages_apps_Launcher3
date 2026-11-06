/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.quickstep.util;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Display;
import android.view.RemoteAnimationTarget;

import androidx.annotation.Nullable;

import com.android.launcher3.uioverrides.QuickstepLauncher;
import com.android.quickstep.SystemUiProxy;

public final class LunarisBarAnimation {
    private LunarisBarAnimation() {}

    @Nullable
    public static RectF getTarget(QuickstepLauncher launcher, RemoteAnimationTarget target) {
        Display display = launcher.getDisplay();
        if (display == null || target == null || target.taskInfo == null
                || target.isTranslucent || target.rotationChange != 0
                || !ValueAnimator.areAnimatorsEnabled()
                || launcher.getDeviceProfile().getDeviceProperties().isTablet()
                || target.taskInfo.displayId != display.getDisplayId()
                || target.windowConfiguration.getWindowingMode() != WINDOWING_MODE_FULLSCREEN
                || target.windowConfiguration.getActivityType() != ACTIVITY_TYPE_STANDARD
                || target.taskInfo.topActivity == null
                || (target.taskInfo.pictureInPictureParams != null
                        && target.taskInfo.pictureInPictureParams.isAutoEnterEnabled())) {
            return null;
        }
        Rect bounds = SystemUiProxy.INSTANCE.get(launcher).getLunarisBarTarget(
                target.taskInfo.topActivity.getPackageName(), target.taskInfo.userId,
                display.getDisplayId(), display.getRotation());
        if (bounds == null || bounds.isEmpty()) return null;
        int[] location = new int[2];
        launcher.getDragLayer().getLocationOnScreen(location);
        RectF result = new RectF(bounds);
        result.offset(-location[0], -location[1]);
        return result;
    }

    public static float getWindowAlpha(float progress) {
        if (progress <= 0.84f) return 1f;
        if (progress >= 0.99f) return 0f;
        float p = (progress - 0.84f) / 0.15f;
        return (1f - p) * (1f - p);
    }

    public static float getCornerRadius(float startRadius, float endRadius, float progress) {
        float p = Math.max(0f, Math.min(1f, progress));
        float curved = 1f - (1f - p) * (1f - p);
        return startRadius + curved * (endRadius - startRadius);
    }
}
