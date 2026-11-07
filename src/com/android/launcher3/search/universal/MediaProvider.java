package com.android.launcher3.search.universal;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Process;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Size;

import com.android.launcher3.LauncherPrefs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class MediaProvider implements SearchProvider {

    private static final Pattern SCREENSHOTS = Pattern.compile("^screen ?shots?$");
    private static final Pattern DOWNLOADS = Pattern.compile("^downloads?$");
    private static final int RECENT_SCORE = 90;

    private static final String[] PROJECTION = {
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
    };

    @Override
    public int getSource() {
        return UniversalSearchResult.SOURCE_FILE;
    }

    public static final String[] PERMISSIONS = {
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    };

    public static final String[] IMAGE_PERMISSIONS = {
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    };

    public static boolean hasImagePermission(Context context) {
        for (String permission : IMAGE_PERMISSIONS) {
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    private static Drawable loadThumbnail(Context context, Uri uri) {
        try {
            Bitmap bitmap = context.getContentResolver()
                    .loadThumbnail(uri, new Size(256, 256), null);
            return bitmap == null ? null : new BitmapDrawable(context.getResources(), bitmap);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static List<UniversalSearchResult> recentScreenshots(Context context, int max) {
        List<UniversalSearchResult> out = new ArrayList<>();
        if (!hasImagePermission(context)) {
            return out;
        }
        Uri images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        try (Cursor c = context.getContentResolver().query(images,
                new String[]{MediaStore.Images.Media._ID},
                MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?", new String[]{"%Screenshots%"},
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            while (c != null && c.moveToNext() && out.size() < max) {
                Uri uri = ContentUris.withAppendedId(images, c.getLong(0));
                Drawable thumb = loadThumbnail(context, uri);
                if (thumb == null) {
                    continue;
                }
                UniversalSearchResult result = new UniversalSearchResult(
                        UniversalSearchResult.SOURCE_SCREENSHOT, uri.toString(), "", null,
                        new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        Process.myUserHandle(), 0);
                result.icon = thumb;
                result.thumbnail = true;
                out.add(result);
            }
        } catch (RuntimeException e) {
            return out;
        }
        return out;
    }

    static List<UniversalSearchResult> recentDownloads(Context context, int max) {
        List<UniversalSearchResult> out = new ArrayList<>();
        if (!hasPermission(context)) {
            return out;
        }
        Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String selection = "(" + MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ? OR "
                + MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ?) AND "
                + MediaStore.Files.FileColumns.DISPLAY_NAME + " IS NOT NULL";
        String[] args = new String[]{"Download/%", "%/Download/%"};
        String sortOrder = MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC";
        try (Cursor c = context.getContentResolver().query(collection, PROJECTION,
                selection, args, sortOrder)) {
            if (c == null) {
                return out;
            }
            int idIndex = c.getColumnIndex(MediaStore.Files.FileColumns._ID);
            int nameIndex = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME);
            int mimeIndex = c.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE);
            int pathIndex = c.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH);
            while (c.moveToNext() && out.size() < max) {
                String name = nameIndex < 0 ? null : c.getString(nameIndex);
                if (TextUtils.isEmpty(name)) {
                    continue;
                }
                Uri uri = ContentUris.withAppendedId(collection, c.getLong(idIndex));
                String mime = mimeIndex < 0 ? null : c.getString(mimeIndex);
                if (mime == null) {
                    mime = "*/*";
                }
                Intent intent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                String path = pathIndex < 0 ? null : c.getString(pathIndex);
                UniversalSearchResult result = new UniversalSearchResult(
                        UniversalSearchResult.SOURCE_RECENT_DOWNLOADS, uri.toString(), name,
                        path != null ? path : context.getString(com.android.launcher3.R.string.search_section_recent_downloads),
                        intent, Process.myUserHandle(), 0);
                if (mime.startsWith("image/") || mime.startsWith("video/")
                        || mime.startsWith("audio/")) {
                    result.icon = loadThumbnail(context, uri);
                    result.thumbnail = result.icon != null;
                }
                if (result.icon == null) {
                    boolean isApk = name != null && name.toLowerCase().endsWith(".apk");
                    result.iconRes = isApk ? com.android.launcher3.R.drawable.ic_search_apk
                            : com.android.launcher3.R.drawable.ic_search_file;
                }
                out.add(result);
            }
        } catch (RuntimeException e) {
            return out;
        }
        return out;
    }

    public static boolean hasPermission(Context context) {
        for (String permission : PERMISSIONS) {
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isEnabled(Context context) {
        return LauncherPrefs.SEARCH_FILES.get(context);
    }

    @Override
    public List<UniversalSearchResult> query(Context context, String query, int max) {
        List<UniversalSearchResult> out = new ArrayList<>();
        if (query.length() < 2) {
            return out;
        }
        if (!hasPermission(context)) {
            return SearchProvider.permissionRequest(context, getSource(),
                    com.android.launcher3.R.string.search_permission_files);
        }
        Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String keyword = query.trim().toLowerCase();
        boolean recent = true;
        String selection;
        String[] args;
        if (SCREENSHOTS.matcher(keyword).matches()) {
            selection = MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ?";
            args = new String[]{"%Screenshots%"};
        } else if (DOWNLOADS.matcher(keyword).matches()) {
            selection = MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ?";
            args = new String[]{"Download%"};
        } else {
            recent = false;
            selection = MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ? AND "
                    + MediaStore.Files.FileColumns.MIME_TYPE + " IS NOT NULL";
            args = new String[]{"%" + query + "%"};
        }
        try (Cursor c = context.getContentResolver().query(collection, PROJECTION, selection,
                args, MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC")) {
            if (c == null) {
                return out;
            }
            int idIndex = c.getColumnIndex(MediaStore.Files.FileColumns._ID);
            int nameIndex = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME);
            int mimeIndex = c.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE);
            int pathIndex = c.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH);
            while (c.moveToNext() && out.size() < max) {
                String name = nameIndex < 0 ? null : c.getString(nameIndex);
                if (TextUtils.isEmpty(name)) {
                    continue;
                }
                Uri uri = ContentUris.withAppendedId(collection, c.getLong(idIndex));
                String mime = mimeIndex < 0 ? null : c.getString(mimeIndex);
                if (TextUtils.isEmpty(mime)) {
                    continue;
                }
                Intent intent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                UniversalSearchResult result = new UniversalSearchResult(
                        UniversalSearchResult.SOURCE_FILE, uri.toString(), name,
                        pathIndex < 0 ? null : c.getString(pathIndex),
                        intent, Process.myUserHandle(),
                        recent ? RECENT_SCORE
                                : ShortcutProvider.score(query.toLowerCase(), name));
                if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/")
                        || mime.startsWith("audio/"))) {
                    result.icon = loadThumbnail(context, uri);
                    result.thumbnail = result.icon != null;
                }
                out.add(result);
            }
        } catch (RuntimeException e) {
            return out;
        }
        return out;
    }
}
