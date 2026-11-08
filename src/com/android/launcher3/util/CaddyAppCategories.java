package com.android.launcher3.util;

import android.content.Context;
import android.content.res.Resources;

import com.android.launcher3.R;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class CaddyAppCategories {
    private static final int[][] CATEGORY_RESOURCES = {
            {R.string.caddy_utilities, R.array.caddy_packages_caddy_utilities},
            {R.string.social_category_label, R.array.caddy_packages_social_category_label},
            {R.string.caddy_games, R.array.caddy_packages_caddy_games},
            {R.string.caddy_finance, R.array.caddy_packages_caddy_finance},
            {R.string.caddy_lifestyle, R.array.caddy_packages_caddy_lifestyle},
            {R.string.caddy_entertainment, R.array.caddy_packages_caddy_entertainment},
            {R.string.caddy_multimedia, R.array.caddy_packages_caddy_multimedia},
            {R.string.productivity_category_label, R.array.caddy_packages_productivity_category_label},
            {R.string.news_category_label, R.array.caddy_packages_news_category_label},
            {R.string.caddy_travel, R.array.caddy_packages_caddy_travel},
            {R.string.caddy_shopping, R.array.caddy_packages_caddy_shopping},
            {R.string.caddy_health, R.array.caddy_packages_caddy_health},
            {R.string.caddy_education, R.array.caddy_packages_caddy_education},
            {R.string.caddy_business, R.array.caddy_packages_caddy_business},
    };

    private static volatile Map<String, Integer> sCategories;

    private CaddyAppCategories() { }

    public static int getCategory(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return 0;
        Map<String, Integer> categories = sCategories;
        if (categories == null) {
            synchronized (CaddyAppCategories.class) {
                categories = sCategories;
                if (categories == null) {
                    categories = loadCatalog(context.getResources());
                    sCategories = categories;
                }
            }
        }
        return categories.getOrDefault(packageName.toLowerCase(Locale.ROOT), 0);
    }

    static Map<String, Integer> loadCatalog(Resources resources) {
        Map<String, Integer> categories = new HashMap<>();
        for (int[] entry : CATEGORY_RESOURCES) {
            for (String pkg : resources.getStringArray(entry[1])) {
                if (categories.put(pkg.toLowerCase(Locale.ROOT), entry[0]) != null) {
                    throw new IllegalStateException("Duplicate Caddy package: " + pkg);
                }
            }
        }
        return Map.copyOf(categories);
    }
}
