package com.example.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 麦克风前台服务：被闹钟唤起后在后台/锁屏下录制指定时长，写到应用私有目录。
 * 录制状态通过本地广播回报给主界面（含失败原因），便于用户排查。
 */
public class RecorderService extends android.app.Service {

    public static final String ACTION_START = "com.example.recorder.action.START";
    public static final String EXTRA_DURATION = "duration_min";
    public static final String EXTRA_SECONDS = "duration_sec";

    // 状态广播：主界面接收后显示实时进度与失败原因
    public static final String ACTION_STATUS = "com.example.recorder.ACTION_STATUS";
    public static final String EXTRA_STATE = "state";   // STARTED / ERROR / DONE
    public static final String EXTRA_MSG = "msg";

    private static final String CHANNEL = "rec_channel";
    private static final int NOTIF_ID = 1;
    private static final int NOTIF_DONE_ID = 2;

    private MediaRecorder recorder;
    private File outFile;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler();
    private Runnable stopTask;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        startForeground(NOTIF_ID, buildNotification("正在启动录音…"));

        int durMin = (intent != null) ? intent.getIntExtra(EXTRA_DURATION, 5) : 5;
        int durSec = (intent != null) ? intent.getIntExtra(EXTRA_SECONDS, 0) : 0;
        if (durMin < 0) durMin = 0;
        if (durSec < 0) durSec = 0;
        if (durSec > 59) durSec = 59;
        long durMs = (long) durMin * 60 * 1000L + (long) durSec * 1000L;
        if (durMs < 1000) durMs = 10000; // 最短 10 秒兜底

        acquireWakeLock();

        try {
            startRecording();
            sendStatus("STARTED", "");
        } catch (Exception e) {
            Log.e("Recorder", "start failed", e);
            String reason = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
            sendStatus("ERROR", reason);
            notifyDone("录音失败", reason);
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        final long finalDur = durMs;
        stopTask = () -> stopRecording();
        handler.postDelayed(stopTask, finalDur);
        return START_NOT_STICKY;
    }

    private void startRecording() throws Exception {
        File dir = new File(getExternalFilesDir(null), "recordings");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建录音目录");
        }
        String name = "rec_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".m4a";
        outFile = new File(dir, name);

        recorder = new MediaRecorder();
        // 使用默认采样率/码率，避免部分机型（如荣耀）因硬编码参数而 start() 失败
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setOutputFile(outFile.getAbsolutePath());
        recorder.prepare();
        recorder.start();
    }

    private void stopRecording() {
        handler.removeCallbacks(stopTask);
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
            }
        } catch (Exception e) {
            Log.e("Recorder", "stop failed", e);
        }
        recorder = null;
        releaseWakeLock();

        if (outFile != null && outFile.exists() && outFile.length() > 0) {
            String info = outFile.getName() + " · " + (outFile.length() / 1024) + " KB";
            sendStatus("DONE", info);
            notifyDone("录音完成", info);
        } else {
            String why = (outFile == null) ? "未生成文件" :
                    (!outFile.exists() ? "文件未生成" : "文件为空(0字节，可能是麦克风被系统拦截)");
            sendStatus("ERROR", why);
            notifyDone("录音异常", why);
        }
        stopForeground(true);
        stopSelf();
    }

    private void sendStatus(String state, String msg) {
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_STATE, state);
        i.putExtra(EXTRA_MSG, msg == null ? "" : msg);
        sendBroadcast(i);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "录音服务", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("定时录音前台服务");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("定时录音")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void notifyDone(String title, String body) {
        Notification n = new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(R.drawable.ic_mic)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(NOTIF_DONE_ID, n);
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Recorder::wakelock");
        wakeLock.acquire(60 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
}
