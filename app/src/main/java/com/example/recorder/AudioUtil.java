package com.example.recorder;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * 音频工具：把录音得到的 m4a/aac 解码并重采样为 16kHz 单声道 16-bit PCM 的 WAV 文件。
 * Vosk 离线识别只认这种格式，所以转写前必须先转一道。
 */
public class AudioUtil {
    private static final String TAG = "AudioUtil";
    private static final int TARGET_RATE = 16000;

    public static File decodeToWav16kMono(File src, File outWav) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(src.getAbsolutePath());

        int track = -1;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                track = i;
                break;
            }
        }
        if (track < 0) throw new IOException("未找到音频轨道");

        MediaFormat inFormat = extractor.getTrackFormat(track);
        String mime = inFormat.getString(MediaFormat.KEY_MIME);
        extractor.selectTrack(track);

        int inRate = inFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int inCh = inFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

        MediaCodec decoder = MediaCodec.createDecoderByType(mime);
        decoder.configure(inFormat, null, null, 0);
        decoder.start();

        ArrayList<short[]> chunks = new ArrayList<>();
        int totalShorts = 0;

        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        final int TIMEOUT_US = 20000;

        while (!sawOutputEOS) {
            if (!sawInputEOS) {
                int inIdx = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (inIdx >= 0) {
                    ByteBuffer buf = decoder.getInputBuffer(inIdx);
                    int sampleSize = extractor.readSampleData(buf, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sawInputEOS = true;
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, sampleSize, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            int outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // 继续等
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = decoder.getOutputFormat();
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inCh = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            } else if (outIdx >= 0) {
                ByteBuffer ob = decoder.getOutputBuffer(outIdx);
                short[] chunk = new short[info.size / 2];
                ob.asShortBuffer().get(chunk);
                decoder.releaseOutputBuffer(outIdx, false);

                short[] mono;
                if (inCh >= 2) {
                    mono = new short[chunk.length / 2];
                    for (int i = 0; i < mono.length; i++) {
                        mono[i] = (short) ((chunk[2 * i] + chunk[2 * i + 1]) / 2);
                    }
                } else {
                    mono = chunk;
                }
                chunks.add(mono);
                totalShorts += mono.length;

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEOS = true;
                }
            }
        }

        decoder.stop();
        decoder.release();
        extractor.release();

        // 拼成单声道 float 数组
        float[] mono = new float[totalShorts];
        int p = 0;
        for (short[] c : chunks) {
            for (short s : c) mono[p++] = s;
        }

        // 重采样到 16kHz
        int outLen = (int) (mono.length * (double) TARGET_RATE / inRate);
        if (outLen < 1) outLen = 1;
        short[] out = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            double pos = i * (double) inRate / TARGET_RATE;
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, mono.length - 1);
            float frac = (float) (pos - i0);
            float v = mono[i0] * (1 - frac) + mono[i1] * frac;
            out[i] = (short) Math.max(-32768, Math.min(32767, v));
        }

        writeWav(outWav, out, TARGET_RATE);
        Log.i(TAG, "解码完成: " + src.length() + "B -> wav " + out.length + " samples @" + TARGET_RATE);
        return outWav;
    }

    private static void writeWav(File out, short[] samples, int sampleRate) throws IOException {
        int dataSize = samples.length * 2;
        FileOutputStream fos = new FileOutputStream(out);
        // RIFF header
        fos.write("RIFF".getBytes());
        writeInt(fos, 36 + dataSize);
        fos.write("WAVE".getBytes());
        fos.write("fmt ".getBytes());
        writeInt(fos, 16);                 // PCM chunk size
        writeShort(fos, (short) 1);        // PCM
        writeShort(fos, (short) 1);        // mono
        writeInt(fos, sampleRate);
        writeInt(fos, sampleRate * 2);     // byte rate
        writeShort(fos, (short) 2);        // block align
        writeShort(fos, (short) 16);       // bits per sample
        fos.write("data".getBytes());
        writeInt(fos, dataSize);
        // PCM data
        for (short s : samples) writeShort(fos, s);
        fos.close();
    }

    private static void writeInt(FileOutputStream fos, int v) throws IOException {
        fos.write(v & 0xff);
        fos.write((v >> 8) & 0xff);
        fos.write((v >> 16) & 0xff);
        fos.write((v >> 24) & 0xff);
    }

    private static void writeShort(FileOutputStream fos, short v) throws IOException {
        fos.write(v & 0xff);
        fos.write((v >> 8) & 0xff);
    }
}
