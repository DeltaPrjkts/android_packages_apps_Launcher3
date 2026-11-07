package com.android.launcher3.search.universal;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.R;
import com.android.launcher3.views.ActivityContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

public class UniversalSearchResults {

    private static final int[] DEFAULT_ORDER = {
            UniversalSearchResult.SOURCE_CALCULATOR,
            UniversalSearchResult.SOURCE_QUICK_ACTION,
            UniversalSearchResult.SOURCE_CONVERSATION,
            UniversalSearchResult.SOURCE_SHORTCUT,
            UniversalSearchResult.SOURCE_CONTACT,
            UniversalSearchResult.SOURCE_PHOTO,
            UniversalSearchResult.SOURCE_QS_TILE,
            UniversalSearchResult.SOURCE_SETTING,
            UniversalSearchResult.SOURCE_APP_CONTENT,
            UniversalSearchResult.SOURCE_FILE,
            UniversalSearchResult.SOURCE_EVENT,
            UniversalSearchResult.SOURCE_WEB_SUGGESTION,
            UniversalSearchResult.SOURCE_WEB,
    };

    private static final String SETTINGS_PACKAGE = "com.android.settings";

    /** Filter chips above the results; a source of -1 means everything. */
    public record Filters(List<Integer> sources, int selected, IntConsumer onSelect) {}

