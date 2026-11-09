/*
 * Copyright (C) 2026 The PenguinOS Project
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.quickstep.cos;

import android.content.Context;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import com.android.internal.util.lunaris.SpringInterpolator;
import com.android.launcher3.LauncherPrefs;

/** Motion measured from ColorOS, kept independent of the default launcher animations. */
public final class CosAppAnimation {
    public static final long LAUNCH_DURATION = 500;
    public static final long ICON_FADE_DURATION = 43;
    public static final long REVEAL_DURATION = 500;
    public static final long WALLPAPER_OPEN_DURATION = 500;
    public static final long WALLPAPER_CLOSE_DURATION = 900;
    public static final float HOME_SCALE = 0.9f;
    public static final Interpolator OPEN = new PathInterpolator(0.2f, 0f, 0f, 1f);
    public static final Interpolator REVEAL = spring(324f, 1f, REVEAL_DURATION);
    public static final Interpolator WALLPAPER_OPEN = spring(196f, 1f, WALLPAPER_OPEN_DURATION);
    public static final Interpolator WALLPAPER_CLOSE = spring(49f, 1f, WALLPAPER_CLOSE_DURATION);

    private CosAppAnimation() { }

    public static boolean isEnabled(Context context) {
        return LauncherPrefs.COS_APP_ANIMATIONS.get(context);
    }

    /** Use the framework Luminous spring solver, normalized to Pengu's measured duration. */
    public static Interpolator spring(float stiffness, float damping, long duration) {
        SpringInterpolator spring = new SpringInterpolator(stiffness, damping, 0f);
        double seconds = duration / 1000.0;
        float end = spring.positionAt(seconds);
        return progress -> spring.positionAt(Math.max(0f, Math.min(1f, progress)) * seconds) / end;
    }

    public static float bounceDamping(float overshoot, float distance) {
        if (overshoot <= 0f || distance <= overshoot) return 1f;
        double log = Math.log(overshoot / distance);
        return Math.max(0.5f, Math.min(1f,
                (float) (-log / Math.sqrt(Math.PI * Math.PI + log * log))));
    }

    public static float iconFade(float elapsedMs) {
        float wt = 85f * Math.max(0f, elapsedMs - 55f) / 1000f;
        return 1f - (1f + wt) * (float) Math.exp(-wt);
    }
}
