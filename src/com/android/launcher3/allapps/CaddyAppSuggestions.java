package com.android.launcher3.allapps;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.ORDERED_BG_EXECUTOR;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.util.PackageUserKey;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class CaddyAppSuggestions {
    private static final String TAG = "CaddyAppSuggestions";
    static final long HISTORY_WINDOW = TimeUnit.DAYS.toMillis(30);
    private static final long REFRESH_INTERVAL = TimeUnit.MINUTES.toMillis(1);
    private static final double DAY = TimeUnit.DAYS.toMillis(1);

    private Map<PackageUserKey, Double> mScores = Map.of();
    private Set<PackageUserKey> mRequestedApps = Set.of();
    private long mLastRefresh;
    private int mGeneration;
    private boolean mLoading;
    private boolean mLoaded;

    Map<PackageUserKey, Double> getScores() {
        return mScores;
    }

    void refresh(Context context, List<AppInfo> apps, Runnable onChanged) {
        Set<PackageUserKey> requested = new HashSet<>();
        for (AppInfo app : apps) {
            requested.add(new PackageUserKey(app.componentName.getPackageName(), app.user));
        }
        boolean sameApps = requested.equals(mRequestedApps);
        long elapsed = SystemClock.elapsedRealtime();
        if (sameApps && (mLoading || (mLoaded && elapsed - mLastRefresh < REFRESH_INTERVAL))) {
            return;
        }
        mRequestedApps = requested;
        mLoading = true;
        int generation = ++mGeneration;
        Context appContext = context.getApplicationContext();
        ORDERED_BG_EXECUTOR.execute(() -> {
            Map<PackageUserKey, Double> scores = queryScores(appContext, requested,
                    System.currentTimeMillis());
            MAIN_EXECUTOR.execute(() -> {
                if (generation != mGeneration) return;
                mLoading = false;
                mLoaded = true;
                mLastRefresh = SystemClock.elapsedRealtime();
                mScores = scores;
                onChanged.run();
            });
        });
    }

    void cancel() {
        ++mGeneration;
        mLoading = false;
        mLoaded = false;
    }

    private static Map<PackageUserKey, Double> queryScores(Context context,
            Set<PackageUserKey> apps, long now) {
        Map<PackageUserKey, Double> result = new HashMap<>();
        Set<UserHandle> users = new HashSet<>();
        for (PackageUserKey app : apps) users.add(app.mUser);
        UserManager userManager = context.getSystemService(UserManager.class);
        for (UserHandle user : users) {
            try {
                if (userManager == null || !userManager.isUserUnlocked(user)
                        || userManager.isQuietModeEnabled(user)) {
                    continue;
                }
                Context userContext = context.createContextAsUser(user, 0);
                UsageStatsManager usage = userContext.getSystemService(UsageStatsManager.class);
                if (usage == null) continue;
                List<UsageStats> stats = usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,
                        now - HISTORY_WINDOW, now);
                if (stats == null) continue;
                Map<String, Double> scores = scoreUsage(stats, now);
                for (Map.Entry<String, Double> entry : scores.entrySet()) {
                    PackageUserKey key = new PackageUserKey(entry.getKey(), user);
                    if (apps.contains(key)) result.put(key, entry.getValue());
                }
            } catch (SecurityException | IllegalStateException | IllegalArgumentException e) {
                Log.d(TAG, "Usage unavailable for profile " + user, e);
            }
        }
        return Map.copyOf(result);
    }

    static Map<String, Double> scoreUsage(List<UsageStats> stats, long now) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Long> lastUsed = new HashMap<>();
        for (UsageStats day : stats) {
            String pkg = day.getPackageName();
            long last = day.getLastTimeUsed();
            if (pkg == null || last <= 0 || last > now || last < now - HISTORY_WINDOW) continue;
            int launches = Math.max(0, day.getAppLaunchCount());
            long foreground = Math.max(0, day.getTotalTimeInForeground());
            if (launches == 0 && foreground == 0) continue;
            double frequency = launches > 0 ? Math.log1p(launches)
                    : 0.25 * Math.min(1, foreground / (double) TimeUnit.MINUTES.toMillis(5));
            double ageDays = (now - last) / DAY;
            scores.merge(pkg, frequency * Math.pow(0.5, ageDays / 7), Double::sum);
            lastUsed.merge(pkg, last, Math::max);
        }
        for (Map.Entry<String, Long> entry : lastUsed.entrySet()) {
            double ageDays = (now - entry.getValue()) / DAY;
            scores.merge(entry.getKey(), 0.5 * Math.pow(0.5, ageDays / 2), Double::sum);
        }
        return scores;
    }
}
