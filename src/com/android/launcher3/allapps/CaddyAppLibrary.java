package com.android.launcher3.allapps;

import android.content.Context;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.util.AppsListUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class CaddyAppLibrary {
    public static final int FAVORITE_COUNT = 4;
    private static final long SUGGESTION_SEED = new Random().nextLong();

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
                R.string.caddy_multimedia, R.string.news_category_label, R.string.caddy_travel};
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

    public static List<AppInfo> suggestions(Context context, List<AppInfo> apps) {
        List<AppInfo> result = new ArrayList<>();
        Set<String> favorites = favorites(context, apps);
        for (String key : favorites) {
            for (AppInfo app : apps) {
                if (key.equals(appKey(context, app))) {
                    result.add(app);
                    break;
                }
            }
        }
        List<AppInfo> shuffled = new ArrayList<>(apps);
        Collections.shuffle(shuffled, new Random(SUGGESTION_SEED));
        for (AppInfo app : shuffled) {
            if (result.size() == FAVORITE_COUNT) {
                break;
            }
            if (!favorites.contains(appKey(context, app))) {
                result.add(app);
            }
        }
        return List.copyOf(result);
    }
}
