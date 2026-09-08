package com.syu.voice.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 模块主界面：查看版本 / 检查更新 / 下载安装 / 查看运行日志。
 * 无资源文件依赖，全部代码构建 UI，避免引入额外资源编译。
 */
public class MainActivity extends Activity {

    private TextView mStatusView;
    private Button mCheckBtn;
    private Button mDownloadBtn;
    private TextView mLogView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    private void buildUi() {
        // 外层 ScrollView：横屏时可滚动整个页面，避免按钮占满屏幕后无法下滑
        ScrollView outerScroll = new ScrollView(this);
        outerScroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setBackgroundColor(0xFFF4F3EE);
        outerScroll.addView(root);

        TextView title = new TextView(this);
        title.setText("fytMusicVoiceInject · 方易通语音助手音乐适配");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF1A1B1C);
        root.addView(title);

        TextView version = new TextView(this);
        version.setText("当前版本 v" + BuildConfig.VERSION_NAME + " (code " + BuildConfig.VERSION_CODE + ")");
        version.setTextSize(13);
        version.setTextColor(0xFF6B7280);
        version.setPadding(0, 6, 0, 16);
        root.addView(version);

        mStatusView = new TextView(this);
        mStatusView.setText("状态：尚未检查更新\n日志目录：/sdcard/Android/data/com.syu.voice.hook/files/logs/");
        mStatusView.setTextSize(13);
        mStatusView.setTextColor(0xFF4B5563);
        mStatusView.setPadding(12, 12, 12, 12);
        mStatusView.setBackgroundColor(0xFFFFFFFF);
        root.addView(mStatusView);

