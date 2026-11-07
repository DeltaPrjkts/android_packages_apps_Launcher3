package com.android.launcher3.search.universal;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;

import com.android.launcher3.model.data.ItemInfo;

public class UniversalSearchResult {

    public static final int SOURCE_APP = 0;
    public static final int SOURCE_SHORTCUT = 1;
    public static final int SOURCE_CONVERSATION = 2;
    public static final int SOURCE_CONTACT = 3;
    public static final int SOURCE_SETTING = 4;
    public static final int SOURCE_QS_TILE = 5;
    public static final int SOURCE_CALCULATOR = 6;
    public static final int SOURCE_FILE = 7;
    public static final int SOURCE_EVENT = 8;
    public static final int SOURCE_WEB = 9;
    public static final int SOURCE_QUICK_ACTION = 10;
    public static final int SOURCE_APP_CONTENT = 11;
    public static final int SOURCE_WEB_SUGGESTION = 12;
    public static final int SOURCE_HISTORY = 13;
    public static final int SOURCE_PHOTO = 14;
    public static final int SOURCE_SCREENSHOT = 15;
    public static final int SOURCE_RECENT_DOWNLOADS = 16;

    public final int source;
    public final String id;
    public final CharSequence title;
    public final CharSequence subtitle;
    public final Intent intent;
    public final UserHandle user;
    public int score;

    public Drawable icon;
    public int iconRes;
    public String packageName;
    public String shortcutId;
    public String phoneNumber;
    public boolean thumbnail;
    public String query;
    public String copyText;
    public Runnable onTap;
    public Runnable onRemove;
    public Intent whatsapp;
    public ItemInfo itemInfo;
    public Toggle toggle;
    public boolean checked;
    public Runnable onStateChanged;

    public interface Toggle {
        boolean set(Context context, boolean on);
    }

    public UniversalSearchResult(int source, String id, CharSequence title, CharSequence subtitle,
            Intent intent, UserHandle user, int score) {
        this.source = source;
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.intent = intent;
        this.user = user;
        this.score = score;
    }

    /** Rows that stand in for something else and must not be ranked or remembered. */
    public boolean isPlaceholder() {
        return onTap != null || "permission".equals(id)
                || (id != null && (id.startsWith("quick_tool_") || id.startsWith("glanceable_")));
    }
}
