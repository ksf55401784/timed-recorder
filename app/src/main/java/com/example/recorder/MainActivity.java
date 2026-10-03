package com.example.recorder;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 主界面：管理多组定时录音 + 立即录音测试 + 权限/电池优化引导。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMS = 100;
    private static final String[] REPEAT_LABELS = {
            "每天", "工作日（周一~周五）", "周末（周六~周日）"
    };

    private ListView listView;
    private ArrayAdapter<String> adapter;
    private List<String> display;
    private List<Schedule> schedules;
    private TextView statusText;
    private BroadcastReceiver statusReceiver;
    private BroadcastReceiver transcriptReceiver;
    private LinearLayout transcriptCard;
    private TextView tvTranscriptStatus;
    private TextView tvSummary;
    private TextView tvPoints;
    private TextView tvText;
    private String lastText = "", lastSummary = "", lastPoints = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        listView = findViewById(R.id.listView);
        Button addBtn = findViewById(R.id.addBtn);
        display = new ArrayList<>();
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, display);
        listView.setAdapter(adapter);

        schedules = ScheduleManager.getAll(this);
        refreshList();

        addBtn.setOnClickListener(v -> openEditor(null));
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (!schedules.isEmpty()) openEditor(schedules.get(position));
        });

        statusText = findViewById(R.id.statusText);
        Button testBtn = findViewById(R.id.testBtn);
        Button battBtn = findViewById(R.id.battBtn);
        Button permBtn = findViewById(R.id.permBtn);
        Button bgBtn = findViewById(R.id.bgBtn);
        transcriptCard = findViewById(R.id.transcriptCard);
        tvTranscriptStatus = findViewById(R.id.tvTranscriptStatus);
        tvSummary = findViewById(R.id.tvSummary);
        tvPoints = findViewById(R.id.tvPoints);
        tvText = findViewById(R.id.tvText);
        testBtn.setOnClickListener(v -> startTestRecord());
        battBtn.setOnClickListener(v -> openBatterySettings());
        permBtn.setOnClickListener(v -> openAppSettings());
        bgBtn.setOnClickListener(v -> openBgSettings());
        findViewById(R.id.btnCopy).setOnClickListener(v -> copyResult());

        // 接收录音服务回传的实时状态（开始/失败原因/完成）
        statusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String state = intent.getStringExtra(RecorderService.EXTRA_STATE);
                String msg = intent.getStringExtra(RecorderService.EXTRA_MSG);
                if ("STARTED".equals(state)) {
                    setStatus("✅ 录音已开始（正在录音…结束后会通知）");
                } else if ("DONE".equals(state)) {
                    setStatus("✅ 录音完成：" + (msg == null ? "" : msg)
                            + "\n文件在：内部存储/Android/data/com.example.recorder/files/recordings/");
                    if (transcriptCard != null) {
                        transcriptCard.setVisibility(View.VISIBLE);
                        tvTranscriptStatus.setText("⏳ 正在识别文字并整理要点…（首次需联网下载约 40MB 中文模型，请保持联网）");
                    }
                } else if ("ERROR".equals(state)) {
                    setStatus("❌ 录音失败：" + (msg == null ? "未知原因" : msg)
                            + "\n请检查：① 设置→应用→定时录音→权限→麦克风→选「允许」；"
                            + "② 华为/荣耀手机还需在 设置→隐私→权限管理 里给麦克风；"
                            + "③ 关闭「麦克风隐私保护/应用锁」。");
                }
            }
        };
        ContextCompat.registerReceiver(this, statusReceiver,
                new IntentFilter(RecorderService.ACTION_STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        // 接收录音完成后的转写结果（文字稿/概要/要点）
        transcriptReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (transcriptCard == null) return;
                transcriptCard.setVisibility(View.VISIBLE);
                String err = intent.getStringExtra(RecorderService.EXTRA_ERR);
                String text = intent.getStringExtra(RecorderService.EXTRA_TEXT);
                String summary = intent.getStringExtra(RecorderService.EXTRA_SUMMARY);
                String points = intent.getStringExtra(RecorderService.EXTRA_POINTS);
                if (err != null) {
                    tvTranscriptStatus.setText("⚠️ 转写失败：" + err + "（录音文件已保留）");
                    return;
                }
                if (text == null || text.trim().isEmpty()) {
                    tvTranscriptStatus.setText("⚠️ 未识别到文字（可能环境太安静，或首次模型未下载完；录音文件已保留）");
                    tvSummary.setText(""); tvPoints.setText(""); tvText.setText("");
                    return;
                }
                tvTranscriptStatus.setText("✅ 已转写并整理（文字稿/概要/要点已保存为 recordings/ 同名 .txt）");
                lastText = text; lastSummary = (summary == null ? "" : summary); lastPoints = (points == null ? "" : points);
                tvSummary.setText(lastSummary);
                tvPoints.setText(lastPoints);
                tvText.setText(text);
            }
        };
        ContextCompat.registerReceiver(this, transcriptReceiver,
                new IntentFilter(RecorderService.ACTION_TRANSCRIPT),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        requestPerms();
        updateStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (statusReceiver != null) {
            try { unregisterReceiver(statusReceiver); } catch (Exception ignore) {}
        }
        if (transcriptReceiver != null) {
            try { unregisterReceiver(transcriptReceiver); } catch (Exception ignore) {}
        }
    }

    /** 复制 概要+要点+文字稿 到剪贴板 */
    private void copyResult() {
        if (lastText.isEmpty()) {
            Toast.makeText(this, "暂无内容可复制", Toast.LENGTH_SHORT).show();
            return;
        }
        String s = "【概要】\n" + lastSummary + "\n\n【要点】\n" + lastPoints + "\n\n【文字稿】\n" + lastText;
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("录音转写", s));
        Toast.makeText(this, "已复制 文字稿+概要+要点", Toast.LENGTH_SHORT).show();
    }

    private void setStatus(String s) {
        if (statusText != null) statusText.setText(s);
    }

    private void refreshList() {
        schedules = ScheduleManager.getAll(this);
        display.clear();
        if (schedules.isEmpty()) {
            display.add("（暂无定时，点下方「＋ 新增定时」添加）");
        } else {
            for (Schedule s : schedules) {
                display.add(summary(s));
            }
        }
        adapter.notifyDataSetChanged();
        updateStatus();
    }

    /** 显示下次触发时间 / 电池优化状态，确认定时确实登记成功 */
    private void updateStatus() {
        if (statusText == null) return;
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        long earliest = Long.MAX_VALUE;
        int enabled = 0;
        for (Schedule s : schedules) {
            if (!s.enabled) continue;
            enabled++;
            long t = ScheduleManager.nextTrigger(now, s.hour, s.minute, s.repeat);
            if (t < earliest) earliest = t;
        }
        if (enabled > 0) {
            String when = new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(earliest));
            sb.append("已启用 ").append(enabled).append(" 个定时，最近一次将在 ").append(when).append(" 自动录音。\n");
        } else {
            sb.append("当前没有已启用的定时。\n");
        }
        boolean ignored = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            ignored = pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        if (ignored) {
            sb.append("✅ 电池优化已忽略（闹钟不会被系统杀掉）。");
        } else {
            sb.append("⚠️ 电池优化未关闭：点「忽略电池优化」并允许，否则闹钟可能不响！");
        }
        sb.append("\n🛡 华为/荣耀：设置→电池→应用启动管理→定时录音→关闭「自动管理」→开「自启动/后台活动」，EMUI 8 还要 设置→电池→受保护应用→开启本应用，否则锁屏不录（点下方橙色按钮直达）。");
        // 仅在尚未显示录音结果时刷新（避免覆盖 STARTED/DONE 提示）
        if (statusText.getText() == null || statusText.getText().toString().startsWith("已启用")
                || statusText.getText().toString().startsWith("当前没有")) {
            statusText.setText(sb.toString());
        }
    }

    /** 立即录音 10 秒做测试，验证麦克风与保存链路（时长短、反馈快） */
    private void startTestRecord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            // 已被永久拒绝（荣耀常见：点了「禁止」或默认拒绝）→ 直接引导去系统设置
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                setStatus("⚠️ 麦克风权限未授予（且系统未弹出请求）。\n请点「去开启权限」按钮，在设置里把麦克风设为「允许」，然后回来再点测试。");
                Toast.makeText(this, "请先去设置授予麦克风权限", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "正在请求麦克风权限…", Toast.LENGTH_SHORT).show();
                requestPerms();
            }
            return;
        }
        Intent svc = new Intent(this, RecorderService.class);
        svc.setAction(RecorderService.ACTION_START);
        svc.putExtra(RecorderService.EXTRA_DURATION, 0);
        svc.putExtra(RecorderService.EXTRA_SECONDS, 10); // 测试录 10 秒
        startForegroundService(svc);
        setStatus("⏳ 已发送录音请求，等待开始…（若数秒后无「✅ 录音已开始」提示，说明权限或麦克风被系统拦截）");
    }

    /** 跳到系统设置，关闭对本 App 的电池优化（国产机闹钟不响的头号原因） */
    private void openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            Toast.makeText(this,
                    "若此页无法把本应用设为「不受限制」，请再点橙色「后台保活白名单」按钮走华为/荣耀系统管家。",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "当前系统无需此设置", Toast.LENGTH_SHORT).show();
        }
    }

    /** 跳到本应用的系统设置页（用于手动开启麦克风等权限，荣耀机型必备） */
    private void openAppSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + getPackageName()));
        startActivity(i);
        Toast.makeText(this, "请在设置→权限→麦克风→选择「允许」", Toast.LENGTH_LONG).show();
    }

    /**
     * 锁屏定时能录的关键：把 App 加入厂商后台白名单。
     * 华为/荣耀有自己独立的系统管家（电池优化、自启动、受保护应用），标准设置页关不掉，
     * 必须直接跳到系统管家对应 Activity；其他品牌则跳系统应用设置页。
     */
    private void openBgSettings() {
        if (isHonor()) {
            openVendorSettings("com.hihonor.systemmanager", new String[]{
                    "com.hihonor.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
                    "com.hihonor.systemmanager/.optimize.process.ProtectActivity"
                },
                "荣耀必做：设置→电池→应用启动管理→定时录音→关闭「自动管理」→打开「允许自启动 / 允许后台活动 / 允许关联启动」");
        } else if (isHuawei()) {
            openVendorSettings("com.huawei.systemmanager", new String[]{
                    "com.huawei.systemmanager/.optimize.process.ProtectActivity",
                    "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity"
                },
                "华为必做：①设置→电池→应用启动管理→定时录音→关闭「自动管理」→开「自启动 / 后台活动 / 关联启动」；"
                        + "②EMUI 8 还要 设置→电池→受保护应用→把「定时录音」设为受保护。两步都做，否则锁屏不录。");
        } else {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            Toast.makeText(this,
                    "请在本应用设置里允许「后台运行 / 自启动 / 电池不被优化」，否则锁屏后可能不录。",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 逐个尝试厂商系统管家的指定 Activity 直达（命中即停），全部失败则兜底到应用详情页 */
    private void openVendorSettings(String pkg, String[] activities, String tip) {
        for (String a : activities) {
            try {
                Intent i = new Intent();
                if (a.contains("/")) {
                    String[] parts = a.split("/", 2);
                    i.setComponent(new android.content.ComponentName(parts[0], parts[1]));
                } else {
                    i.setPackage(a);
                    i.setAction(Intent.ACTION_MAIN);
                    i.addCategory(Intent.CATEGORY_LAUNCHER);
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                Toast.makeText(this, tip, Toast.LENGTH_LONG).show();
                return;
            } catch (Exception ignore) {
                // 该 Activity 在此 EMUI 版本不存在，尝试下一个候选
            }
        }
        // 所有直达入口都失败：兜底到本应用设置页并给出通用提示
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + getPackageName()));
        startActivity(i);
        Toast.makeText(this, tip, Toast.LENGTH_LONG).show();
    }

    private boolean isHuawei() {
        String man = (Build.MANUFACTURER != null) ? Build.MANUFACTURER.toLowerCase() : "";
        String brand = (Build.BRAND != null) ? Build.BRAND.toLowerCase() : "";
        return man.contains("huawei") || brand.contains("huawei")
                || brand.contains("hwin") || brand.contains("hw");
    }

    private boolean isHonor() {
        String man = (Build.MANUFACTURER != null) ? Build.MANUFACTURER.toLowerCase() : "";
        String brand = (Build.BRAND != null) ? Build.BRAND.toLowerCase() : "";
        return man.contains("honor") || brand.contains("honor");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            boolean audioOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            if (audioOk) {
                Toast.makeText(this, "✅ 麦克风权限已授予，可点「立即录音(测试)」", Toast.LENGTH_LONG).show();
            } else {
                setStatus("❌ 麦克风权限被拒绝。\n华为/荣耀手机请点「去开启权限」→ 设置→权限→麦克风→允许，并关闭「麦克风隐私保护/应用锁」。");
                Toast.makeText(this, "权限被拒绝，请去设置手动开启", Toast.LENGTH_LONG).show();
            }
            updateStatus();
        }
    }

    private String summary(Schedule s) {
        String rep = REPEAT_LABELS[s.repeat % REPEAT_LABELS.length];
        String note = (s.label != null && !s.label.isEmpty()) ? "  #" + s.label : "";
        return (s.enabled ? "● " : "○ ")
                + String.format("%02d:%02d", s.hour, s.minute)
                + "  " + rep
                + "  录" + s.durationMin + "分"
                + note;
    }

    private void openEditor(Schedule src) {
        final boolean isNew = (src == null);
        final Schedule cur = isNew
                ? new Schedule(ScheduleManager.nextId(this), "", 9, 0, 5, true, Schedule.REPEAT_DAILY)
                : new Schedule(src.id, src.label, src.hour, src.minute, src.durationMin, src.enabled, src.repeat);

        View v = LayoutInflater.from(this).inflate(R.layout.dialog_schedule, null);
        EditText label = v.findViewById(R.id.dlgLabel);
        TimePicker time = v.findViewById(R.id.dlgTime);
        EditText dur = v.findViewById(R.id.dlgDur);
        Spinner repeat = v.findViewById(R.id.dlgRepeat);
        Switch en = v.findViewById(R.id.dlgEnable);

        label.setText(cur.label);
        time.setHour(cur.hour);
        time.setMinute(cur.minute);
        dur.setText(String.valueOf(cur.durationMin));
        en.setChecked(cur.enabled);

        ArrayAdapter<String> repAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, REPEAT_LABELS);
        repAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        repeat.setAdapter(repAdapter);
        repeat.setSelection(cur.repeat);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(isNew ? "新增定时" : "编辑定时")
                .setView(v)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null);
        if (!isNew) {
            builder.setNeutralButton("删除", (dialog, which) -> {
                for (int i = 0; i < schedules.size(); i++) {
                    if (schedules.get(i).id == cur.id) {
                        schedules.remove(i);
                        break;
                    }
                }
                ScheduleManager.saveAll(this, schedules);
                ScheduleManager.applyAll(this);
                refreshList();
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
        }
        AlertDialog dlg = builder.create();

        dlg.setOnShowListener(dialog -> {
            Button ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            ok.setOnClickListener(vv -> {
                int h = time.getHour();
                int m = time.getMinute();
                int d;
                try {
                    d = Integer.parseInt(dur.getText().toString().trim());
                } catch (Exception e) {
                    d = 5;
                }
                if (d < 1) d = 1;
                if (d > 120) d = 120;

                cur.label = label.getText().toString().trim();
                cur.hour = h;
                cur.minute = m;
                cur.durationMin = d;
                cur.enabled = en.isChecked();
                cur.repeat = repeat.getSelectedItemPosition();

                if (isNew) {
                    schedules.add(cur);
                } else {
                    for (int i = 0; i < schedules.size(); i++) {
                        if (schedules.get(i).id == cur.id) {
                            schedules.set(i, cur);
                            break;
                        }
                    }
                }
                ScheduleManager.saveAll(this, schedules);
                ScheduleManager.applyAll(this);
                refreshList();
                Toast.makeText(this, "已保存并登记定时", Toast.LENGTH_SHORT).show();
                dlg.dismiss();
            });
        });
        dlg.show();
    }

    private void requestPerms() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms = new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS};
        } else {
            perms = new String[]{Manifest.permission.RECORD_AUDIO};
        }
        boolean need = false;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                need = true;
                break;
            }
        }
        if (need) {
            ActivityCompat.requestPermissions(this, perms, REQ_PERMS);
        }
    }
}
