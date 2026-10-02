package com.example.recorder;

/** 一组定时录音的配置。多组定时各自独立登记闹钟。 */
public class Schedule {
    public long id;            // 唯一 id，同时用作 AlarmManager 的 requestCode
    public String label;       // 名称 / 备注
    public int hour;           // 0-23
    public int minute;         // 0-59
    public int durationMin;    // 录音时长（分钟）
    public boolean enabled;    // 是否启用
    public int repeat;         // 0=每天 1=工作日 2=周末

    public static final int REPEAT_DAILY = 0;
    public static final int REPEAT_WEEKDAY = 1;
    public static final int REPEAT_WEEKEND = 2;

    public Schedule() {}

    public Schedule(long id, String label, int hour, int minute,
                    int durationMin, boolean enabled, int repeat) {
        this.id = id;
        this.label = label;
        this.hour = hour;
        this.minute = minute;
        this.durationMin = durationMin;
        this.enabled = enabled;
        this.repeat = repeat;
    }
}
