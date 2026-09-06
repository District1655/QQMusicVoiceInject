package com.syu.voice.hook;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 运行日志：Logcat + 文件双写。
 *
 * 日志文件位置：/sdcard/fytMusicVoiceInject/logs/fytMusicVoiceInject.log
 * （车助理是 system uid，可直接写 /sdcard；可通过模块主界面查看）
 * 单文件超过 MAX_BYTES 自动轮转为 .1 / .2，保留 3 份。
 */
public final class LogManager {

    public static final String TAG = MainHook.TAG;

    private static final String LOG_DIR = "fytMusicVoiceInject/logs";
    private static final String LOG_NAME = "fytMusicVoiceInject.log";
    private static final long MAX_BYTES = 1024 * 1024; // 1MB
    private static final int KEEP_BACKUPS = 3;

    private static volatile File sLogFile;

    private LogManager() {
    }

    /** 在目标进程内初始化日志文件（幂等） */
    public static synchronized void init(Context context) {
        if (sLogFile != null) {
            return;
        }
        try {
            File base = Environment.getExternalStorageDirectory();
            File dir = new File(base, LOG_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                dir = context.getFilesDir();
            }
            sLogFile = new File(dir, LOG_NAME);
            write("==== 日志启动 " + stamp() + " ====");
            Log.i(TAG, "日志文件: " + sLogFile.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "日志初始化失败", t);
        }
    }

    public static void d(String tag, String msg) {
        Log.d(tag, msg);
        write("[D] " + tag + ": " + msg);
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        write("[I] " + tag + ": " + msg);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        write("[W] " + tag + ": " + msg);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        write("[E] " + tag + ": " + msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
        write("[E] " + tag + ": " + msg + "\n    " + Log.getStackTraceString(t));
    }

    private static synchronized void write(String line) {
        if (sLogFile == null) {
            return;
        }
        try {
            FileOutputStream fos = new FileOutputStream(sLogFile, true);
            try {
                fos.write((stamp() + " " + line + "\n").getBytes("UTF-8"));
            } finally {
                fos.close();
            }
            rotateIfNeeded();
        } catch (IOException ignored) {
        }
    }

    private static void rotateIfNeeded() {
        if (sLogFile == null || sLogFile.length() < MAX_BYTES) {
            return;
        }
        try {
            for (int i = KEEP_BACKUPS - 1; i >= 1; i--) {
                File src = new File(sLogFile.getAbsolutePath() + "." + i);
                File dst = new File(sLogFile.getAbsolutePath() + "." + (i + 1));
                if (src.exists()) {
                    if (dst.exists()) {
                        dst.delete();
                    }
                    src.renameTo(dst);
                }
            }
            File first = new File(sLogFile.getAbsolutePath() + ".1");
            if (first.exists()) {
                first.delete();
            }
            sLogFile.renameTo(first);
        } catch (Throwable ignored) {
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    /** 返回日志文件路径（可能为 null） */
    public static File getLogFile() {
        return sLogFile;
    }
}
