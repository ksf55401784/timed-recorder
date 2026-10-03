package com.example.recorder;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 离线语音识别封装（Vosk）：
 * - 首次使用若本地没有中文模型，则从 GitHub Release 下载并解压（需联网一次）。
 * - 之后完全离线，把 16kHz 单声道 WAV 转成文字。
 */
public class Transcriber {
    private static final String TAG = "Transcriber";
    // 中文小模型（约 40MB），放在本仓库的 Release 里供 App 下载
    private static final String MODEL_URL =
            "https://github.com/ksf55401784/timed-recorder/releases/download/models/vosk-model-small-cn-0.22.zip";
    // 备用源（官方直链）：主源失败自动回退
    private static final String MODEL_URL_FALLBACK =
            "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip";
    private static final String MODEL_DIR_NAME = "vosk-model-small-cn-0.22";

    public static File getModelDir(Context ctx) {
        return new File(ctx.getFilesDir(), MODEL_DIR_NAME);
    }

    public static boolean isModelReady(Context ctx) {
        return new File(getModelDir(ctx), "am/final.mdl").exists();
    }

    /** 确保模型就绪：缺失则联网下载并解压。返回是否就绪。 */
    public static boolean ensureModel(Context ctx) {
        if (isModelReady(ctx)) return true;
        String[] urls = { MODEL_URL, MODEL_URL_FALLBACK };
        for (String u : urls) {
            try {
                File zip = new File(ctx.getCacheDir(), "vosk-model.zip");
                download(u, zip);
                unzip(zip, ctx.getFilesDir());
                zip.delete();
                if (isModelReady(ctx)) return true;
            } catch (Exception e) {
                Log.e(TAG, "模型下载/解压失败 url=" + u, e);
            }
        }
        return false;
    }

    private static void download(String url, File out) throws Exception {
        InputStream in = new BufferedInputStream(new URL(url).openStream());
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close();
        in.close();
        Log.i(TAG, "模型下载完成: " + out.length() + "B");
    }

    private static void unzip(File zip, File destDir) throws Exception {
        destDir.mkdirs();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                File f = new File(destDir, e.getName());
                if (e.isDirectory()) {
                    f.mkdirs();
                } else {
                    f.getParentFile().mkdirs();
                    FileOutputStream fos = new FileOutputStream(f);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    fos.close();
                }
                zis.closeEntry();
            }
        }
    }

    /** 把 wav(16k 单声道 16-bit) 转写成文字。模型必须已就绪。 */
    public static String transcribe(Context ctx, File wav) throws Exception {
        Model model = new Model(getModelDir(ctx).getAbsolutePath());
        Recognizer rec = new Recognizer(model, 16000.0f);
        byte[] buf = new byte[4096];
        try (RandomAccessFile raf = new RandomAccessFile(wav, "r")) {
            raf.seek(44); // 跳过 WAV 头
            int r;
            while ((r = raf.read(buf)) > 0) {
                if (r == buf.length) {
                    rec.acceptWaveForm(buf, buf.length);
                } else {
                    byte[] part = new byte[r];
                    System.arraycopy(buf, 0, part, 0, r);
                    rec.acceptWaveForm(part, part.length);
                }
            }
        }
        String result = rec.getFinalResult();
        rec.close();
        model.close();
        try {
            return new JSONObject(result).optString("text", "").trim();
        } catch (Exception e) {
            return result.trim();
        }
    }
}
