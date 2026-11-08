package com.android.launcher3.allapps;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.CrossWindowBlurListeners;

import androidx.core.graphics.ColorUtils;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.util.Themes;

final class CaddyStyle {
    private CaddyStyle() { }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static int textColor(Context context) {
        return LauncherPrefs.ALL_APPS_DARK_TEXT.get(context)
                ? context.getColor(R.color.all_apps_label_color_dark_forced)
                : Themes.getAttrColor(context, android.R.attr.textColorPrimary);
    }

    static RippleDrawable background(Context context, boolean sheet) {
        int base = Themes.getAttrColor(context, R.attr.allAppsScrimColor);
        if (LauncherPrefs.APP_DRAWER_CUSTOM_COLOR_ENABLED.get(context)) {
            base = Utilities.isDarkTheme(context)
                    ? LauncherPrefs.APP_DRAWER_CUSTOM_COLOR_DARK.get(context)
                    : LauncherPrefs.APP_DRAWER_CUSTOM_COLOR_LIGHT.get(context);
        }
        float opacity = Math.max(0, Math.min(100,
                LauncherPrefs.APP_DRAWER_OPACITY.get(context))) / 100f;
        int surface = ColorUtils.blendARGB(base, textColor(context), .12f);
        boolean blurEnabled = CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled();
        int alpha;
        if (sheet) {
            alpha = Math.round(255 * (blurEnabled ? .48f + .26f * opacity : .95f));
        } else {
            alpha = Math.round(255 * (.10f + .38f * opacity));
        }
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(ColorUtils.setAlphaComponent(surface, alpha));
        shape.setCornerRadius(dp(context, sheet ? 32 : 22));
        shape.setStroke(dp(context, 1), ColorUtils.setAlphaComponent(
                textColor(context), sheet ? 36 : 20));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(android.graphics.Color.WHITE);
        mask.setCornerRadius(dp(context, sheet ? 32 : 22));
        return new RippleDrawable(ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(textColor(context), 24)), shape, mask);
    }
}
