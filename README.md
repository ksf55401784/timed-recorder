# 定时录音（Android 原生）

锁屏也能准点录音的 Android 应用。支持**多组定时**，每组可单独设置时间、录音时长、重复方式（每天 / 工作日 / 周末）。到点在系统闹钟唤醒下启动**麦克风前台服务**录制指定时长，保存到应用私有目录。

## 为什么能锁屏录音

| 机制 | 作用 |
|---|---|
| `AlarmManager.setAlarmClock` | 即使设备锁屏 / Doze 休眠，也会**唤醒并准点触发**（状态栏显示闹钟图标），且享有「后台启动前台服务」的临时豁免 |
| 前台服务 `foregroundServiceType="microphone"` | 在后台 / 锁屏下合法占用麦克风录音 |
| `RECEIVE_BOOT_COMPLETED` 接收器 | 设备重启后自动恢复每日定时 |
| `PARTIAL_WAKE_LOCK` | 录音期间保持 CPU 唤醒，避免被打断 |

## 功能

- **多组定时**：可添加任意多组录音计划，互不干扰
- 每组独立设置：触发时间（TimePicker）、录音时长（1–120 分钟）、名称/备注
- **重复方式**：每天 / 工作日（周一~周五）/ 周末（周六~周日）
- 每组可单独启用 / 停用；列表页点击即编辑，支持删除
- 录音文件：`内部存储/Android/data/com.example.recorder/files/recordings/rec_YYYYMMDD_HHMMSS.m4a`
- 开始 / 完成均弹出系统通知

## 构建与运行

> 需要 **Android Studio**（含 Android SDK，compileSdk 34），以及一台**真机**（模拟器通常没有真实麦克风，录音会失败）。

1. 用 Android Studio 打开本目录（即 `android-recorder/` 作为项目根）。
2. 等待 Gradle 同步完成（首次会下载 AGP 8.2.2 与依赖）。
3. 用 USB 连接手机，开启「开发者选项 → USB 调试」。
4. 点击 ▶ Run，选择该设备，安装并启动。
5. 首次进入会请求**麦克风**与**通知**权限，务必允许。
6. 点「＋ 新增定时」，设好时间、时长、重复方式，打开「启用」开关，点「保存」。
7. 锁屏等待到设定时间，即可看到状态栏闹钟图标并自动开始录音；到点后通知「录音完成」。

## 权限清单

`RECORD_AUDIO`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MICROPHONE`、`POST_NOTIFICATIONS`、`RECEIVE_BOOT_COMPLETED`、`WAKE_LOCK`。

## 平台说明

- **Android**：本方案即为此平台实现，锁屏 / 重启后均可靠。
- **iOS**：系统层面不允许任意定时录音，只能在 App 前台录音，无等价原生方案。

## 目录结构

```
android-recorder/
├── settings.gradle / build.gradle / gradle.properties
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/recorder/
        │   ├── MainActivity.java        # 多组定时列表 + 新增/编辑/删除
        │   ├── Schedule.java            # 单组定时数据模型
        │   ├── ScheduleManager.java     # 多组闹钟登记/取消/重启恢复（JSON 存储）
        │   ├── AlarmReceiver.java       # 闹钟触发 → 取该组时长 → 启动录音服务
        │   ├── BootReceiver.java        # 开机恢复定时
        │   └── RecorderService.java     # 麦克风前台录音服务
        └── res/layout/
            ├── activity_main.xml        # 列表 + 新增按钮
            └── dialog_schedule.xml      # 编辑/新增定时对话框
        └── res/                        # 布局 / 字符串 / 主题 / 图标
```
