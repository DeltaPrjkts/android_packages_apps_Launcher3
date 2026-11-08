package com.android.launcher3.allapps;

import android.content.Context;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.util.AppsListUtils;
import com.android.launcher3.util.LabelComparator;
import com.android.launcher3.util.PackageUserKey;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CaddyAppLibrary {
    public static final int FAVORITE_COUNT = 4;

    private CaddyAppLibrary() { }

    public static final class Category {
        public final String title;
        public final List<AppInfo> apps;

        Category(String title, List<AppInfo> apps) {
            this.title = title;
            this.apps = List.copyOf(apps);
        }
    }

    public static List<Category> categories(Context context, List<AppInfo> apps) {
        List<Category> result = new ArrayList<>();
        Map<String, List<AppInfo>> categories = AppsListUtils.categorizeApps(context, apps);
        int[] order = {R.string.social_category_label, R.string.caddy_games,
                R.string.caddy_utilities, R.string.caddy_entertainment, R.string.caddy_lifestyle,
                R.string.caddy_finance, R.string.productivity_category_label,
                R.string.caddy_multimedia, R.string.news_category_label, R.string.caddy_travel,
                R.string.caddy_shopping, R.string.caddy_health, R.string.caddy_education,
                R.string.caddy_business};
        for (int label : order) {
            String title = context.getString(label);
            List<AppInfo> categoryApps = categories.remove(title);
            if (categoryApps != null) {
                result.add(new Category(title, categoryApps));
            }
        }
        for (Map.Entry<String, List<AppInfo>> entry : categories.entrySet()) {
            result.add(new Category(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    public static String appKey(Context context, AppInfo app) {
        return app.componentName.flattenToString() + "#"
                + UserCache.INSTANCE.get(context).getSerialNumberForUser(app.user);
    }

    private static String profileKey(Context context, List<AppInfo> apps) {
        UserCache users = UserCache.INSTANCE.get(context);
        android.os.UserHandle user = users.getUserInfo(apps.get(0).user).isWork()
                ? apps.get(0).user : android.os.Process.myUserHandle();
        return Long.toString(users.getSerialNumberForUser(user));
    }

    private static JSONObject readFavorites(Context context) {
        try {
            return new JSONObject(LauncherPrefs.CADDY_FAVORITES.get(context));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public static Set<String> favorites(Context context, List<AppInfo> apps) {
        Set<String> result = new LinkedHashSet<>();
        if (apps.isEmpty()) {
            return result;
        }
        Set<String> available = new java.util.HashSet<>();
        for (AppInfo app : apps) {
            available.add(appKey(context, app));
        }
        JSONArray saved = readFavorites(context).optJSONArray(profileKey(context, apps));
        if (saved != null) {
            for (int i = 0; i < saved.length() && result.size() < FAVORITE_COUNT; i++) {
                String key = saved.optString(i);
                if (available.contains(key)) {
                    result.add(key);
                }
            }
        }
        return result;
    }

    public static void saveFavorites(Context context, List<AppInfo> apps, Set<String> keys) {
        if (apps.isEmpty()) {
            return;
        }
        JSONObject saved = readFavorites(context);
        try {
            saved.put(profileKey(context, apps), new JSONArray(new ArrayList<>(keys)));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        LauncherPrefs.getPrefs(context).edit()
                .putString(LauncherPrefs.CADDY_FAVORITES.getSharedPrefKey(), saved.toString()).apply();
    }

    public static List<AppInfo> suggestions(Context context, List<AppInfo> apps,
            Map<PackageUserKey, Double> scores) {
        return rankSuggestions(apps, favorites(context, apps), scores,
                app -> appKey(context, app));
    }

    static List<AppInfo> rankSuggestions(List<AppInfo> apps, Set<String> favorites,
            Map<PackageUserKey, Double> scores,
            java.util.function.Function<AppInfo, String> keyForApp) {
        List<AppInfo> result = new ArrayList<>();
        Set<PackageUserKey> selectedPackages = new HashSet<>();
        for (String key : favorites) {
            for (AppInfo app : apps) {
                if (result.size() == FAVORITE_COUNT) return List.copyOf(result);
                if (key.equals(keyForApp.apply(app))) {
                    result.add(app);
                    selectedPackages.add(PackageUserKey.fromItemInfo(app));
                    break;
                }
            }
        }
        List<AppInfo> ranked = new ArrayList<>(apps);
        LabelComparator labels = new LabelComparator();
        ranked.sort((a, b) -> {
            int order = Double.compare(scores.getOrDefault(PackageUserKey.fromItemInfo(b), 0d),
                    scores.getOrDefault(PackageUserKey.fromItemInfo(a), 0d));
            if (order != 0) return order;
            order = labels.compare(sortingTitle(a), sortingTitle(b));
            return order != 0 ? order : keyForApp.apply(a).compareTo(keyForApp.apply(b));
        });
        for (AppInfo app : ranked) {
            if (result.size() == FAVORITE_COUNT) {
                break;
            }
            if (selectedPackages.add(PackageUserKey.fromItemInfo(app))) {
                result.add(app);
            }
        }
        return List.copyOf(result);
    }

    private static String sortingTitle(AppInfo app) {
        CharSequence title = app.appTitle != null && app.appTitle.length() > 0
                ? app.appTitle : app.title;
        return title == null ? "" : title.toString();
    }
}