        // 日志开关
        boolean logEnabled = getSharedPreferences("fyt_music_voice_prefs", MODE_PRIVATE)
                .getBoolean("log_enabled", true);
        Switch logSwitch = new Switch(this);
        logSwitch.setText("记录运行日志（关闭后仅 Logcat 输出，不写文件）");
        logSwitch.setTextSize(13);
        logSwitch.setTextColor(0xFF1A1B1C);
        logSwitch.setChecked(logEnabled);
        logSwitch.setPadding(0, 12, 0, 4);
        logSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                getSharedPreferences("fyt_music_voice_prefs", MODE_PRIVATE)
                        .edit().putBoolean("log_enabled", isChecked).apply();
                LogManager.setEnabled(isChecked);
                Toast.makeText(MainActivity.this,
                        "运行日志已" + (isChecked ? "开启" : "关闭") + "（重启车机后生效）",
                        Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(logSwitch);

        mCheckBtn = makeButton("检查更新（GitHub Releases）");
        mCheckBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUpdate();
            }
        });
        root.addView(mCheckBtn);

        mDownloadBtn = makeButton("下载并安装最新版");
        mDownloadBtn.setEnabled(false);
        mDownloadBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDownload();
            }
        });
        root.addView(mDownloadBtn);

        Button logBtn = makeButton("查看运行日志");
        logBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadLog();
            }
        });
        root.addView(logBtn);

        Button clearLogBtn = makeButton("清空日志");
        clearLogBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearLog();
            }
        });
        root.addView(clearLogBtn);

        Button exportBtn = makeButton("导出完整日志（zip 到 Download，含作用域进程）");
        exportBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportLogs();
            }
        });
        root.addView(exportBtn);

        Button rebootBtn = makeButton("重启车机（使模块生效）");
        rebootBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReboot();
            }
        });
        root.addView(rebootBtn);

        // 日志区域：固定高度，内部 ScrollView 独立滚动
        ScrollView logScroll = new ScrollView(this);
        mLogView = new TextView(this);
        mLogView.setTextSize(11);
        mLogView.setTypeface(Typeface.MONOSPACE);
        mLogView.setTextColor(0xFF1A1B1C);
        mLogView.setPadding(8, 8, 8, 8);
        mLogView.setBackgroundColor(0xFFFFFFFF);
        logScroll.addView(mLogView);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(260));
        logLp.topMargin = 12;
        logScroll.setLayoutParams(logLp);
        root.addView(logScroll);

        setContentView(outerScroll);
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 10;
        b.setLayoutParams(lp);
        return b;
    }

    /** 检查是否有读取 /sdcard 日志的权限（Android 11+ 用 isExternalStorageManager，10 及以下用运行时权限） */
    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 仅在用户点击"查看日志"且无权限时弹出，不再打开 App 就弹 */
    private void showStoragePermissionDialog() {
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+：跳"所有文件访问"设置页
            new AlertDialog.Builder(this)
                    .setTitle("需要存储权限")
                    .setMessage("查看日志需要访问 /sdcard/fytMusicVoiceInject/logs，请在授权页打开“允许访问所有文件”后返回。\n（注入功能本身不依赖该权限）")
                    .setPositiveButton("去授权", (d, w) -> {
                        try {
                            Intent intent = new Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } catch (Throwable t1) {
                            try {
                                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            } catch (Throwable t2) {
                                Toast.makeText(this, "车机系统不支持所有文件访问设置，请手动在系统设置中授权存储权限",
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            // Android 10 及以下：直接申请运行时权限，不跳设置页（避免车机无对应设置页导致闪退）
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 1001);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadLog();
            } else {
                Toast.makeText(this, "存储权限被拒绝，无法查看日志", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ------------------------------------------------------------------
    // 更新逻辑
    // ------------------------------------------------------------------

    private UpdateManager.ReleaseInfo mLatest;

    private void checkUpdate() {
        mStatusView.setText("状态：正在检查更新…");
        mDownloadBtn.setEnabled(false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final UpdateManager.ReleaseInfo info = UpdateManager.checkLatest();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mLatest = info;
                        if (info == null) {
                            mStatusView.setText("状态：检查失败（网络不通或仓库无 Release）\n"
                                    + "请确认车机可访问 api.github.com");
                            return;
                        }
                        String head = "状态：最新版本 v" + info.tagName
                                + "（code " + info.versionCode + "）\n";
                        String body = (info.body == null || info.body.isEmpty())
                                ? "" : "更新内容：\n" + info.body + "\n";
                        if (UpdateManager.isNewer(info, BuildConfig.VERSION_CODE)) {
                            mStatusView.setText(head + body + "→ 发现新版本，可下载安装");
                            mDownloadBtn.setEnabled(true);
                        } else {
                            mStatusView.setText(head + body + "→ 当前已是最新版本");
                            mDownloadBtn.setEnabled(false);
                        }
                    }
                });
            }
        }).start();
    }

    private void startDownload() {
        if (mLatest == null || TextUtils.isEmpty(mLatest.downloadUrl)) {
            Toast.makeText(this, "请先检查更新", Toast.LENGTH_SHORT).show();
            return;
        }
        mStatusView.setText("状态：正在下载 " + mLatest.tagName + " …");
        long id = UpdateManager.download(this, mLatest.downloadUrl);
        if (id >= 0) {
            UpdateManager.registerDownloadReceiver(this, id);
        } else {
            mStatusView.setText("状态：下载启动失败");
        }
    }

    // ------------------------------------------------------------------
    // 日志
    // ------------------------------------------------------------------

    /**
     * 全部作用域（宿主）包名 + 模块自身兜底。
     *
     * v1.6.0 修正：各进程的 LogManager 写的是【宿主自己】的外部私有目录
     * （/sdcard/Android/data/<宿主pkg>/files/logs/），不是模块目录——
     * 旧列表用 com.syu.voice.hook 当"车助理进程"是错的，且漏了 TXZ（com.txznet.txz）。
     * getExternalFilesDir 失败时日志还会 fallback 到宿主内部私有目录 /data/data/<宿主pkg>/files/logs/。
     */
    private static final String[] SCOPE_PKGS = {
            "com.syu.voice",                 // 车助理（语音助手）
            "com.txznet.txz",               // TXZ 语音主服务
            "com.tencent.qqmusicpad",        // QQ音乐 HD / Pad
            "com.tencent.qqmusiccar",        // QQ音乐 车机版
            "com.tencent.qqmusic",           // QQ音乐 手机版
            "com.netease.cloudmusic.iot",    // 网易云 车机版
            "com.netease.cloudmusic",        // 网易云 手机版
            "com.syu.voice.hook",            // 模块自身（旧版/兜底目录）
    };

    private static final String LOG_NAME = "fytMusicVoiceInject.log";
    /** 轮转备份后缀（LogManager 1MB 轮转，保留 .1/.2/.3） */
    private static final String[] LOG_SUFFIXES = {"", ".1", ".2", ".3"};

    private Boolean mRootCache;

    /** 是否有可用 root（结果缓存；首次探测在后台线程做，避免 Magisk 弹窗卡主线程） */
    private boolean hasRoot() {
        if (mRootCache == null) {
            try {
                mRootCache = new String(suRun("id"), "UTF-8").contains("uid=0");
            } catch (Throwable t) {
                mRootCache = false;
            }
        }
        return mRootCache;
    }

    /** 执行 su 命令并返回 stdout（先读完输出再 waitFor，防大输出撑爆管道死锁） */
    private static byte[] suRun(String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
        byte[] out = readAll(p.getInputStream());
        byte[] err = readAll(p.getErrorStream());
        int code = p.waitFor();
        if (code != 0) {
            throw new RuntimeException("su exit=" + code
                    + (err.length > 0 ? " err=" + new String(err, "UTF-8").trim() : ""));
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }

    /** 某包的候选日志目录：sdcard 外部私有目录 + /data/data 内部私有目录（fallback） */
    private static List<File> logFileCandidates(String pkg, String suffix) {
        List<File> files = new ArrayList<File>();
        String name = LOG_NAME + suffix;
        files.add(new File("/sdcard/Android/data/" + pkg + "/files/logs/" + name));
        files.add(new File("/data/data/" + pkg + "/files/logs/" + name));
        return files;
    }

    /**
     * 读取一个日志文件：先直读（自己能读的），失败且 root 可用时走 su cat。
     * Android 10+ 分区存储下，模块（普通 uid）读不了 QQ音乐HD 等其他 App 的
     * 外部私有目录——这正是旧版"看不到 QQ音乐HD 日志"的根因，root 直读绕开限制。
     *
     * @return 文件内容；文件不存在/读取失败返回 null
     */
    private byte[] readLogBestEffort(String pkg, String suffix) {
        // 直读（自己目录或 ROM 放开的场景）
        for (File f : logFileCandidates(pkg, suffix)) {
            if (f.exists() && f.isFile() && f.canRead()) {
                try {
                    byte[] data = readAll(new FileInputStream(f));
                    if (data.length > 0) {
                        return data;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        // root 兜底：cat 逐个候选路径，读到内容即返回
        if (mRootCache == null || mRootCache) {
            for (File f : logFileCandidates(pkg, suffix)) {
                try {
                    byte[] data = suRun("cat " + f.getAbsolutePath());
                    if (data.length > 0) {
                        return data;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private void loadLog() {
        mLogView.setText("正在读取日志…（含作用域进程，首次可能弹出 root 授权）");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder sb = new StringBuilder();
                boolean any = false;
                final List<String> empty = new ArrayList<String>();
                for (String pkg : SCOPE_PKGS) {
                    byte[] data = readLogBestEffort(pkg, "");
                    if (data == null) {
                        empty.add(pkg);
                        continue;
                    }
                    sb.append("===== ").append(pkg).append(" =====\n");
                    try {
                        sb.append(new String(data, "UTF-8")).append('\n');
                    } catch (Throwable t) {
                        sb.append("(解码失败: ").append(t.getMessage()).append(")\n");
                    }
                    any = true;
                }
                if (!any) {
                    sb.append("（暂无日志。请先重启车机让模块生效，或触发一次语音指令）");
                } else if (!empty.isEmpty()) {
                    sb.append("\n[提示] 无日志文件的进程: ").append(TextUtils.join(", ", empty))
                            .append("\n（未安装/未注入或尚未产生日志）");
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mLogView.setText(sb.toString());
                    }
                });
            }
        }).start();
    }

    private void clearLog() {
        int cleared = 0;
        boolean root = hasRoot();
        for (String pkg : SCOPE_PKGS) {
            for (String suffix : LOG_SUFFIXES) {
                for (File f : logFileCandidates(pkg, suffix)) {
                    if (!f.exists()) {
                        continue;
                    }
                    if (f.delete()) {
                        cleared++;
                    } else if (root) {
                        // 分区存储下删不了其他 App 目录的文件，root 删
                        try {
                            suRun("rm -f " + f.getAbsolutePath());
                            cleared++;
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
        Toast.makeText(this, "已清空 " + cleared + " 个日志文件", Toast.LENGTH_SHORT).show();
        mLogView.setText("");
    }

    // ------------------------------------------------------------------
    // 一键导出完整日志（v1.6.0）
    // ------------------------------------------------------------------

    /**
     * 导出模块 + 全部作用域进程的完整日志：
     *   <pkg>/fytMusicVoiceInject.log[.1~.3]  各进程文件日志（直读 + root 兜底）
     *   logcat/logcat_filtered.txt            logcat 关键行（fytMusic/Xposed/AndroidRuntime）
     *   logcat/logcat_recent.txt               logcat 最近 5000 行（全量，root 时）
     *   info.txt                               版本/环境/各进程文件清单
     * 有 root：zip 落 /sdcard/Download/（su cp + chmod）；
     * 无 root：zip 落模块自己外部目录（只含直读能拿到的部分）。
     */
    private void exportLogs() {
        Toast.makeText(this, "正在导出…（后台执行，完成后提示路径）", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    final File out = doExportLogs();
                    result = out != null
                            ? "已导出: " + out.getAbsolutePath()
                            : "导出完成，但未能生成文件";
                } catch (Throwable t) {
                    result = "导出失败: " + t.getMessage();
                }
                final String msg = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mStatusView.setText("状态：" + msg);
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private File doExportLogs() throws Exception {
        boolean root = hasRoot();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String zipName = "fytMusicVoiceInject_logs_" + stamp + ".zip";

        File cacheDir = new File(getCacheDir(), "export");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        File tmpZip = new File(cacheDir, zipName);

        List<String> info = new ArrayList<String>();
        info.add("模块版本: v" + BuildConfig.VERSION_NAME + " (code " + BuildConfig.VERSION_CODE + ")");
        info.add("导出时间: " + stamp);
        info.add("Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        info.add("Root: " + (root ? "是" : "否（仅直读可获取的日志）"));
        info.add("");
        info.add("各进程日志文件:");

        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmpZip));
        try {
            // 1) 各作用域进程的文件日志（主文件 + 轮转 .1/.2/.3）
            for (String pkg : SCOPE_PKGS) {
                StringBuilder pkgLine = new StringBuilder("  ").append(pkg).append(':');
                boolean hasAny = false;
                for (String suffix : LOG_SUFFIXES) {
                    byte[] data = readLogBestEffort(pkg, suffix);
                    if (data == null) {
                        continue;
                    }
                    putZipEntry(zos, pkg + "/" + LOG_NAME + suffix, data);
                    pkgLine.append(" ").append(LOG_NAME + suffix)
                            .append("(").append(data.length).append("B)");
                    hasAny = true;
                }
                if (!hasAny) {
                    pkgLine.append(" 未找到（未安装/未注入或未产生日志）");
                }
                info.add(pkgLine.toString());
            }

            // 2) logcat
            byte[] logcatAll = null;
            if (root) {
                try {
                    logcatAll = suRun("logcat -d -t 5000");
                } catch (Throwable ignored) {
                }
            }
            if (logcatAll == null) {
                // 无 root：尝试直读（部分车机 ROM 放开 logcat 权限），只取模块 tag
                try {
                    Process p = Runtime.getRuntime().exec(
                            new String[]{"logcat", "-d", "-t", "3000", "-s", "fytMusicVoice"});
                    logcatAll = readAll(p.getInputStream());
                    p.waitFor();
                } catch (Throwable ignored) {
                }
            }
            if (logcatAll != null && logcatAll.length > 0) {
                putZipEntry(zos, "logcat/logcat_recent.txt", logcatAll);
                putZipEntry(zos, "logcat/logcat_filtered.txt", filterLines(logcatAll));
            } else {
                info.add("");
                info.add("logcat 不可读（无 root 且 ROM 限制）");
            }

            // 3) info.txt
            putZipEntry(zos, "info.txt",
                    TextUtils.join("\n", info).getBytes("UTF-8"));
        } finally {
            zos.close();
        }

        // 4) 发布：有 root 放 /sdcard/Download/（用户最易取）；否则放模块自己外部目录
        if (tmpZip.length() == 0) {
            return null;
        }
        if (root) {
            try {
                String dst = "/sdcard/Download/" + zipName;
                suRun("cp " + tmpZip.getAbsolutePath() + " " + dst
                        + " && chmod 644 " + dst);
                File out = new File(dst);
                if (out.exists() && out.length() > 0) {
                    return out;
                }
            } catch (Throwable t) {
                LogManager.w("export", "root 发布到 Download 失败: " + t.getMessage());
            }
        }
        File fallback = new File(getExternalFilesDir(null), zipName);
        copyFile(tmpZip, fallback);
        return fallback;
    }

    private static void putZipEntry(ZipOutputStream zos, String name, byte[] data) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    /** 从 logcat 字节流中过滤模块相关行（fytMusic / Xposed / AndroidRuntime，忽略大小写） */
    private static byte[] filterLines(byte[] logcat) {
        try {
            String[] lines = new String(logcat, "UTF-8").split("\n");
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                String lower = line.toLowerCase(Locale.US);
                if (lower.contains("fytmusic") || lower.contains("xposed")
                        || lower.contains("androidruntime")) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString().getBytes("UTF-8");
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        out.close();
    }

    // ------------------------------------------------------------------
    // 重启车机
    // ------------------------------------------------------------------

    private void confirmReboot() {
        new AlertDialog.Builder(this)
                .setTitle("确认重启车机")
                .setMessage("重启后 LSPosed 模块会生效。\n\n需要 root 权限，首次使用请在 Magisk 中允许本应用获取 root。")
                .setPositiveButton("立即重启", (d, w) -> rebootDevice())
                .setNegativeButton("取消", null)
                .show();
    }

    private void rebootDevice() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "reboot"});
            p.waitFor();
            // 极少数设备 reboot 不生效，fallback 到 svc power reboot
            try {
                Runtime.getRuntime().exec(new String[]{"su", "-c", "svc power reboot"});
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            Toast.makeText(this, "重启失败：未获取 root 权限。\n请在 Magisk 中允许本应用获取 root。",
                    Toast.LENGTH_LONG).show();
        }
    }
}
