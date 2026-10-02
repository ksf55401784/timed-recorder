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
 */
public class RecorderService extends android.app.Service {

    public static final String ACTION_START = "com.example.recorder.action.START";
    public static final String EXTRA_DURATION = "duration_min";
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
        int durMin = (intent != null) ? intent.getIntExtra(EXTRA_DURATION, 5) : 5;
        if (durMin < 1) durMin = 1;
        if (durMin > 120) durMin = 120;
        startForeground(NOTIF_ID, buildNotification("正在录音…"));
        acquireWakeLock();

        try {
            startRecording();
        } catch (Exception e) {
            Log.e("Recorder", "start failed", e);
            notifyDone("录音失败", e.getMessage());
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        final long durMs = (long) durMin * 60 * 1000;
        stopTask = this::stopRecording;
        handler.postDelayed(stopTask, durMs);
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
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioSamplingRate(44100);
        recorder.setAudioEncodingBitRate(192000);
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

        if (outFile != null && outFile.exists()) {
            notifyDone("录音完成", outFile.getName() + " · " + (outFile.length() / 1024) + " KB");
        }
        stopForeground(true);
        stopSelf();
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
