package com.android.launcher3.search.universal;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.util.Themes;

public final class SearchResultTheme {

    private SearchResultTheme() { }

    public static int textColor(Context context) {
        return LauncherPrefs.ALL_APPS_DARK_TEXT.get(context)
                ? context.getColor(R.color.all_apps_label_color_dark_forced)
                : Themes.getAttrColor(context, android.R.attr.textColorPrimary);
    }

    static int secondaryColor(Context context) {
        return LauncherPrefs.ALL_APPS_DARK_TEXT.get(context)
                ? ColorUtils.setAlphaComponent(textColor(context), 184)
                : Themes.getAttrColor(context, android.R.attr.textColorSecondary);
    }

    static void bindRow(View view) {
        Context context = view.getContext();
        ((TextView) view.findViewById(R.id.search_result_title)).setTextColor(textColor(context));
        ((TextView) view.findViewById(R.id.search_result_subtitle))
                .setTextColor(secondaryColor(context));
        for (int id : new int[]{R.id.search_result_call, R.id.search_result_message,
                R.id.search_result_remove}) {
            ((ImageView) view.findViewById(id)).setImageTintList(
                    ColorStateList.valueOf(textColor(context)));
        }
    }

    static void bindChip(TextView chip, boolean selected) {
        Context context = chip.getContext();
        int text = textColor(context);
        chip.setTextColor(selected ? context.getColorStateList(R.color.all_apps_tab_text)
                .getColorForState(new int[]{android.R.attr.state_selected}, text) : text);
        int fill = selected ? context.getColor(R.color.all_apps_tab_background_selected)
                : ColorUtils.setAlphaComponent(text, 10);
        chip.setBackground(background(context, fill, !selected));
        chip.setSelected(selected);
    }

    private static RippleDrawable background(Context context, int fill, boolean outlined) {
        int text = textColor(context);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        float density = context.getResources().getDisplayMetrics().density;
        float radius = 24 * density * Math.max(0, Math.min(100,
                LauncherPrefs.SEARCH_RADIUS_SIZE.get(context))) / 100f;
        shape.setCornerRadius(radius);
        if (outlined) {
            shape.setStroke(Math.max(1, Math.round(density)),
                    ColorUtils.setAlphaComponent(text, 48));
        }
        return new RippleDrawable(ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(text, 32)), shape, null);
    }
}
