package com.android.launcher3.search.universal;

import android.app.AlarmManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Process;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;

import com.android.launcher3.R;

import java.util.Date;
import java.util.concurrent.TimeUnit;

public final class GlanceableProvider {

    private GlanceableProvider() {}

    public static UniversalSearchResult getGlanceableSummary(Context context) {
        if (CalendarProvider.hasPermission(context)) {
            UniversalSearchResult event = getUpcomingEvent(context);
            if (event != null) {
                return event;
            }
        }

        UniversalSearchResult alarm = getNextAlarm(context);
        if (alarm != null) {
            return alarm;
        }

        return getBatterySummary(context);
    }

    private static UniversalSearchResult getUpcomingEvent(Context context) {
        long now = System.currentTimeMillis();
        long window = TimeUnit.HOURS.toMillis(24);
        Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(uri, now);
        ContentUris.appendId(uri, now + window);
        String[] projection = {
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY
        };
        String selection = CalendarContract.Instances.VISIBLE + "=1 AND "
                + CalendarContract.Instances.SELF_ATTENDEE_STATUS + "!="
                + CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED + " AND "
                + CalendarContract.Instances.STATUS + "!=" + CalendarContract.Events.STATUS_CANCELED;
        try (Cursor cursor = context.getContentResolver().query(uri.build(), projection,
                selection, null, CalendarContract.Instances.BEGIN + " ASC")) {
            if (cursor != null && cursor.moveToNext()) {
                long end = cursor.getLong(3);
                if (end <= now) {
                    return null;
                }
                long id = cursor.getLong(0);
                String title = cursor.getString(1);
                long begin = cursor.getLong(2);
                boolean allDay = cursor.getInt(4) == 1;

                CharSequence timeText;
                if (allDay) {
                    timeText = DateUtils.getRelativeTimeSpanString(context, begin, true);
                } else {
                    CharSequence rel = DateUtils.getRelativeTimeSpanString(begin, now,
                            DateUtils.MINUTE_IN_MILLIS);
                    String startTime = DateFormat.getTimeFormat(context).format(new Date(begin));
                    String endTime = DateFormat.getTimeFormat(context).format(new Date(end));
                    timeText = rel + " · " + startTime + " – " + endTime;
                }

                Intent intent = new Intent(Intent.ACTION_VIEW,
                        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                UniversalSearchResult result = new UniversalSearchResult(
                        UniversalSearchResult.SOURCE_EVENT, "glanceable_event_" + id,
                        TextUtils.isEmpty(title)
                                ? context.getString(R.string.smartspace_event_untitled) : title,
                        timeText, intent, Process.myUserHandle(), 0);
                result.iconRes = R.drawable.ic_search_event;
                return result;
            }
        } catch (RuntimeException e) {
        }
        return null;
    }

    private static UniversalSearchResult getNextAlarm(Context context) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        AlarmManager.AlarmClockInfo alarm = manager == null ? null : manager.getNextAlarmClock();
        if (alarm == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        long triggerTime = alarm.getTriggerTime();
        if (triggerTime > now && triggerTime <= now + TimeUnit.HOURS.toMillis(24)) {
            String time = DateFormat.getTimeFormat(context).format(new Date(triggerTime));
            CharSequence rel = DateUtils.getRelativeTimeSpanString(triggerTime, now,
                    DateUtils.MINUTE_IN_MILLIS);
            Intent intent = new Intent(AlarmClock.ACTION_SHOW_ALARMS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            UniversalSearchResult result = new UniversalSearchResult(
                    UniversalSearchResult.SOURCE_QUICK_ACTION, "glanceable_alarm",
                    context.getString(R.string.smartspace_next_alarm) + " · " + time,
                    rel, intent, Process.myUserHandle(), 0);
            result.iconRes = R.drawable.ic_search_alarm;
            return result;
        }
        return null;
    }

    private static UniversalSearchResult getBatterySummary(Context context) {
        try {
            Intent battery = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) {
                return null;
            }
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN);
            int percent = (scale > 0 && level >= 0) ? Math.min(100, level * 100 / scale) : -1;
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;

            if (percent >= 0 && (charging || percent <= 20)) {
                String title = context.getString(charging
                        ? R.string.smartspace_charging : R.string.smartspace_low_battery, percent);
                String subtitle = context.getString(R.string.smartspace_battery_settings);
                Intent intent = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                UniversalSearchResult result = new UniversalSearchResult(
                        UniversalSearchResult.SOURCE_SETTING, "glanceable_battery",
                        title, subtitle, intent, Process.myUserHandle(), 0);
                result.iconRes = R.drawable.ic_smartspace_bluetooth;
                return result;
            }
        } catch (RuntimeException e) {
        }
        return null;
    }
}
