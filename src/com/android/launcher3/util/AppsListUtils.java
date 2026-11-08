/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.util;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import com.android.launcher3.R;
import com.android.launcher3.model.data.AppInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Utility functions for working with app lists.
 */
public class AppsListUtils {

    private static final Map<PackageUserKey, ApplicationInfo> sPackageInfoCache =
            new java.util.HashMap<>();

    /** Clears cached metadata when apps are installed, removed or updated. */
    public static void clearPackageInfoCache() {
        sPackageInfoCache.clear();
    }

    /**
     * Categorizes apps by their application category.
     * Note: This method returns all apps categorized. Use {@link #categorizeAppsHybrid}
     * for the recommended hybrid approach that creates folders only for categories with
     * multiple apps and lists individual apps separately.
     * 
     * @param context The context
     * @param appList List of apps to categorize
     * @return Map of category names to lists of apps in that category
     */
    public static Map<String, List<AppInfo>> categorizeApps(
            Context context, List<AppInfo> appList) {
        Map<String, List<AppInfo>> categories = new TreeMap<>();
        if (appList == null) {
            return categories;
        }

        String othersLabel = context.getString(R.string.others_category_label);

        for (AppInfo appInfo : appList) {
            if (appInfo == null || appInfo.getTargetPackage() == null) {
                continue;
            }

            String packageName = appInfo.getTargetPackage();
            String packageBasedCategory = getCategoryByPackage(packageName, context);
            PackageUserKey key = new PackageUserKey(packageName, appInfo.user);
            ApplicationInfo applicationInfo = sPackageInfoCache.get(key);
            
            if (applicationInfo == null && packageBasedCategory == null) {
                try {
                    applicationInfo = new ApplicationInfoWrapper(
                            context, packageName, appInfo.user).getInfo();
                    if (applicationInfo != null) {
                        sPackageInfoCache.put(key, applicationInfo);
                    }
                } catch (Exception e) {
                    // Package not found or inaccessible
                }
            }

            String categoryTitle = othersLabel;
            
            // First, try to categorize by package name (most reliable)
            if (packageBasedCategory != null) {
                categoryTitle = packageBasedCategory;
            } else if (applicationInfo != null) {
                // Fall back to ApplicationInfo.category if package name didn't match
                categoryTitle = getCategoryTitle(context,
                        (applicationInfo.flags & ApplicationInfo.FLAG_IS_GAME) != 0
                                ? ApplicationInfo.CATEGORY_GAME : applicationInfo.category);
                if (categoryTitle == null) {
                    categoryTitle = (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                            ? context.getString(R.string.caddy_utilities) : othersLabel;
                }
            }

            List<AppInfo> categoryList = categories.get(categoryTitle);
            if (categoryList == null) {
                categoryList = new ArrayList<>();
                categories.put(categoryTitle, categoryList);
            }
            if (!categoryList.contains(appInfo)) {
                categoryList.add(appInfo);
            }
        }

        return categories;
    }

    /**
     * Categorizes apps into System Apps, Google Apps, and other categorized apps.
     * This prioritizes System Apps and Google Apps as separate folders before
     * categorizing remaining apps.
     * 
     * Note: This method returns all apps in categories. For the recommended hybrid
     * approach, use {@link #categorizeAppsHybrid} which creates folders only for
     * categories with 2+ apps and lists individual apps separately.
     *
     * @param context The context
     * @param appList List of apps to categorize
     * @return Map of category names to lists of apps in that category
     */
    public static Map<String, List<AppInfo>> categorizeAppsWithSystemAndGoogle(
            Context context, List<AppInfo> appList) {
        Map<String, List<AppInfo>> finalCategorizedApps = new TreeMap<>();
        if (appList == null) {
            return finalCategorizedApps;
        }

        List<AppInfo> systemApps = new ArrayList<>();
        List<AppInfo> googleApps = new ArrayList<>();
        List<AppInfo> otherApps = new ArrayList<>();

        for (AppInfo app : appList) {
            if (app == null || app.getTargetPackage() == null) {
                continue;
            }

            String packageName = app.getTargetPackage();

            // First, check if the app matches a specific category by package name
            // This takes priority over Google/System categorization
            String packageBasedCategory = getCategoryByPackage(packageName, context);
            if (packageBasedCategory != null) {
                // App matches a specific category, categorize it normally
                otherApps.add(app);
                continue;
            }

            // Check if it's a Google app (Google apps can also be system apps)
            if (packageName.startsWith("com.google.")) {
                googleApps.add(app);
            } else {
                // Check if it's a system app
                try {
                    ApplicationInfoWrapper appInfo = new ApplicationInfoWrapper(
                            context, packageName, app.user);
                    if (appInfo.isSystem()) {
                        systemApps.add(app);
                    } else {
                        otherApps.add(app);
                    }
                } catch (Exception e) {
                    // If we can't determine, treat as other app
                    otherApps.add(app);
                }
            }
        }

        // Add System Apps folder if not empty
        if (!systemApps.isEmpty()) {
            finalCategorizedApps.put(context.getString(R.string.system_apps_category_label),
                    systemApps);
        }

        // Add Google Apps folder if not empty
        if (!googleApps.isEmpty()) {
            finalCategorizedApps.put(context.getString(R.string.google_apps_category_label),
                    googleApps);
        }

        // Categorize other apps using the standard categorization
        Map<String, List<AppInfo>> categorizedOtherApps = categorizeApps(context, otherApps);
        finalCategorizedApps.putAll(categorizedOtherApps);

        return finalCategorizedApps;
    }

    /**
     * Result of hybrid categorization that separates apps into folders and individual apps.
     */
    public static class HybridCategorizationResult {
        /** Categories with 2+ apps that should become folders */
        public final Map<String, List<AppInfo>> folders;
        
        /** Individual apps (from categories with 1 app or uncategorized) */
        public final List<AppInfo> individualApps;

        public HybridCategorizationResult(Map<String, List<AppInfo>> folders,
                List<AppInfo> individualApps) {
            this.folders = folders;
            this.individualApps = individualApps;
        }
    }

    /**
     * Categorizes apps into folders (for categories with 2+ apps) and individual apps.
     * This is the default categorization mode - categories with multiple apps become
     * folders at the top, while single apps and uncategorized apps are listed individually below.
     * 
     * This hybrid approach ensures that apps we can't properly categorize don't get
     * forced into folders, maintaining a clean and usable app drawer.
     *
     * @param context The context
     * @param appList List of apps to categorize
     * @return HybridCategorizationResult containing folders and individual apps
     */
    public static HybridCategorizationResult categorizeAppsHybrid(
            Context context, List<AppInfo> appList) {
        Map<String, List<AppInfo>> folderMap = new TreeMap<>();
        List<AppInfo> individualApps = new ArrayList<>();
        
        if (appList == null || appList.isEmpty()) {
            return new HybridCategorizationResult(folderMap, individualApps);
        }

        // Use the categorization with System Apps and Google Apps
        Map<String, List<AppInfo>> categorized = categorizeAppsWithSystemAndGoogle(context, appList);

        String othersLabel = context.getString(R.string.others_category_label);

        for (Map.Entry<String, List<AppInfo>> entry : categorized.entrySet()) {
            List<AppInfo> apps = entry.getValue();
            String categoryName = entry.getKey();
            
            // Always put "Others" category apps in individual apps list, never create a folder
            if (othersLabel.equals(categoryName)) {
                individualApps.addAll(apps);
            } else if (apps.size() > 1) {
                // Category with multiple apps -> folder
                folderMap.put(categoryName, apps);
            } else if (apps.size() == 1) {
                // Category with single app -> individual app
                individualApps.add(apps.get(0));
            }
        }

        return new HybridCategorizationResult(folderMap, individualApps);
    }

    /**
     * Gets a category title by checking if the package name matches known apps in that category.
     * 
     * @param packageName The package name to check
     * @param context The context for string resources
     * @return Category title if found, or null if not found
     */
    private static String getCategoryByPackage(String packageName, Context context) {
        int category = CaddyAppCategories.getCategory(context, packageName);
        return category == 0 ? null : context.getString(category);
    }
    
    /**
     * Gets a displayable category title from an ApplicationInfo category constant.
     * Uses string resources for proper localization.
     * 
     * @param context The context
     * @param category The ApplicationInfo category constant
     * @return Displayable category title, or null if unknown (will fall back to "Others")
     */
    private static String getCategoryTitle(Context context, int category) {
        // Map ApplicationInfo category constants to localized string resources
        // These constants are defined in ApplicationInfo but may vary by Android version
        switch (category) {
            case ApplicationInfo.CATEGORY_GAME:
                return context.getString(R.string.caddy_games);
            case ApplicationInfo.CATEGORY_MAPS:
                return context.getString(R.string.caddy_travel);
            case ApplicationInfo.CATEGORY_ACCESSIBILITY:
                return context.getString(R.string.caddy_utilities);
            case ApplicationInfo.CATEGORY_UNDEFINED:
                return null; // Will fall back to "Others"
            case ApplicationInfo.CATEGORY_PRODUCTIVITY:
                return context.getString(R.string.productivity_category_label);
            case ApplicationInfo.CATEGORY_NEWS:
                return context.getString(R.string.news_category_label);
            case ApplicationInfo.CATEGORY_SOCIAL:
                return context.getString(R.string.social_category_label);
            case ApplicationInfo.CATEGORY_AUDIO:
            case ApplicationInfo.CATEGORY_VIDEO:
                return context.getString(R.string.caddy_entertainment);
            case ApplicationInfo.CATEGORY_IMAGE:
                return context.getString(R.string.caddy_multimedia);
            default:
                // For unknown categories, return null to fall back to "Others"
                // This allows for future Android versions with new categories to be handled gracefully
                return null;
        }
    }
}

