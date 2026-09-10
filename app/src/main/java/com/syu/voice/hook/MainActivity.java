package com.syu.voice.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
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

import org.json.JSONArray;
import org.json.JSONException;

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

    public static final String TAG = MainHook.TAG;

    private TextView mStatusView;
    private Button mCheckBtn;
    private Button mDownloadBtn;
    private TextView mLogView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 模块 App 进程（com.syu.voice.hook）不是 LSPosed 作用域宿主，
        // MainHook 不会在本进程跑，需自己初始化文件日志，便于记录按钮操作。
        try {
            LogManager.init(this);
        } catch (Throwable ignored) {
        }
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

        Button forceStopBtn = makeButton("一键勾选作用域并重启应用");
        forceStopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmAutoScopeAndRestart();
            }
        });
        root.addView(forceStopBtn);

        Button checkScopeBtn = makeButton("检测 LSPosed 作用域勾选状态");
        checkScopeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkLsposedScope();
            }
        });
        root.addView(checkScopeBtn);

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

    /** 某包的候选日志路径：sdcard 外部私有目录 + /data/media（root 绕过 FUSE）+ /data/data 内部私有目录（fallback） */
    private static List<File> logFileCandidates(String pkg, String suffix) {
        List<File> files = new ArrayList<File>();
        String name = LOG_NAME + suffix;
        files.add(new File("/sdcard/Android/data/" + pkg + "/files/logs/" + name));
        // root 走 /data/media/0 绕过 FUSE（部分 ROM 下 root 通过 /sdcard FUSE 访问 Android/data 仍被拒）
        files.add(new File("/data/media/0/Android/data/" + pkg + "/files/logs/" + name));
        files.add(new File("/data/data/" + pkg + "/files/logs/" + name));
        // LogManager 旧版/异常兜底可能直接落在内部 files 根目录（无 logs 子目录）
        files.add(new File("/data/data/" + pkg + "/files/" + name));
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

    /**
     * v1.8.1：从某进程日志内容解析"模块加载"记录里的版本号。
     * v1.8.3：取最后一条匹配（最新加载的版本），而非第一条——日志文件跨天累积，
     * 第一条可能是几天前的旧版本。
     * 车助理: "模块加载，进程: ...，版本: 1.8.2"；QQ/TXZ: "模块加载（…进程）v1.8.2 ..."。
     * @return 版本号字符串（如 1.8.2）；无加载记录返回 null
     */
    private static String parseLoadedVersion(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            String text = new String(data, "UTF-8");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "模块加载[^\\n]*?(?:版本[: ]+|v)([0-9]+\\.[0-9]+\\.[0-9]+)")
                    .matcher(text);
            String last = null;
            while (m.find()) {
                last = m.group(1);
            }
            return last;
        } catch (Throwable ignored) {
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
                    String ver = parseLoadedVersion(data);
                    sb.append("===== ").append(pkg)
                            .append(ver != null ? "（模块 v" + ver + " 已加载）" : "（未检测到模块加载记录）")
                            .append(" =====\n");
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
                            .append("\n（未安装/未注入或尚未产生日志）")
                            .append("\n★ 若 QQ音乐HD / TXZ 在此列：打开 LSPosed -> 模块 -> fytMusicVoiceInject，")
                            .append("确认勾选了该应用，并强制停止它（或重启车机）后再测。");
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
    // 一键导出完整日志（v1.6.0；v1.6.1 结果三态化——失败/残缺明确可识别）
    // ------------------------------------------------------------------

    /** 导出结果：成功 / 不完整（缺 root 或作用域日志）/ 失败 三态 */
    private static final class ExportResult {
        File zipFile;            // 最终发布的 zip；null=彻底失败
        boolean rootOk;          // 是否拿到 root
        String rootError;        // root 失败原因（可空）
        final List<String> foundPkgs = new ArrayList<String>(); // 读到日志的包名
        boolean logcatOk;        // logcat 是否入包
        String fatalError;       // 致命错误（zip 未生成/一个进程日志都没有）
    }

    /**
     * 导出模块 + 全部作用域进程的完整日志：
     *   <pkg>/fytMusicVoiceInject.log[.1~.3]  各进程文件日志（直读 + root 兜底）
     *   logcat/logcat_filtered.txt            logcat 关键行（fytMusic/Xposed/AndroidRuntime）
     *   logcat/logcat_recent.txt              logcat 最近 30 万行（root 时，车机日志量大，覆盖足够时间窗）
     *   info.txt                              版本/环境/各进程文件清单
     * 有 root：zip 落 /sdcard/Download/（su cp + chmod）；
     * 无 root：zip 落模块自己外部目录，且结果对话框明确警告"导出不完整"。
     */
    private void exportLogs() {
        // 每次导出重新探测 root（用户可能上次拒绝、刚去 Magisk 完成授权）
        mRootCache = null;
        Toast.makeText(this, "正在导出…首次使用会弹出 Magisk 授权，请点「允许」",
                Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ExportResult r;
                try {
                    r = doExportLogs();
                } catch (Throwable t) {
                    ExportResult e = new ExportResult();
                    e.fatalError = t.getClass().getSimpleName() + ": "
                            + (t.getMessage() == null ? t.toString() : t.getMessage());
                    showResultOnUi(e);
                    return;
                }
                showResultOnUi(r);
            }
        }).start();
    }

    private void showResultOnUi(final ExportResult r) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showExportResult(r);
            }
        });
    }

    private ExportResult doExportLogs() {
        ExportResult r = new ExportResult();

        // 1) root 探测（触发 Magisk 授权弹窗；后台线程阻塞等待用户响应，不卡 UI）
        try {
            byte[] id = suRun("id");
            r.rootOk = new String(id, "UTF-8").contains("uid=0");
            if (!r.rootOk) {
                r.rootError = "su id 未返回 uid=0";
            }
        } catch (Throwable t) {
            r.rootOk = false;
            r.rootError = t.getMessage() == null ? t.toString() : t.getMessage();
        }
        mRootCache = r.rootOk; // readLogBestEffort 据此决定是否走 root 兜底

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
        info.add("Root: " + (r.rootOk ? "是" : "否（" + r.rootError + "）"));
        info.add("");
        info.add("各进程日志文件:");

        try {
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmpZip));
            try {
                // 2) 各作用域进程的文件日志（主文件 + 轮转 .1/.2/.3）
                for (String pkg : SCOPE_PKGS) {
                    StringBuilder pkgLine = new StringBuilder("  ").append(pkg).append(':');
                    boolean hasAny = false;
                    String loadedVer = null;
                    for (String suffix : LOG_SUFFIXES) {
                        byte[] data = readLogBestEffort(pkg, suffix);
                        if (data == null) {
                            continue;
                        }
                        putZipEntry(zos, pkg + "/" + LOG_NAME + suffix, data);
                        pkgLine.append(" ").append(LOG_NAME + suffix)
                                .append("(").append(data.length).append("B)");
                        hasAny = true;
                        if ("".equals(suffix)) {
                            loadedVer = parseLoadedVersion(data);
                        }
                    }
                    if (hasAny) {
                        r.foundPkgs.add(pkg);
                        pkgLine.append(loadedVer != null
                                ? " | 模块 v" + loadedVer + " 已加载"
                                : " | 有日志但无模块加载记录（旧版本？）");
                    } else {
                        pkgLine.append(" 未找到（未安装/未注入/未产生日志或无 root 读不到）");
                    }
                    info.add(pkgLine.toString());
                }

                // 3) logcat
                byte[] logcatAll = null;
                if (r.rootOk) {
                    try {
                        logcatAll = suRun("logcat -d -t 300000");
                    } catch (Throwable t) {
                        info.add("logcat root 读取失败: " + t.getMessage());
                    }
                }
                if (logcatAll == null) {
                    // 无 root：尝试直读（部分车机 ROM 放开 logcat 权限），只取模块 tag
                    try {
                        Process p = Runtime.getRuntime().exec(
                                new String[]{"logcat", "-d", "-t", "3000", "-s", "fytMusicVoice"});
                        logcatAll = readAll(p.getInputStream());
                        p.waitFor();
                    } catch (Throwable t) {
                        info.add("logcat 直读失败: " + t.getMessage());
                    }
                }
                r.logcatOk = logcatAll != null && logcatAll.length > 0;
                if (r.logcatOk) {
                    putZipEntry(zos, "logcat/logcat_recent.txt", logcatAll);
                    putZipEntry(zos, "logcat/logcat_filtered.txt", filterLines(logcatAll));
                } else {
                    info.add("logcat 不可读（无 root 且 ROM 限制）");
                }

                // 4) info.txt
                putZipEntry(zos, "info.txt",
                        TextUtils.join("\n", info).getBytes("UTF-8"));
            } finally {
                zos.close();
            }
        } catch (Throwable t) {
            r.fatalError = "写 zip 失败: " + t.getMessage();
            tmpZip.delete();
            return r;
        }

        // 5) 一个进程日志都没拿到 = 失败（zip 里只有 info.txt，无诊断价值）
        if (r.foundPkgs.isEmpty()) {
            tmpZip.delete();
            r.fatalError = "没有收集到任何进程的日志。"
                    + (r.rootOk ? "" : "主因：未获取 root 权限，读不到其他 App 的日志目录。");
            return r;
        }

        // 6) 发布 zip：有 root 由 su cp 到 /sdcard/Download/；否则落模块自己外部目录
        if (r.rootOk) {
            try {
                String dst = "/sdcard/Download/" + zipName;
                suRun("cp " + tmpZip.getAbsolutePath() + " " + dst + " && chmod 644 " + dst);
                File out = new File(dst);
                if (out.exists() && out.length() > 0) {
                    r.zipFile = out;
                }
            } catch (Throwable t) {
                r.rootError = "发布到 Download 失败: " + t.getMessage();
            }
        }
        if (r.zipFile == null) {
            try {
                File fallback = new File(getExternalFilesDir(null), zipName);
                copyFile(tmpZip, fallback);
                r.zipFile = fallback;
            } catch (Throwable t) {
                r.fatalError = "zip 发布失败: " + t.getMessage();
            }
        }
        return r;
    }

    /**
     * 结果对话框（v1.6.1）：必须点「确定」关闭，不会像 Toast 一闪而过。
     * 三态：✅ 成功 / ⚠️ 导出不完整（缺 root 或作用域日志）/ ❌ 失败，均给出可操作建议。
     */
    private void showExportResult(ExportResult r) {
        boolean failed = r.zipFile == null || r.fatalError != null;
        boolean hasScopeLog = false;
        for (String pkg : r.foundPkgs) {
            if (!"com.syu.voice.hook".equals(pkg)) {
                hasScopeLog = true; // 7 个作用域宿主包任一拿到日志
                break;
            }
        }
        boolean partial = !failed && (!r.rootOk || !hasScopeLog);

        String title;
        StringBuilder msg = new StringBuilder();
        if (failed) {
            title = "❌ 导出失败";
            msg.append(r.fatalError == null ? "未知错误" : r.fatalError);
            msg.append("\n\n请重试；若持续失败：\n")
               .append("1. 打开 Magisk →「超级用户」，允许「fytMusicVoiceInject」获取 root；\n")
               .append("2. 确认车机已 root；\n")
               .append("3. 截图本对话框发给开发者。");
        } else {
            msg.append("日志包路径：\n").append(r.zipFile.getAbsolutePath()).append("\n\n");
            msg.append("收集到进程日志（").append(r.foundPkgs.size()).append("）：\n")
               .append(TextUtils.join("、", r.foundPkgs)).append("\n\n");
            msg.append("logcat：").append(r.logcatOk ? "已包含" : "未获取").append('\n');
            msg.append("root 权限：").append(r.rootOk ? "已授权" : "未获取").append('\n');

            if (partial) {
                title = "⚠️ 导出不完整";
                msg.append("\n⚠ 未获取 root 权限，读不到 QQ音乐HD/车助理等其他 App 的日志目录")
                   .append("（Android 10+ 分区存储限制），本日志包缺少排查所需的作用域进程日志。\n\n")
                   .append("解决办法：\n")
                   .append("1. 打开 Magisk →「超级用户」列表，允许「fytMusicVoiceInject」获取 root；\n")
                   .append("2. 重新点「导出完整日志」（授权弹窗会再次出现，点「允许」）。");
            } else {
                title = "✅ 导出成功";
                msg.append("\n请把该 zip 取出（/sdcard/Download/ 或 MT 管理器）发送给开发者排查。");
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg.toString())
                .setPositiveButton("确定", null)
                .show();
        mStatusView.setText("状态：" + title + " → "
                + (r.zipFile != null ? r.zipFile.getAbsolutePath()
                                     : (r.fatalError == null ? "" : r.fatalError)));
    }

    private static void putZipEntry(ZipOutputStream zos, String name, byte[] data) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    /** 从 logcat 字节流中过滤模块相关行（fytMusic / Xposed / LSPosed / AndroidRuntime，忽略大小写） */
    private static byte[] filterLines(byte[] logcat) {
        try {
            String[] lines = new String(logcat, "UTF-8").split("\n");
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                String lower = line.toLowerCase(Locale.US);
                if (lower.contains("fytmusic") || lower.contains("xposed")
                        || lower.contains("lsposed") || lower.contains("androidruntime")) {
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

    // ------------------------------------------------------------------
    // 强制停止作用域应用（让所有宿主进程重新加载最新模块代码）
    // ------------------------------------------------------------------

    /** 需要停止的作用域宿主包（排除模块自身 com.syu.voice.hook） */
    private static final String[] FORCE_STOP_PKGS = {
            "com.syu.voice",                 // 车助理（语音助手，系统应用会自动重启）
            "com.txznet.txz",               // TXZ 语音主服务
            "com.tencent.qqmusicpad",        // QQ音乐 HD / Pad
            "com.tencent.qqmusiccar",        // QQ音乐 车机版
            "com.tencent.qqmusic",           // QQ音乐 手机版
            "com.netease.cloudmusic.iot",    // 网易云 车机版
            "com.netease.cloudmusic",        // 网易云 手机版
    };

    private void confirmAutoScopeAndRestart() {
        new AlertDialog.Builder(this)
                .setTitle("一键勾选作用域并重启应用")
                .setMessage("将自动把本模块的作用域勾选为：车助理、TXZ语音、QQ音乐HD/车机版/手机版、网易云车机版/手机版（仅勾选已安装的），并强制停止这些应用使其重新加载最新模块代码。\n\n"
                        + "语音和音乐播放会短暂中断；车助理/TXZ等系统服务会被系统自动拉起。\n\n"
                        + "需要 Root 权限。是否继续？")
                .setPositiveButton("确定", (d, w) -> doAutoScopeAndRestart())
                .setNegativeButton("取消", null)
                .show();
    }

    private void doAutoScopeAndRestart() {
        mLogView.setText("正在配置 LSPosed 作用域并重启应用…\n");
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append("===== 一键勾选作用域并重启 ")
                        .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                                .format(new Date()))
                        .append(" =====\n");
                if (!hasRoot()) {
                    sb.append("❌ 无 Root 权限\n");
                    final String r = sb.toString();
                    runOnUiThread(() -> mLogView.setText(r));
                    return;
                }

                // 1. 配置 LSPosed 作用域
                boolean scopeOk = ensureLsposedScope(sb);

                // 2. 强制停止所有目标应用（无论 scope 配置是否成功都执行，
                //    让已勾选的应用重新加载模块；未勾选的重启后也不注入）
                sb.append("\n----- 强制停止目标应用 -----\n");
                forceStopScopeAppsInternal(sb);

                if (scopeOk) {
                    sb.append("\n✅ 作用域已配置并重启应用。下次语音指令时各应用会加载最新模块代码。\n");
                } else {
                    sb.append("\n⚠️ 作用域配置未完全成功，已重启应用。请点「检测作用域」确认勾选状态。\n");
                }

                LogManager.i(TAG, "一键勾选作用域并重启结果:\n" + sb.toString());
                final String result = sb.toString();
                runOnUiThread(() -> {
                    mLogView.setText(result);
                    Toast.makeText(MainActivity.this, scopeOk ? "作用域已配置并重启应用"
                            : "作用域配置部分失败，请查看日志", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /**
     * 配置 LSPosed 作用域：把已安装的目标包全部加入模块 scope，enabled=1。
     * 直接修改 LSPosed 配置数据库 modules_config.db 的 modules 表。
     * @param sb 日志追加
     * @return true=配置成功，false=失败
     */
    private boolean ensureLsposedScope(StringBuilder sb) {
        String dbPath = null;
        for (String p : LSPD_DB_PATHS) {
            try {
                byte[] out = suRun("ls -l " + p);
                if (new String(out, "UTF-8").trim().length() > 0) {
                    dbPath = p;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        if (dbPath == null) {
            sb.append("❌ 未找到 LSPosed 配置数据库\n");
            return false;
        }
        sb.append("数据库: ").append(dbPath).append("\n");

        // 收集已安装的目标包
        java.util.LinkedHashSet<String> targetPkgs = new java.util.LinkedHashSet<>();
        for (String pkg : FORCE_STOP_PKGS) {
            try {
                getPackageManager().getPackageInfo(pkg, 0);
                targetPkgs.add(pkg);
            } catch (Throwable ignored) {
            }
        }
        if (targetPkgs.isEmpty()) {
            sb.append("❌ 没有已安装的目标应用\n");
            return false;
        }

        // 备份 + 拷贝到缓存目录
        String cachedDb = new File(getCacheDir(), "lspd_modules_write.db").getAbsolutePath();
        String backupDb = new File(getCacheDir(), "lspd_modules_backup.db").getAbsolutePath();
        try {
            suRun("cp " + dbPath + " " + backupDb);
            suRun("cp " + dbPath + " " + cachedDb + " && chmod 644 " + cachedDb);
        } catch (Throwable t) {
            sb.append("❌ 拷贝数据库失败: ").append(t.getMessage()).append("\n");
            return false;
        }

        boolean ok = false;
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(cachedDb, null, SQLiteDatabase.OPEN_READWRITE);
            // 读当前 scope
            String currentScope = null;
            try {
                android.database.Cursor c = db.rawQuery(
                        "SELECT scope FROM modules WHERE mid=?", new String[]{MODULE_PKG});
                if (c.moveToFirst()) {
                    currentScope = c.getString(0);
                }
                c.close();
            } catch (Throwable t) {
                sb.append("⚠️ 读 scope 失败: ").append(t.getMessage()).append("\n");
            }

            // 合并 scope：旧 scope ∪ 目标 scope
            java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
            if (currentScope != null && !currentScope.isEmpty()) {
                try {
                    JSONArray arr = new JSONArray(currentScope);
                    for (int i = 0; i < arr.length(); i++) {
                        merged.add(arr.getString(i));
                    }
                } catch (JSONException e) {
                    sb.append("⚠️ 旧 scope JSON 解析失败，将覆盖: ").append(currentScope).append("\n");
                }
            }
            java.util.LinkedHashSet<String> added = new java.util.LinkedHashSet<>();
            for (String pkg : targetPkgs) {
                if (!merged.contains(pkg)) {
                    merged.add(pkg);
                    added.add(pkg);
                }
            }
            JSONArray newScope = new JSONArray();
            for (String pkg : merged) {
                newScope.put(pkg);
            }
            String newScopeStr = newScope.toString();

            // 写回：UPDATE 或 INSERT
            try {
                db.execSQL("UPDATE modules SET scope=?, enabled=1 WHERE mid=?",
                        new Object[]{newScopeStr, MODULE_PKG});
            } catch (Throwable t) {
                // 可能没有该模块记录，尝试 INSERT
                try {
                    db.execSQL("INSERT INTO modules (mid, enabled, scope) VALUES (?, 1, ?)",
                            new Object[]{MODULE_PKG, newScopeStr});
                } catch (Throwable t2) {
                    sb.append("❌ 写入 modules 表失败: ").append(t2.getMessage()).append("\n");
                    return false;
                }
            }
            // 清 WAL
            try {
                db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (Throwable ignored) {
            }
            ok = true;
            sb.append("✅ 作用域已配置（共 ").append(merged.size()).append(" 个包）\n");
            if (!added.isEmpty()) {
                sb.append("  新增勾选: ").append(TextUtils.join(", ", added)).append("\n");
            } else {
                sb.append("  （目标包已全部在作用域中，无需新增）\n");
            }
        } catch (Throwable t) {
            sb.append("❌ 操作数据库失败: ").append(t.getMessage()).append("\n");
        } finally {
            if (db != null) {
                try { db.close(); } catch (Throwable ignored) {}
            }
        }
        if (!ok) {
            return false;
        }

        // 拷贝回原路径 + 恢复权限属主 + 清 WAL/SHM
        try {
            suRun("cp " + cachedDb + " " + dbPath
                    + " && chmod 600 " + dbPath
                    + " && chown system:system " + dbPath
                    + " && rm -f " + dbPath + "-wal " + dbPath + "-shm");
        } catch (Throwable t) {
            sb.append("⚠️ 写回数据库后权限修正失败: ").append(t.getMessage()).append("\n");
        }
        new File(cachedDb).delete();
        // 备份保留在缓存目录，用户可自行清理
        return true;
    }

    /**
     * 强制停止所有目标应用（v1.8.4 从原 doForceStopScope 抽出的内部方法，
     * 供一键配置作用域后调用）。返回 ok/fail/skip 计数摘要。
     */
    private void forceStopScopeAppsInternal(StringBuilder sb) {
        int ok = 0, fail = 0, skip = 0;
        for (String pkg : FORCE_STOP_PKGS) {
            boolean installed;
            try {
                getPackageManager().getPackageInfo(pkg, 0);
                installed = true;
            } catch (Throwable t) {
                installed = false;
            }
            if (!installed) {
                sb.append(pkg).append(": 未安装，跳过\n");
                skip++;
                continue;
            }
            String pidBefore = getPidOf(pkg);
            String forceErr = null;
            try {
                suRun("am force-stop " + pkg);
            } catch (Throwable t) {
                forceErr = t.getMessage();
            }
            try { Thread.sleep(600); } catch (Throwable ignored) {}
            String pidAfter = getPidOf(pkg);
            boolean stopped = pidAfter.isEmpty();
            sb.append(pkg).append(": ");
            if (pidBefore.isEmpty()) {
                sb.append("停止前=未运行");
            } else {
                sb.append("停止前 pid=").append(pidBefore);
            }
            sb.append(" → ");
            if (stopped) {
                sb.append("已停止 ✅");
                ok++;
            } else {
                sb.append("仍在运行 pid=").append(pidAfter).append(" ⚠️");
                if (!pidBefore.equals(pidAfter)) {
                    ok++;
                } else {
                    fail++;
                }
            }
            if (forceErr != null) {
                sb.append(" | force-stop 异常: ").append(forceErr);
                fail++;
            }
            sb.append("\n");
        }
        sb.append("小结：成功 ").append(ok).append("，失败 ").append(fail)
                .append("，跳过未安装 ").append(skip).append("\n");
    }

    private void confirmForceStopScope() {
        new AlertDialog.Builder(this)
                .setTitle("强制停止作用域应用")
                .setMessage("将强制停止所有作用域 App（车助理、TXZ语音、QQ音乐等），"
                        + "使其重新加载最新模块代码。\n\n"
                        + "语音和音乐播放会短暂中断；车助理/TXZ等系统服务会被系统自动拉起，"
                        + "QQ音乐等普通应用需下次语音指令时由模块拉起。\n\n"
                        + "需要 Root 权限。是否继续？")
                .setPositiveButton("确定", (d, w) -> doForceStopScope())
                .setNegativeButton("取消", null)
                .show();
    }

    /** @deprecated 保留兼容（已被 doAutoScopeAndRestart 取代） */
    private void doForceStopScope() {
        mLogView.setText("正在强制停止作用域应用…\n");
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append("===== 强制停止作用域应用 ")
                        .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                                .format(new Date()))
                        .append(" =====\n");
                if (!hasRoot()) {
                    sb.append("❌ 无 Root 权限\n");
                    final String r = sb.toString();
                    runOnUiThread(() -> mLogView.setText(r));
                    return;
                }
                forceStopScopeAppsInternal(sb);
                LogManager.i(TAG, "强制停止作用域应用结果:\n" + sb.toString());
                final String result = sb.toString();
                runOnUiThread(() -> {
                    mLogView.setText(result);
                    Toast.makeText(MainActivity.this, "已强制停止作用域应用",
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /**
     * 取某包的主进程 pid（root 下 pidof）。返回空串表示未运行。
     * pidof 可能返回多个 pid（多进程 App），取第一个。
     */
    private String getPidOf(String pkg) {
        try {
            byte[] out = suRun("pidof " + pkg);
            String s = new String(out, "UTF-8").trim();
            if (s.isEmpty()) {
                return "";
            }
            int sp = s.indexOf(' ');
            return sp > 0 ? s.substring(0, sp) : s;
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // LSPosed 作用域检测（读取 LSPosed 配置数据库，列出各作用域勾选状态）
    // ------------------------------------------------------------------

    private static final String MODULE_PKG = "com.syu.voice.hook";
    /** LSPosed 配置数据库可能的路径（不同版本/安装方式） */
    private static final String[] LSPD_DB_PATHS = {
            "/data/adb/lspd/config/modules_config.db",
            "/data/adb/modules/zygisk_lsposed/config/modules_config.db",
            "/data/adb/lspd/config/modules_config.db",
            "/data/adb/lspd/modules_config.db",
    };

    private void checkLsposedScope() {
        mLogView.setText("正在检测 LSPosed 作用域…（需要 Root）\n");
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append("===== LSPosed 作用域检测 ")
                        .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                                .format(new Date()))
                        .append(" =====\n");
                if (!hasRoot()) {
                    sb.append("❌ 无 Root 权限，无法读取 LSPosed 配置\n");
                    final String r = sb.toString();
                    runOnUiThread(() -> mLogView.setText(r));
                    return;
                }
                // 1. 定位数据库
                String dbPath = null;
                for (String p : LSPD_DB_PATHS) {
                    try {
                        byte[] out = suRun("ls -l " + p);
                        if (new String(out, "UTF-8").trim().length() > 0) {
                            dbPath = p;
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (dbPath == null) {
                    sb.append("❌ 未找到 LSPosed 配置数据库（已尝试 4 个常见路径）\n")
                            .append("可能 LSPosed 未安装或路径不同，请手动打开 LSPosed 管理器查看。\n");
                    final String r = sb.toString();
                    runOnUiThread(() -> mLogView.setText(r));
                    return;
                }
                sb.append("数据库: ").append(dbPath).append("\n\n");

                // 2. 拷贝数据库到模块缓存目录（用 Android SQLite API 读，避免 sqlite3 命令缺失）
                String cachedDb = new File(getCacheDir(), "lspd_modules.db").getAbsolutePath();
                String scopeJson = null;
                int enabled = -1;
                try {
                    suRun("cp " + dbPath + " " + cachedDb + " && chmod 644 " + cachedDb);
                    SQLiteDatabase db = SQLiteDatabase.openDatabase(cachedDb, null,
                            SQLiteDatabase.OPEN_READONLY);
                    try {
                        // 表名可能是 modules 或 scope，字段 mid/module_pkg_name
                        android.database.Cursor c = null;
                        try {
                            c = db.rawQuery(
                                    "SELECT scope FROM modules WHERE mid=?",
                                    new String[]{MODULE_PKG});
                            if (c.moveToFirst()) {
                                scopeJson = c.getString(0);
                            }
                            c.close();
                        } catch (Throwable ignored) {
                        }
                        // enabled 字段
                        try {
                            c = db.rawQuery(
                                    "SELECT enabled FROM modules WHERE mid=?",
                                    new String[]{MODULE_PKG});
                            if (c.moveToFirst()) {
                                enabled = c.getInt(0);
                            }
                            c.close();
                        } catch (Throwable ignored) {
                        }
                    } finally {
                        db.close();
                    }
                    new File(cachedDb).delete();
                } catch (Throwable t) {
                    sb.append("❌ 读取数据库失败: ").append(t.getMessage()).append("\n");
                    final String r = sb.toString();
                    runOnUiThread(() -> mLogView.setText(r));
                    return;
                }

                if (enabled == 0) {
                    sb.append("❌ 模块在 LSPosed 中未启用！请在 LSPosed 管理器里打开 fytMusicVoiceInject 开关。\n");
                } else if (enabled == 1) {
                    sb.append("✅ 模块已启用\n");
                } else {
                    sb.append("⚠️ 无法确定模块启用状态\n");
                }

                if (scopeJson == null) {
                    sb.append("❌ 未查到模块的作用域配置（modules 表无此模块记录）\n");
                    sb.append("请在 LSPosed 管理器 → 模块 → fytMusicVoiceInject 里勾选作用域。\n");
                } else {
                    sb.append("\n作用域勾选状态（目标 App）：\n");
                    for (String pkg : FORCE_STOP_PKGS) {
                        boolean checked = scopeJson.contains("\"" + pkg + "\"")
                                || scopeJson.contains(pkg);
                        boolean installed;
                        try {
                            getPackageManager().getPackageInfo(pkg, 0);
                            installed = true;
                        } catch (Throwable t) {
                            installed = false;
                        }
                        sb.append("  ").append(checked ? "✅" : "❌").append(" ").append(pkg);
                        if (!installed) {
                            sb.append("（未安装）");
                        } else if (!checked) {
                            sb.append(" ← 未勾选！请在 LSPosed 勾选此应用");
                        }
                        sb.append("\n");
                    }
                }

                sb.append("\n说明：车助理(com.syu.voice)、TXZ语音(com.txznet.txz)、")
                        .append("QQ音乐HD(com.tencent.qqmusicpad) 三个必须全部勾选，")
                        .append("否则歌单/收藏播放无法生效。\n");

                LogManager.i(TAG, "LSPosed 作用域检测结果:\n" + sb.toString());
                final String result = sb.toString();
                runOnUiThread(() -> mLogView.setText(result));
            }
        }).start();
    }
}