    /** Section order from the user's preference, with any sources it predates appended. */
    public static List<Integer> sectionOrder(Context context) {
        List<Integer> order = new ArrayList<>();
        for (String id : LauncherPrefs.SEARCH_SECTION_ORDER.get(context).split(",")) {
            try {
                int source = Integer.parseInt(id.trim());
                if (contains(DEFAULT_ORDER, source) && !order.contains(source)) {
                    order.add(source);
                }
            } catch (NumberFormatException e) {
            }
        }
        for (int source : DEFAULT_ORDER) {
            if (!order.contains(source)) {
                order.add(source);
            }
        }
        return order;
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    public static String getSectionTitle(Context context, int source) {
        switch (source) {
            case UniversalSearchResult.SOURCE_APP:
                return context.getString(R.string.search_section_apps);
            case UniversalSearchResult.SOURCE_CONVERSATION:
                return context.getString(R.string.search_section_conversations);
            case UniversalSearchResult.SOURCE_SHORTCUT:
                return context.getString(R.string.search_section_shortcuts);
            case UniversalSearchResult.SOURCE_CONTACT:
                return context.getString(R.string.search_section_contacts);
            case UniversalSearchResult.SOURCE_QS_TILE:
                return context.getString(R.string.search_section_qs_tiles);
            case UniversalSearchResult.SOURCE_CALCULATOR:
                return context.getString(R.string.search_section_calculator);
            case UniversalSearchResult.SOURCE_FILE:
                return context.getString(R.string.search_section_files);
            case UniversalSearchResult.SOURCE_EVENT:
                return context.getString(R.string.search_section_events);
            case UniversalSearchResult.SOURCE_WEB:
                return context.getString(R.string.search_section_actions);
            case UniversalSearchResult.SOURCE_SETTING:
                return context.getString(R.string.search_section_settings);
            case UniversalSearchResult.SOURCE_QUICK_ACTION:
                return context.getString(R.string.search_section_quick_actions);
            case UniversalSearchResult.SOURCE_APP_CONTENT:
                return context.getString(R.string.search_section_app_content);
            case UniversalSearchResult.SOURCE_WEB_SUGGESTION:
                return context.getString(R.string.search_section_web_suggestions);
            case UniversalSearchResult.SOURCE_HISTORY:
                return context.getString(R.string.search_section_history);
            case UniversalSearchResult.SOURCE_PHOTO:
                return context.getString(R.string.search_section_photos);
            case UniversalSearchResult.SOURCE_SCREENSHOT:
                return context.getString(R.string.search_section_screenshots);
            case UniversalSearchResult.SOURCE_RECENT_DOWNLOADS:
                return context.getString(R.string.search_section_recent_downloads);
            default:
                return "";
        }
    }

    private static int getFallbackIcon(int source) {
        switch (source) {
            case UniversalSearchResult.SOURCE_CONVERSATION:
                return R.drawable.ic_search_conversation;
            case UniversalSearchResult.SOURCE_CONTACT:
                return R.drawable.ic_search_contact;
            case UniversalSearchResult.SOURCE_QS_TILE:
            case UniversalSearchResult.SOURCE_SETTING:
                return R.drawable.ic_setting;
            case UniversalSearchResult.SOURCE_CALCULATOR:
                return R.drawable.ic_search_calculator;
            case UniversalSearchResult.SOURCE_FILE:
            case UniversalSearchResult.SOURCE_RECENT_DOWNLOADS:
                return R.drawable.ic_search_file;
            case UniversalSearchResult.SOURCE_EVENT:
                return R.drawable.ic_search_event;
            case UniversalSearchResult.SOURCE_WEB:
            case UniversalSearchResult.SOURCE_QUICK_ACTION:
            case UniversalSearchResult.SOURCE_WEB_SUGGESTION:
                return R.drawable.ic_search_web;
            case UniversalSearchResult.SOURCE_HISTORY:
                return R.drawable.ic_search_history;
            default:
                return R.drawable.ic_search_conversation;
        }
    }

    private static boolean highlights(UniversalSearchResult result) {
        if (result.isPlaceholder()) {
            return false;
        }
        switch (result.source) {
            case UniversalSearchResult.SOURCE_WEB:
            case UniversalSearchResult.SOURCE_QUICK_ACTION:
            case UniversalSearchResult.SOURCE_CALCULATOR:
            case UniversalSearchResult.SOURCE_HISTORY:
            case UniversalSearchResult.SOURCE_PHOTO:
                return false;
            default:
                return true;
        }
    }

    public static void bindThumbnails(ActivityContext activityContext, View view,
            List<UniversalSearchResult> results) {
        ViewGroup group = view.findViewById(R.id.search_thumbnails_group);
        group.removeAllViews();
        if (results == null) {
            return;
        }
        int size = view.getResources().getDimensionPixelSize(R.dimen.search_thumbnail_size);
        int gap = view.getResources().getDimensionPixelSize(R.dimen.search_thumbnail_gap);
        float radius = view.getResources().getDimension(R.dimen.search_thumbnail_strip_radius);
        for (UniversalSearchResult result : results) {
            ImageView thumb = new ImageView(view.getContext());
            ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(size, size);
            lp.setMarginEnd(gap);
            thumb.setLayoutParams(lp);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setImageDrawable(result.icon);
            thumb.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline outline) {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            thumb.setClipToOutline(true);
            thumb.setForeground(view.getContext().getDrawable(
                    android.R.drawable.list_selector_background));
            thumb.setContentDescription(TextUtils.isEmpty(result.title)
                    ? view.getContext().getString(R.string.search_photo) : result.title);
            thumb.setFocusable(true);
            thumb.setOnClickListener(v -> launch(activityContext, v, result));
            group.addView(thumb);
        }
    }

    public static void bindFilters(View view, Filters filters) {
        ViewGroup group = view.findViewById(R.id.search_filters_group);
        if (filters == null) {
            group.removeAllViews();
            group.setTag(null);
            return;
        }
        boolean reuse = filters.sources().equals(group.getTag())
                && group.getChildCount() == filters.sources().size();
        if (!reuse) {
            group.removeAllViews();
            group.setTag(new ArrayList<>(filters.sources()));
        }
        LayoutInflater inflater = LayoutInflater.from(view.getContext());
        for (int i = 0; i < filters.sources().size(); i++) {
            int source = filters.sources().get(i);
            TextView chip = reuse ? (TextView) group.getChildAt(i)
                    : (TextView) inflater.inflate(R.layout.search_filter_chip, group, false);
            chip.setText(source < 0
                    ? view.getContext().getString(R.string.search_filter_all)
                    : getSectionTitle(view.getContext(), source));
            SearchResultTheme.bindChip(chip, source == filters.selected());
            chip.setFocusable(true);
            chip.setOnClickListener(v -> filters.onSelect().accept(source));
            if (!reuse) {
                group.addView(chip);
            }
        }
    }

    public static void bind(ActivityContext activityContext, View view,
            UniversalSearchResult result) {
        if (result == null) {
            return;
        }
        SearchResultTheme.bindRow(view);
        ImageView icon = view.findViewById(R.id.search_result_icon);
        TextView title = view.findViewById(R.id.search_result_title);
        TextView subtitle = view.findViewById(R.id.search_result_subtitle);

        title.setText(highlights(result)
                ? FuzzyMatcher.highlight(result.query, result.title) : result.title);
        if (TextUtils.isEmpty(result.subtitle)) {
            subtitle.setVisibility(View.GONE);
        } else {
            subtitle.setVisibility(View.VISIBLE);
            subtitle.setText(result.subtitle);
        }
        View call = view.findViewById(R.id.search_result_call);
        if (result.phoneNumber == null) {
            call.setVisibility(View.GONE);
        } else {
            call.setVisibility(View.VISIBLE);
            call.setOnClickListener(v -> {
                Intent dial = new Intent(Intent.ACTION_DIAL,
                        Uri.fromParts("tel", result.phoneNumber, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                SearchHistory.recordLaunch(v.getContext(), SearchHistory.resultKey(result));
                activityContext.startActivitySafely(v, dial, null);
            });
        }
        ImageView message = view.findViewById(R.id.search_result_message);
        message.setVisibility(result.phoneNumber == null ? View.GONE : View.VISIBLE);
        message.setOnClickListener(v -> {
            SearchHistory.recordLaunch(v.getContext(), SearchHistory.resultKey(result));
            activityContext.startActivitySafely(v, new Intent(Intent.ACTION_SENDTO,
                    Uri.fromParts("smsto", result.phoneNumber, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null);
        });
        ImageView whatsapp = view.findViewById(R.id.search_result_whatsapp);
        whatsapp.setVisibility(result.whatsapp == null ? View.GONE : View.VISIBLE);
        if (result.whatsapp != null) {
            try {
                whatsapp.setImageDrawable(view.getContext().getPackageManager()
                        .getApplicationIcon(result.whatsapp.getPackage()));
            } catch (PackageManager.NameNotFoundException e) {
                whatsapp.setVisibility(View.GONE);
            }
            whatsapp.setOnClickListener(v -> {
                SearchHistory.recordLaunch(v.getContext(), SearchHistory.resultKey(result));
                activityContext.startActivitySafely(v, result.whatsapp, null);
            });
        }
        ImageView remove = view.findViewById(R.id.search_result_remove);
        remove.setVisibility(result.onRemove == null ? View.GONE : View.VISIBLE);
        remove.setOnClickListener(result.onRemove == null ? null : v -> result.onRemove.run());
        applyIcon(icon, result);
        CompoundButton toggle = view.findViewById(R.id.search_result_switch);
        if (result.toggle == null) {
            view.setStateDescription(null);
            result.onStateChanged = null;
            toggle.setVisibility(View.GONE);
            view.setOnClickListener(v -> launch(activityContext, v, result));
        } else {
            toggle.setVisibility(View.VISIBLE);
            result.onStateChanged = () -> {
                toggle.setChecked(result.checked);
                view.setStateDescription(view.getContext().getString(result.checked
                        ? R.string.search_toggle_on : R.string.search_toggle_off));
            };
            result.onStateChanged.run();
            view.setOnClickListener(v -> launch(activityContext, v, result));
        }
        boolean hasMenu = !result.isPlaceholder() && buildMenu(null, result);
        view.setOnLongClickListener(hasMenu ? v -> {
            showMenu(activityContext, v, result);
            return true;
        } : null);
        view.setLongClickable(hasMenu);
    }

    private static final int MENU_COPY = 1;
    private static final int MENU_CALL = 2;
    private static final int MENU_MESSAGE = 3;
    private static final int MENU_COPY_NUMBER = 4;
    private static final int MENU_SHARE = 5;
    private static final int MENU_OPEN_WITH = 6;
    private static final int MENU_OPEN_SETTINGS = 7;
    private static final int MENU_ADD_TO_HOME = 8;
    private static final int MENU_APP_INFO = 9;

    /** Fills menu (when given) and returns whether this result has anything to offer. */
    private static boolean buildMenu(Menu menu, UniversalSearchResult result) {
        List<int[]> items = new ArrayList<>();
        if (result.copyText != null) {
            items.add(new int[]{MENU_COPY, R.string.search_menu_copy});
        }
        if (result.phoneNumber != null) {
            items.add(new int[]{MENU_CALL, R.string.search_menu_call});
            items.add(new int[]{MENU_MESSAGE, R.string.search_menu_message});
            items.add(new int[]{MENU_COPY_NUMBER, R.string.search_menu_copy_number});
        }
        if ((result.source == UniversalSearchResult.SOURCE_FILE
                || result.source == UniversalSearchResult.SOURCE_RECENT_DOWNLOADS)
                && result.intent != null) {
            items.add(new int[]{MENU_SHARE, R.string.search_menu_share});
            items.add(new int[]{MENU_OPEN_WITH, R.string.search_menu_open_with});
        }
        boolean setting = result.source == UniversalSearchResult.SOURCE_SETTING
                || result.source == UniversalSearchResult.SOURCE_QS_TILE;
        if (setting && result.intent != null) {
            if (result.toggle != null) {
                items.add(new int[]{MENU_OPEN_SETTINGS, R.string.search_menu_open_settings});
            }
            items.add(new int[]{MENU_ADD_TO_HOME, R.string.search_menu_add_to_home});
        }
        if (result.packageName != null && (result.source == UniversalSearchResult.SOURCE_SHORTCUT
                || result.source == UniversalSearchResult.SOURCE_CONVERSATION
                || result.source == UniversalSearchResult.SOURCE_APP_CONTENT)) {
            items.add(new int[]{MENU_APP_INFO, R.string.search_menu_app_info});
        }
        if (menu != null) {
            for (int[] item : items) {
                menu.add(Menu.NONE, item[0], Menu.NONE, item[1]);
            }
        }
        return !items.isEmpty();
    }

    private static void showMenu(ActivityContext activityContext, View anchor,
            UniversalSearchResult result) {
        PopupMenu popup = new PopupMenu(anchor.getContext(), anchor);
        buildMenu(popup.getMenu(), result);
        popup.setOnMenuItemClickListener(item -> {
            onMenuItem(activityContext, anchor, result, item.getItemId());
            return true;
        });
        popup.show();
    }

    private static void onMenuItem(ActivityContext activityContext, View view,
            UniversalSearchResult result, int id) {
        Context context = view.getContext();
        Intent intent = null;
        switch (id) {
            case MENU_COPY:
                copy(context, result.copyText);
                return;
            case MENU_COPY_NUMBER:
                copy(context, result.phoneNumber);
                return;
            case MENU_CALL:
                intent = new Intent(Intent.ACTION_DIAL,
                        Uri.fromParts("tel", result.phoneNumber, null));
                break;
            case MENU_MESSAGE:
                intent = new Intent(Intent.ACTION_SENDTO,
                        Uri.fromParts("smsto", result.phoneNumber, null));
                break;
            case MENU_SHARE: {
                Intent send = new Intent(Intent.ACTION_SEND)
                        .setType(result.intent.getType() == null ? "*/*"
                                : result.intent.getType())
                        .putExtra(Intent.EXTRA_STREAM, result.intent.getData())
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent = Intent.createChooser(send, null);
                break;
            }
            case MENU_OPEN_WITH:
                intent = Intent.createChooser(new Intent(result.intent), null);
                break;
            case MENU_OPEN_SETTINGS:
                intent = result.intent;
                break;
            case MENU_ADD_TO_HOME:
                pinToHome(context, result);
                return;
            case MENU_APP_INFO:
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", result.packageName, null));
                break;
            default:
                return;
        }
        activityContext.startActivitySafely(view,
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null);
    }

    private static void copy(Context context, String text) {
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(null, text));
        }
    }

    private static void pinToHome(Context context, UniversalSearchResult result) {
        ShortcutManager shortcuts = context.getSystemService(ShortcutManager.class);
        if (shortcuts == null || !shortcuts.isRequestPinShortcutSupported()) {
            Toast.makeText(context, R.string.search_pin_unsupported, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(result.intent);
        if (intent.getAction() == null) {
            intent.setAction(Intent.ACTION_MAIN);
        }
        Icon icon = settingsIcon(context);
        ShortcutInfo.Builder builder = new ShortcutInfo.Builder(context,
                "search:" + result.source + ":" + result.id)
                .setShortLabel(result.title.toString())
                .setIntent(intent);
        if (icon != null) {
            builder.setIcon(icon);
        }
        try {
            shortcuts.requestPinShortcut(builder.build(), null);
        } catch (IllegalArgumentException | IllegalStateException e) {
            Toast.makeText(context, R.string.search_pin_unsupported, Toast.LENGTH_SHORT).show();
        }
    }

    private static Icon settingsIcon(Context context) {
        Drawable drawable;
        try {
            drawable = context.getPackageManager().getApplicationIcon(SETTINGS_PACKAGE);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        int size = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 192;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(new Canvas(bitmap));
        return Icon.createWithBitmap(bitmap);
    }

    private static void applyIcon(ImageView icon, UniversalSearchResult result) {
        if (result.icon == null) {
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setClipToOutline(false);
            icon.setOutlineProvider(null);
            icon.setImageTintList(result.iconRes == R.drawable.ic_search_engine_google
                    ? null : android.content.res.ColorStateList.valueOf(
                            SearchResultTheme.textColor(icon.getContext())));
            icon.setImageResource(result.iconRes != 0 ? result.iconRes
                    : getFallbackIcon(result.source));
            return;
        }
        icon.setImageTintList(null);
        icon.setImageDrawable(result.icon);
        if (!result.thumbnail) {
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setClipToOutline(false);
            icon.setOutlineProvider(null);
            return;
        }
        boolean circle = result.source == UniversalSearchResult.SOURCE_CONTACT;
        float radius = circle ? -1
                : icon.getResources().getDimension(R.dimen.search_thumbnail_radius);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                int size = Math.min(view.getWidth(), view.getHeight());
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        radius < 0 ? size / 2f : radius);
            }
        });
        icon.setClipToOutline(true);
    }

    public static boolean launchItem(ActivityContext activityContext, View view, ItemInfo item) {
        if (view.getWindowToken() == null) {
            return false;
        }
        Object previousTag = view.getTag();
        try {
            view.setTag(item);
            activityContext.getItemOnClickListener().onClick(view);
            return true;
        } finally {
            view.setTag(previousTag);
        }
    }

    public static boolean launch(ActivityContext activityContext, View view,
            UniversalSearchResult result) {
        if (result.onTap != null) {
            result.onTap.run();
            return true;
        }
        Context context = activityContext.asContext();
        if (!SearchProvider.isPackageSearchable(context, result.packageName)) {
            return false;
        }
        if (result.itemInfo != null) {
            SearchHistory.recordLaunch(context, SearchHistory.resultKey(result));
            return launchItem(activityContext, view, result.itemInfo);
        }
        if (result.toggle != null && result.toggle.set(context, !result.checked)) {
            result.checked = !result.checked;
            if (result.onStateChanged != null) {
                result.onStateChanged.run();
            }
            SearchHistory.recordLaunch(context, SearchHistory.resultKey(result));
            return true;
        }
        if (!result.isPlaceholder()) {
            SearchHistory.recordLaunch(context, SearchHistory.resultKey(result));
        }
        if (result.intent != null) {
            return activityContext.startActivitySafely(view, result.intent, null) != null;
        }
        if (result.shortcutId == null || result.packageName == null) {
            return false;
        }
        LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
        if (launcherApps == null) {
            return false;
        }
        try {
            launcherApps.startShortcut(result.packageName, result.shortcutId, null, null,
                    result.user);
            return true;
        } catch (SecurityException | IllegalStateException e) {
            return false;
        }
    }
}
