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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Utility functions for working with app lists.
 */
public class AppsListUtils {

    private static final Map<PackageUserKey, ApplicationInfo> sPackageInfoCache =
            new java.util.HashMap<>();

    private static final Map<String, Integer> LIBRARY_CATEGORIES = new java.util.HashMap<>();

    static {
        addLibraryPackages(R.string.caddy_utilities,
                "com.android.settings", "com.android.vending", "com.android.chrome",
                "com.android.calculator2", "com.google.android.calculator", "com.android.deskclock",
                "com.google.android.deskclock", "com.google.android.googlequicksearchbox",
                "com.google.android.apps.nbu.files", "com.android.documentsui",
                "com.google.android.documentsui", "org.mozilla.firefox", "com.brave.browser",
                "com.sec.android.app.sbrowser", "com.microsoft.emmx", "com.android.camera2",
                "com.android.camera", "com.google.android.GoogleCamera", "org.lineageos.aperture");
        addLibraryPackages(R.string.social_category_label,
                "com.google.android.dialer", "com.android.dialer", "com.android.contacts",
                "com.google.android.contacts", "com.google.android.apps.messaging",
                "com.android.messaging", "org.thoughtcrime.securesms", "com.whatsapp.w4b",
                "com.google.android.gm", "com.microsoft.office.outlook", "com.buz.app");
        addLibraryPackages(R.string.caddy_games,
                "com.firsttouchgames.dls7", "com.kiloo.subwaysurf", "com.roblox.client",
                "com.mojang.minecraftpe", "com.supercell.clashofclans", "com.supercell.clashroyale",
                "com.supercell.brawlstars", "com.pubg.imobile", "com.tencent.ig",
                "com.dts.freefireth", "com.dts.freefiremax", "com.activision.callofduty.shooter",
                "com.valvesoftware.android.steam.community", "com.epicgames.portal");
        addLibraryPackages(R.string.caddy_lifestyle,
                "com.application.zomato", "com.application.zomato.district", "in.swiggy.android",
                "com.grofers.customerapp", "com.zeptoconsumerapp", "com.phonegap.rxpal",
                "com.amazon.mShop.android.shopping", "com.flipkart.android", "com.myntra.android",
                "com.ubercab", "com.olacabs.customer",
                "com.Dominos", "com.strava", "com.google.android.apps.fitness",
                "com.sec.android.app.shealth", "com.nike.plusgps", "com.nike.omega");
        addLibraryPackages(R.string.caddy_finance,
                "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm",
                "com.paypal.android.p2pmobile", "com.revolut.revolut", "com.nextbillion.groww",
                "com.zerodha.kite3", "com.dreamplug.androidapp",
                "com.google.android.apps.walletnfcrel", "com.mobikwik_new", "com.freecharge.android",
                "com.axis.mobile", "com.sbi.lotusintouch", "com.csam.icici.bank.imobile",
                "com.snapwork.hdfc", "com.myairtelapp", "com.jio.myjio", "com.mventus.selfcare.activity");
        addLibraryPackages(R.string.productivity_category_label,
                "com.google.android.calendar", "com.android.calendar", "com.google.android.keep",
                "com.google.android.apps.docs", "com.google.android.apps.docs.editors.docs",
                "com.google.android.apps.docs.editors.sheets", "com.google.android.apps.docs.editors.slides");
        addLibraryPackages(R.string.caddy_entertainment,
                "com.jio.media.ondemand", "in.startv.hotstar", "com.jio.media.jiobeats",
                "com.bsbportal.music", "com.apple.android.music");
        addLibraryPackages(R.string.caddy_multimedia,
                "com.google.android.apps.photos", "com.android.gallery3d", "org.lineageos.gallery",
                "com.niksoftware.snapseed", "com.canva.editor", "com.lemon.lvoverseas");
        addLibraryPackages(R.string.caddy_travel,
                "com.google.android.apps.maps", "com.waze", "com.here.app.maps",
                "net.osmand", "net.osmand.plus", "com.mapswithme.maps.pro");
    }

    private static void addLibraryPackages(int category, String... packages) {
        for (String packageName : packages) {
            LIBRARY_CATEGORIES.put(packageName.toLowerCase(Locale.ROOT), category);
        }
    }
    
    /**
     * Clears the package info cache. Should be called when apps are updated/installed
     * to ensure fresh ApplicationInfo is used for categorization.
     */
    public static void clearPackageInfoCache() {
        sPackageInfoCache.clear();
    }
    
