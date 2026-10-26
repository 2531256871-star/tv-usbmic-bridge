package com.minis.usbmictest;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

public class MainActivity extends Activity {

    private TextView logView, meterView;
    private AudioManager audioManager;
    private volatile boolean running = false;
    private Thread recordThread;
    private AudioRecord record;
    private AudioTrack track;
    private FileOutputStream wavOut;
    private long totalFrames = 0;

    private static final int SR = 48000;
    private static final int CH = AudioFormat.CHANNEL_IN_MONO;
    private static final int FMT = 2; // ENCODING_PCM_16_BIT

    private void log(final String s) {
        android.util.Log.e("USBMIC", s);
        runOnUiThread(() -> logView.append(s + "\n"));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 40, 40, 40);
        meterView = new TextView(this);
        meterView.setTextSize(56);
        root.addView(meterView);
        ScrollView sv = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(15);
        sv.addView(logView);
        root.addView(sv);
        setContentView(root);

        log("=== USBMicBridge 启动 ===");
        log("Android " + Build.VERSION.RELEASE);

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            log("等待 RECORD_AUDIO 授权... (若用adb装, 我会自动pm grant)");
            return;
        }
        startAll();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] g) {
        if (req == 1 && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) {
            log("授权成功");
            startAll();
        } else {
            log("授权被拒绝!");
        }
    }

    private AudioDeviceInfo findUsbInput() {
        AudioDeviceInfo[] devs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        log("---- 输入设备清单 ----");
        AudioDeviceInfo usb = null;
        for (AudioDeviceInfo d : devs) {
            String type = typeName(d.getType());
            String name = String.valueOf(d.getProductName());
            log(String.format(Locale.US, "id=%d type=%s(%d) name=%s",
                    d.getId(), type, d.getType(), name));
            if ((d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE
                    || d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET
                    || d.getType() == AudioDeviceInfo.TYPE_USB_ACCESSORY)
                    && d.isSource() && usb == null) {
                usb = d;
            }
        }
        return usb;
    }

    private static String typeName(int t) {
        switch (t) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "BUILTIN_MIC";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB_DEVICE";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB_HEADSET";
            case AudioDeviceInfo.TYPE_USB_ACCESSORY: return "USB_ACCESSORY";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "WIRED_HEADSET";
            case AudioDeviceInfo.TYPE_HDMI: return "HDMI";
            case AudioDeviceInfo.TYPE_LINE_ANALOG: return "LINE_ANALOG";
            case AudioDeviceInfo.TYPE_FM_TUNER: return "FM_TUNER";
            case AudioDeviceInfo.TYPE_TV_TUNER: return "TV_TUNER";
            default: return "TYPE_" + t;
        }
    }

    private void startAll() {
        AudioDeviceInfo usb = findUsbInput();

        int minBuf = AudioRecord.getMinBufferSize(SR, CH, FMT);
        int buf = Math.max(minBuf, 16384);
        int source = MediaRecorder.AudioSource.MIC;
        record = new AudioRecord(source, SR, CH, FMT, buf);

        if (Build.VERSION.SDK_INT >= 23) {
            if (usb != null) {
                record.setPreferredDevice(usb);
                log(">>> setPreferredDevice -> USB id=" + usb.getId());
            } else {
                log("!!! 未发现 USB 输入设备, 暂用默认输入");
            }
        }
        String state = (record.getState() == AudioRecord.STATE_INITIALIZED) ? "INITIALIZED" : "FAILED";
        log(String.format(Locale.US, "AudioRecord: src=%d rate=%d buf=%d state=%s", source, SR, buf, state));
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            log("AudioRecord 初始化失败!");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            AudioDeviceInfo eff = record.getRoutedDevice();
            if (eff != null) {
                log(">>> 实际路由: id=" + eff.getId() + " type=" + typeName(eff.getType())
                        + " name=" + eff.getProductName());
            }
        }

        int tbuf = Math.max(AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, FMT), 16384);
        track = new AudioTrack(AudioManager.STREAM_MUSIC, SR, AudioFormat.CHANNEL_OUT_MONO, FMT, tbuf, AudioTrack.MODE_STREAM);
        track.play();

        try {
            File f = new File(Environment.getExternalStorageDirectory(), "usbmic_test.wav");
            wavOut = new FileOutputStream(f);
            wavOut.write(buildWavHeader(SR));
            log("WAV -> " + f.getAbsolutePath());
        } catch (Exception e) {
            log("WAV 创建失败: " + e);
        }

        record.startRecording();
        running = true;
        log(">>> 开始录音, 对着麦克风唱歌! <<<");

        recordThread = new Thread(() -> {
            short[] buf16 = new short[2048];
            byte[] out8 = new byte[buf16.length * 2];
            long lastUi = 0;
            double peakRms = 0;
            while (running) {
                int n = record.read(buf16, 0, buf16.length);
                if (n <= 0) continue;
                double sum = 0;
                short peak = 0;
                for (int i = 0; i < n; i++) {
                    sum += (double) buf16[i] * buf16[i];
                    short a = (short) Math.abs(buf16[i]);
                    if (a > peak) peak = a;
                    out8[2 * i] = (byte) (buf16[i] & 0xff);
                    out8[2 * i + 1] = (byte) ((buf16[i] >> 8) & 0xff);
                }
                double rms = Math.sqrt(sum / n) / 32768.0;
                totalFrames += n;
                try {
                    if (wavOut != null) wavOut.write(out8, 0, n * 2);
                } catch (IOException ignored) {}
                if (track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    track.write(out8, 0, n * 2);
                }
                long now = System.currentTimeMillis();
                if (now - lastUi > 200) {
                    lastUi = now;
                    if (rms > peakRms) peakRms = rms;
                    final int bars = (int) Math.min(40, rms * 300);
                    final StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < bars; i++) sb.append('#');
                    final String meter = String.format(Locale.US, "%s\nRMS %.4f  PEAK %.4f  %d帧",
                            sb.toString(), rms, peakRms, totalFrames);
                    runOnUiThread(() -> meterView.setText(meter));
                }
            }
        }, "usbmic-reader");
        recordThread.start();
    }

    private byte[] buildWavHeader(int sampleRate) {
        int byteRate = sampleRate * 2;
        byte[] h = new byte[44];
        put(h, 0, "RIFF");
        put32(h, 4, 0); put(h, 8, "WAVE");
        put(h, 12, "fmt "); put32(h, 16, 16);
        put16(h, 20, 1); put16(h, 22, 1);
        put32(h, 24, sampleRate); put32(h, 28, byteRate);
        put16(h, 32, 2); put16(h, 34, 16);
        put(h, 36, "data"); put32(h, 40, 0);
        return h;
    }
    private void put(byte[] h, int o, String s) { for (int i = 0; i < s.length(); i++) h[o + i] = (byte) s.charAt(i); }
    private void put32(byte[] h, int o, int v) { h[o] = (byte) v; h[o+1] = (byte)(v>>8); h[o+2] = (byte)(v>>16); h[o+3] = (byte)(v>>24); }
    private void put16(byte[] h, int o, int v) { h[o] = (byte) v; h[o+1] = (byte)(v>>8); }

    @Override
    protected void onDestroy() {
        running = false;
        try {
            if (record != null) { record.stop(); record.release(); }
            if (track != null) { track.stop(); track.release(); }
            if (wavOut != null) {
                int dataLen = (int) totalFrames * 2;
                File f = new File(Environment.getExternalStorageDirectory(), "usbmic_test.wav");
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw");
                raf.seek(4); raf.writeInt(Integer.reverseBytes(36 + dataLen));
                raf.seek(40); raf.writeInt(Integer.reverseBytes(dataLen));
                raf.close();
            }
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
