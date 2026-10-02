package com.example.recorder;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面：管理多组定时录音。
 * - 列表展示全部定时（含启用状态、时间、重复、时长、备注）；
 * - 点击列表项编辑；点「新增定时」添加；
 * - 编辑对话框内可删除已有定时。
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

        requestPerms();
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
                .setPositiveButton("保存", null)   // 在 onShow 里设监听以做校验
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
