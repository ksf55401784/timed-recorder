package com.example.recorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 闹钟触发接收器：到点后按所属定时取出录音时长，启动「麦克风前台服务」开始录音。
 * 由 AlarmManager.setAlarmClock 在锁屏/Doze 下唤醒并调用，享有后台启动前台服务的临时豁免。
 */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (ScheduleManager.ACTION.equals(intent.getAction())) {
            long id = intent.getLongExtra(ScheduleManager.EXTRA_ID, -1);
            Schedule s = (id > 0) ? ScheduleManager.getById(context, id) : null;
            int dur = (s != null) ? s.durationMin : 5;
            if (dur < 1) dur = 1;
            if (dur > 120) dur = 120;

            Intent svc = new Intent(context, RecorderService.class);
            svc.setAction(RecorderService.ACTION_START);
            svc.putExtra(RecorderService.EXTRA_DURATION, dur);
            context.startForegroundService(svc);
        }
    }
}
