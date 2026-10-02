package com.example.recorder;

import android.app.AlarmManager;
import android.app.AlarmManager.AlarmClockInfo;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 多组定时调度管理：
 * - 用 SharedPreferences 以 JSON 数组保存多组定时；
 * - 每组定时用独立 PendingIntent（requestCode = schedule.id）登记，可增删改互不干扰；
 * - 使用 AlarmManager.setAlarmClock —— 即使在锁屏 / Doze 休眠下也会唤醒设备并准点触发，
 *   同时在状态栏显示一个闹钟图标，用户点按可回到 App。
 */
public final class ScheduleManager {

    public static final String PREF = "rec_sched";
    public static final String PREF_LIST = "schedules";
    public static final String PREF_SEQ = "seq";
    public static final String ACTION = "com.example.recorder.ACTION_RECORD";
    public static final String EXTRA_ID = "schedule_id";

    private static final String K_ID = "id";
    private static final String K_LABEL = "label";
    private static final String K_HOUR = "hour";
    private static final String K_MIN = "min";
    private static final String K_DUR = "dur";
    private static final String K_EN = "enabled";
    private static final String K_REP = "repeat";

    /** 读取全部定时（按存储顺序） */
    public static List<Schedule> getAll(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        List<Schedule> list = new ArrayList<>();
        String raw = p.getString(PREF_LIST, null);
        if (raw == null) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Schedule s = new Schedule();
                s.id = o.getLong(K_ID);
                s.label = o.optString(K_LABEL, "");
                s.hour = o.getInt(K_HOUR);
                s.minute = o.getInt(K_MIN);
                s.durationMin = o.optInt(K_DUR, 5);
                s.enabled = o.optBoolean(K_EN, true);
                s.repeat = o.optInt(K_REP, Schedule.REPEAT_DAILY);
                list.add(s);
            }
        } catch (Exception e) {
            // 数据损坏则忽略，返回空列表
        }
        return list;
    }

    /** 覆盖保存全部定时 */
    public static void saveAll(Context c, List<Schedule> list) {
        JSONArray arr = new JSONArray();
        for (Schedule s : list) {
            try {
                JSONObject o = new JSONObject();
                o.put(K_ID, s.id);
                o.put(K_LABEL, s.label == null ? "" : s.label);
                o.put(K_HOUR, s.hour);
                o.put(K_MIN, s.minute);
                o.put(K_DUR, s.durationMin);
                o.put(K_EN, s.enabled);
                o.put(K_REP, s.repeat);
                arr.put(o);
            } catch (Exception ignore) {
            }
        }
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(PREF_LIST, arr.toString()).apply();
    }

    /** 生成下一个自增 id */
    public static long nextId(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long seq = p.getLong(PREF_SEQ, 0) + 1;
        p.edit().putLong(PREF_SEQ, seq).apply();
        return seq;
    }

    /** 按 id 查找定时 */
    public static Schedule getById(Context c, long id) {
        for (Schedule s : getAll(c)) {
            if (s.id == id) return s;
        }
        return null;
    }

    /** 触发用的 PendingIntent：requestCode = schedule.id 区分不同定时 */
    public static PendingIntent triggerIntent(Context c, long id) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction(ACTION);
        i.putExtra(EXTRA_ID, id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        int rc = (int) (id & 0x7fffffff);
        return PendingIntent.getBroadcast(c, rc, i, flags);
    }

    /** 状态栏点按回主界面的 PendingIntent（多组共用即可） */
    public static PendingIntent showIntent(Context c) {
        Intent show = new Intent(c, MainActivity.class);
        int sFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            sFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(c, 0, show, sFlags);
    }

    /**
     * 计算下一次触发时间（毫秒），考虑重复类型：
     * - 每天：今天若已过则顺延到明天；
     * - 工作日：下一个周一~周五的该时刻；
     * - 周末：下一个周六~周日的该时刻。
     */
    public static long nextTrigger(long now, int hour, int minute, int repeat) {
        Calendar cal = Calendar.getInstance();
        for (int day = 0; day <= 14; day++) {
            cal.setTimeInMillis(now);
            cal.add(Calendar.DAY_OF_MONTH, day);
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            int dow = cal.get(Calendar.DAY_OF_WEEK); // 1=Sun .. 7=Sat
            boolean ok;
            if (repeat == Schedule.REPEAT_WEEKDAY) {
                ok = (dow >= Calendar.MONDAY && dow <= Calendar.FRIDAY);
            } else if (repeat == Schedule.REPEAT_WEEKEND) {
                ok = (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY);
            } else {
                ok = true; // 每天
            }
            if (!ok) continue;
            if (cal.getTimeInMillis() <= now) continue; // 当天已过的时刻跳过
            return cal.getTimeInMillis();
        }
        // 兜底：明天此时
        cal.setTimeInMillis(now);
        cal.add(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /** 登记全部已启用的定时（先取消全部再登记，保证与存储一致） */
    public static void applyAll(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        List<Schedule> all = getAll(c);
        // 先取消所有（含已禁用的）登记的闹钟，避免残留
        for (Schedule s : all) {
            am.cancel(triggerIntent(c, s.id));
        }
        // 再登记所有已启用的
        for (Schedule s : all) {
            if (!s.enabled) continue;
            long t = nextTrigger(System.currentTimeMillis(), s.hour, s.minute, s.repeat);
            AlarmClockInfo info = new AlarmClockInfo(t, showIntent(c));
            am.setAlarmClock(info, triggerIntent(c, s.id));
        }
    }

    /** 开机 / 解锁后恢复定时：重新登记全部已启用的定时 */
    public static void reapplyIfEnabled(Context c) {
        applyAll(c);
    }
}
