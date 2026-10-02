package com.example.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * 闹钟触发接收器：到点后按所属定时取出录音时长，启动「麦克风前台服务」开始录音。
 * 由 AlarmManager.setAlarmClock 在锁屏/Doze 下唤醒并调用，享有后台启动前台服务的临时豁免。
 *
 * 诊断增强：触发时先发一个锁屏可见的通知，用于判断「闹钟是否真的被唤起」；
 * 若 startForegroundService 被系统（国产机后台管控）拒绝，也明确通知用户去加白名单。
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String ALARM_CH = "alarm_channel";
    private static final int ALARM_NOTIF_ID = 3;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ScheduleManager.ACTION.equals(intent.getAction())) {
            long id = intent.getLongExtra(ScheduleManager.EXTRA_ID, -1);
            Schedule s = (id > 0) ? ScheduleManager.getById(context, id) : null;
            int dur = (s != null) ? s.durationMin : 5;
            if (dur < 1) dur = 1;
            if (dur > 120) dur = 120;

            // 诊断：先发锁屏可见通知，确认闹钟确实被触发
            notifyTriggered(context);

            Intent svc = new Intent(context, RecorderService.class);
            svc.setAction(RecorderService.ACTION_START);
            svc.putExtra(RecorderService.EXTRA_DURATION, dur);
            try {
                context.startForegroundService(svc);
            } catch (Exception e) {
                // 多见于国产机后台启动限制：系统拒绝从后台启动前台服务
                notifyBlocked(context);
            }
        }
    }

    /** 闹钟已触发的诊断通知（锁屏可见、高优先级） */
    private void notifyTriggered(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    ALARM_CH, "定时触发诊断", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("定时到点是否触发的诊断通知");
            nm.createNotificationChannel(ch);
        }
        Intent i = new Intent(c, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(c, ALARM_CH)
                .setContentTitle("⏰ 定时录音已触发")
                .setContentText("正在启动录音…若随后无「录音完成」通知，说明被系统后台限制。")
                .setSmallIcon(R.drawable.ic_mic)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .build();
        nm.notify(ALARM_NOTIF_ID, n);
    }

    /** 系统拒绝启动录音服务的提示（引导加白名单） */
    private void notifyBlocked(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    ALARM_CH, "定时触发诊断", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
        Intent i = new Intent(c, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(c, ALARM_CH)
                .setContentTitle("❌ 系统拒绝启动录音")
                .setContentText("请在 设置→电池→应用启动管理→定时录音→关闭自动管理，并允许自启动/后台活动。")
                .setSmallIcon(R.drawable.ic_mic)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build();
        nm.notify(ALARM_NOTIF_ID + 1, n);
    }
}