    // Package name sets for popular apps in each category
    private static final Set<String> PRODUCTIVITY_PACKAGES = new HashSet<>();
    private static final Set<String> NEWS_PACKAGES = new HashSet<>();
    private static final Set<String> SOCIAL_PACKAGES = new HashSet<>();
    private static final Set<String> AUDIO_PACKAGES = new HashSet<>();
    private static final Set<String> VIDEO_PACKAGES = new HashSet<>();
    private static final Set<String> IMAGE_PACKAGES = new HashSet<>();
    
    static {
        // Popular Productivity apps
        PRODUCTIVITY_PACKAGES.add("com.microsoft.office.excel");
        PRODUCTIVITY_PACKAGES.add("com.microsoft.office.powerpoint");
        PRODUCTIVITY_PACKAGES.add("com.microsoft.office.word");
        PRODUCTIVITY_PACKAGES.add("com.microsoft.office.onenote");
        PRODUCTIVITY_PACKAGES.add("com.microsoft.todos");
        PRODUCTIVITY_PACKAGES.add("com.microsoft.planner");
        PRODUCTIVITY_PACKAGES.add("com.microsoft.translator");
        PRODUCTIVITY_PACKAGES.add("com.adobe.reader");
        PRODUCTIVITY_PACKAGES.add("com.dropbox.android");
        PRODUCTIVITY_PACKAGES.add("com.evernote");
        PRODUCTIVITY_PACKAGES.add("com.todoist");
        PRODUCTIVITY_PACKAGES.add("com.anydo");
        PRODUCTIVITY_PACKAGES.add("com.wunderkinder.wunderlistandroid");
        PRODUCTIVITY_PACKAGES.add("com.asana.app");
        PRODUCTIVITY_PACKAGES.add("com.notion.id");
        PRODUCTIVITY_PACKAGES.add("com.ideashower.readitlater.pro");
        PRODUCTIVITY_PACKAGES.add("com.simplenote");
        
        // Popular News apps
        NEWS_PACKAGES.add("com.nytimes.android");
        NEWS_PACKAGES.add("com.washingtonpost.android");
        NEWS_PACKAGES.add("com.guardian");
        NEWS_PACKAGES.add("com.foxnews.android");
        NEWS_PACKAGES.add("com.cnn.mobile.android.phone");
        NEWS_PACKAGES.add("com.bbc.news");
        NEWS_PACKAGES.add("bbc.mobile.news.ww");
        NEWS_PACKAGES.add("flipboard.app");
        NEWS_PACKAGES.add("com.reddit.frontpage");
        NEWS_PACKAGES.add("com.google.android.apps.magazines");
        NEWS_PACKAGES.add("com.microsoft.amp.apps.bingnews");
        NEWS_PACKAGES.add("com.medium.reader");
        NEWS_PACKAGES.add("com.devhd.feedly");
        NEWS_PACKAGES.add("com.innologica.inoreader");
        NEWS_PACKAGES.add("wsj.reader_sp");
        NEWS_PACKAGES.add("com.bloomberg.android.plus");
        
        // Popular Social apps
        SOCIAL_PACKAGES.add("com.facebook.katana");
        SOCIAL_PACKAGES.add("com.facebook.lite");
        SOCIAL_PACKAGES.add("com.facebook.orca");
        SOCIAL_PACKAGES.add("com.instagram.android");
        SOCIAL_PACKAGES.add("com.twitter.android");
        SOCIAL_PACKAGES.add("com.snapchat.android");
        SOCIAL_PACKAGES.add("com.pinterest");
        SOCIAL_PACKAGES.add("com.linkedin.android");
        SOCIAL_PACKAGES.add("com.tumblr");
        SOCIAL_PACKAGES.add("com.reddit.frontpage");
        SOCIAL_PACKAGES.add("com.vkontakte.android");
        SOCIAL_PACKAGES.add("com.kakao.talk");
        SOCIAL_PACKAGES.add("com.whatsapp");
        SOCIAL_PACKAGES.add("com.telegram.messenger");
        SOCIAL_PACKAGES.add("org.telegram.messenger");
        SOCIAL_PACKAGES.add("org.telegram.messenger.web");
        SOCIAL_PACKAGES.add("org.telegram.plus");
        SOCIAL_PACKAGES.add("org.thunderdog.challegram");
        SOCIAL_PACKAGES.add("org.telegram");
        SOCIAL_PACKAGES.add("ru.mail.mailapp");
        SOCIAL_PACKAGES.add("com.discord");
        SOCIAL_PACKAGES.add("com.slack");
        SOCIAL_PACKAGES.add("tv.periscope.android");
        SOCIAL_PACKAGES.add("com.twitter");
        SOCIAL_PACKAGES.add("com.facebook.messenger");
        SOCIAL_PACKAGES.add("com.zhiliaoapp.musically");
        
        // Popular Audio apps
        AUDIO_PACKAGES.add("com.spotify.music");
        AUDIO_PACKAGES.add("com.pandora.android");
        AUDIO_PACKAGES.add("com.soundcloud.android");
        AUDIO_PACKAGES.add("com.spotify.lite");
        AUDIO_PACKAGES.add("com.shazam.android");
        AUDIO_PACKAGES.add("com.google.android.apps.youtube.music");
        AUDIO_PACKAGES.add("com.amazon.mp3");
        AUDIO_PACKAGES.add("com.deezer.android.app");
        AUDIO_PACKAGES.add("com.tunein.player");
        AUDIO_PACKAGES.add("au.com.shiftyjelly.pocketcasts");
        AUDIO_PACKAGES.add("com.bambuna.podcastaddict");
        AUDIO_PACKAGES.add("com.sticher.player");
        AUDIO_PACKAGES.add("fm.castbox.audiobook.radio.podcast");
        
        // Popular Video apps
        VIDEO_PACKAGES.add("com.google.android.youtube");
        VIDEO_PACKAGES.add("com.netflix.mediaclient");
        VIDEO_PACKAGES.add("com.amazon.avod.thirdpartyclient");
        VIDEO_PACKAGES.add("com.hulu.plus");
        VIDEO_PACKAGES.add("com.disney.disneyplus");
        VIDEO_PACKAGES.add("com.hbo.hbonow");
        VIDEO_PACKAGES.add("com.vudu.rentalstore.android");
        VIDEO_PACKAGES.add("com.showtime.showtimeanytime");
        VIDEO_PACKAGES.add("tv.twitch.android.app");
        VIDEO_PACKAGES.add("com.vimeo.android.videoapp");
        VIDEO_PACKAGES.add("com.cbs.app");
        VIDEO_PACKAGES.add("com.nbcuni.com.nbcsports.liveextra");
        VIDEO_PACKAGES.add("com.foxnext.android.foxsportsgo");
        VIDEO_PACKAGES.add("com.plexapp.android");
        VIDEO_PACKAGES.add("com.dailymotion.dailymotion");
        
        // Popular Image apps
        IMAGE_PACKAGES.add("com.adobe.photoshop");
        IMAGE_PACKAGES.add("com.adobe.lightroom");
        IMAGE_PACKAGES.add("com.snapchat.android");
        IMAGE_PACKAGES.add("com.instagram.android");
        IMAGE_PACKAGES.add("com.pinterest");
        IMAGE_PACKAGES.add("com.imgur.mobile");
        IMAGE_PACKAGES.add("com.flickr.android");
        IMAGE_PACKAGES.add("com.pixlr.express");
        IMAGE_PACKAGES.add("com.over.image");
        IMAGE_PACKAGES.add("com.picsart.studio");
        IMAGE_PACKAGES.add("com.burbn.hipstamatic");
        IMAGE_PACKAGES.add("com.vsco.cam");
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
        if (packageName == null || packageName.isEmpty()) {
            return null;
        }
        
        // Use Locale.ROOT for package name comparison as package names are locale-independent
        // Trim to handle any potential whitespace issues
        String lowerPackageName = packageName.trim().toLowerCase(Locale.ROOT);
        Integer libraryCategory = LIBRARY_CATEGORIES.get(lowerPackageName);
        if (libraryCategory != null) {
            return context.getString(libraryCategory);
        }
        
        // Check each category's package set (order matters - check most specific first)
        // Check SOCIAL before IMAGE since some apps like Instagram/Pinterest are in both
        if (SOCIAL_PACKAGES.contains(lowerPackageName)) {
            return context.getString(R.string.social_category_label);
        }
        if (PRODUCTIVITY_PACKAGES.contains(lowerPackageName)) {
            return context.getString(R.string.productivity_category_label);
        }
        if (NEWS_PACKAGES.contains(lowerPackageName)) {
            return context.getString(R.string.news_category_label);
        }
        if (AUDIO_PACKAGES.contains(lowerPackageName)) {
            return context.getString(R.string.caddy_entertainment);
        }
        if (VIDEO_PACKAGES.contains(lowerPackageName)) {
            return context.getString(R.string.caddy_entertainment);
        }
        if (IMAGE_PACKAGES.contains(lowerPackageName)) {
            return context.getString(R.string.caddy_multimedia);
        }
        
        return null;
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

