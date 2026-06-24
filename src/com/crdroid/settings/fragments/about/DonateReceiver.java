/*
 * SPDX-FileCopyrightText: 2025 crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crdroid.settings.fragments.about;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.android.settings.R;

public class DonateReceiver extends BroadcastReceiver {

    private static final String TAG = "DonateReceiver";

    public static final String DONATE_LAST_CHECKED = "pref_donate_checked_in";
    public static final String DONATE_DISABLED = "pref_donate_disabled";

    private static final String DONATE_CHANNEL_ID = "donation_channel";
    private static final int DONATE_NOTIFICATION_ID = 8989;
    private static final int REQ_ALARM = 7611;
    private static final int REQ_DISMISS_FOREVER = 7612;

    public static final long COOLDOWN_MIN = 30 * 24 * 60; // Monthly reminder
    public static final long INITIAL_DELAY_MIN = 30; // 30-minute after first boot
    public static final long REPEAT_DELAY_MIN = 60; // Repeat after 1 hour if not opened

    private static final String ACTION_DONATE_NUDGE = "com.crdroid.settings.action.DONATE_NUDGE";
    private static final String ACTION_DONATE_DISMISS_FOREVER = "com.crdroid.settings.action.DONATE_DISMISS_FOREVER";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        final String action = intent != null ? intent.getAction() : null;
        if (action == null) return;

        if (ACTION_DONATE_DISMISS_FOREVER.equals(action)) {
            disablePermanently(ctx);
            return;
        }

        UserManager um = ctx.getSystemService(UserManager.class);
        if (um == null) {
            Log.d(TAG, "User service not ready, skipping notification");
            return;
        }

        if (!um.isPrimaryUser()) {
            Log.d(TAG, "Not running as the primary user, skipping notification");
            return;
        }

        if (!um.isUserUnlocked() && !Intent.ACTION_USER_UNLOCKED.equals(action)) {
            Log.d(TAG, "User not unlocked, skipping notification");
            return;
        }

        if (isDisabled(ctx)) {
            Log.d(TAG, "Donate reminders disabled by user, skipping notification");
            return;
        }

        if (isCoolDownActive(ctx)) {
            Log.d(TAG, "Cooldown period active, skipping notification");
            return;
        }

        if (Intent.ACTION_USER_UNLOCKED.equals(action) ||
                Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            scheduleNext(ctx, INITIAL_DELAY_MIN);
        } else if (ACTION_DONATE_NUDGE.equals(action)) {
            showDonateNotification(ctx);
        }
    }

    private boolean isDisabled(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean(DONATE_DISABLED, false);
    }

    private boolean isCoolDownActive(Context ctx) {
        long now = System.currentTimeMillis();
        long last = PreferenceManager.getDefaultSharedPreferences(ctx)
                       .getLong(DONATE_LAST_CHECKED, 0L);
        long elapsed = (now - last) / (60_000L);
        if (last > 0 && elapsed < COOLDOWN_MIN) {
            // Reschedule after cooldown
            long remaining = Math.max(1, COOLDOWN_MIN - elapsed);
            scheduleNext(ctx, remaining);
            return true;
        }
        return false;
    }

    public static void scheduleNext(Context ctx, long delayMinutes) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, REQ_ALARM, new Intent(ACTION_DONATE_NUDGE).setPackage(ctx.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        long triggerAt = SystemClock.elapsedRealtime() + delayMinutes * 60_000L;
        am.cancel(pi);
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
    }

    private void showDonateNotification(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(DONATE_NOTIFICATION_ID);

        createChannelIfNeeded(ctx);

        PendingIntent contentPi = PendingIntent.getActivity(
                ctx, 0, new Intent(ctx, DonateActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        PendingIntent dismissForeverPi = PendingIntent.getBroadcast(
                ctx, REQ_DISMISS_FOREVER, new Intent(ACTION_DONATE_DISMISS_FOREVER).setPackage(ctx.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, DONATE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_donate_notification)
                .setContentTitle(ctx.getString(R.string.crdroid_donate_title))
                .setContentText(ctx.getString(R.string.crdroid_donate_notification_text))
                .setContentIntent(contentPi)
                .addAction(0, ctx.getString(R.string.crdroid_dont_show_again_action), dismissForeverPi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        nm.notify(DONATE_NOTIFICATION_ID, b.build());

        scheduleNext(ctx, REPEAT_DELAY_MIN);
    }

    private void createChannelIfNeeded(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
                DONATE_CHANNEL_ID,
                ctx.getString(R.string.crdroid_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        ch.setDescription(ctx.getString(R.string.crdroid_channel_desc));
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    public static void cancelNotification(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(DONATE_NOTIFICATION_ID);
    }

    public static void disablePermanently(Context ctx) {
        PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit()
                .putBoolean(DONATE_DISABLED, true)
                .apply();

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, REQ_ALARM, new Intent(ACTION_DONATE_NUDGE).setPackage(ctx.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        am.cancel(pi);

        cancelNotification(ctx);
    }
}
