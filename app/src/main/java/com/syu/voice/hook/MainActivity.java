package com.syu.voice.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 模块主界面（v1.8.8 起为纯 UI 编排）：查看版本 / 检查更新 / 下载安装 / 查看运行日志 /
 * 作用域配置与检测。无资源文件依赖，全部代码构建 UI，避免引入额外资源编译。
 *
 * 业务逻辑已拆出：
 * {@link RootShell}             —— root 命令执行 / 设备重启 / pid 查询
 * {@link LogCollector}          —— 各进程日志读取 / 清空 / zip 导出
 * {@link LsposedScopeManager}   —— LSPosed 配置库作用域读写与检测
 * {@link AppControl}            —— 强制停止作用域应用
 * {@link ScopeConfig}           —— 作用域包名 / 路径常量
 */
public class MainActivity extends Activity {

    public static final String TAG = MainHook.TAG;

    private TextView mStatusView;
    private Button mCheckBtn;
    private Button mDownloadBtn;
    private TextView mLogView;

    private RootShell mShell;
    private LogCollector mCollector;
    private LsposedScopeManager mScopeMgr;
    private AppControl mAppControl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 模块 App 进程（com.syu.voice.hook）不是 LSPosed 作用域宿主，
        // MainHook 不会在本进程跑，需自己初始化文件日志，便于记录按钮操作。
        try {
            LogManager.init(this);
        } catch (Throwable ignored) {
        }
        mShell = new RootShell();
        mCollector = new LogCollector(this, mShell);
        mScopeMgr = new LsposedScopeManager(this);
        mAppControl = new AppControl(this, mShell);
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
    // 日志查看 / 清空 / 导出（业务在 LogCollector）
    // ------------------------------------------------------------------

    private void loadLog() {
        mLogView.setText("正在读取日志…（含作用域进程，首次可能弹出 root 授权）");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String text = mCollector.buildLogViewText();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mLogView.setText(text);
                    }
                });
            }
        }).start();
    }

    private void clearLog() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int cleared = mCollector.clearLogs();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this,
                                "已清空 " + cleared + " 个日志文件", Toast.LENGTH_SHORT).show();
                        mLogView.setText("");
                    }
                });
            }
        }).start();
    }

    /**
     * 导出模块 + 全部作用域进程的完整日志。
     * 每次导出重新探测 root（用户可能上次拒绝、刚去 Magisk 完成授权）。
     */
    private void exportLogs() {
        mShell.invalidate();
        Toast.makeText(this, "正在导出…首次使用会弹出 Magisk 授权，请点「允许」",
                Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final LogCollector.ExportResult r;
                try {
                    r = mCollector.exportZip();
                } catch (Throwable t) {
                    LogCollector.ExportResult e = new LogCollector.ExportResult();
                    e.fatalError = t.getClass().getSimpleName() + ": "
                            + (t.getMessage() == null ? t.toString() : t.getMessage());
                    showExportResultOnUi(e);
                    return;
                }
                showExportResultOnUi(r);
            }
        }).start();
    }

    private void showExportResultOnUi(final LogCollector.ExportResult r) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showExportResult(r);
            }
        });
    }

    /**
     * 结果对话框（v1.6.1）：必须点「确定」关闭，不会像 Toast 一闪而过。
     * 三态：✅ 成功 / ⚠️ 导出不完整（缺 root 或作用域日志）/ ❌ 失败，均给出可操作建议。
     */
    private void showExportResult(LogCollector.ExportResult r) {
        boolean failed = r.zipFile == null || r.fatalError != null;
        boolean hasScopeLog = false;
        for (String pkg : r.foundPkgs) {
            if (!ScopeConfig.MODULE_PKG.equals(pkg)) {
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

    // ------------------------------------------------------------------
    // 重启车机
    // ------------------------------------------------------------------

    private void confirmReboot() {
        new AlertDialog.Builder(this)
                .setTitle("确认重启车机")
                .setMessage("重启后 LSPosed 模块会生效。\n\n需要 root 权限，首次使用请在 Magisk 中允许本应用获取 root。")
                .setPositiveButton("立即重启", (d, w) -> {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            if (!mShell.reboot()) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(MainActivity.this,
                                                "重启失败：未获取 root 权限。\n请在 Magisk 中允许本应用获取 root。",
                                                Toast.LENGTH_LONG).show();
                                    }
                                });
                            }
                        }
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------
    // 一键勾选作用域并重启应用（LsposedScopeManager + AppControl）
    // ------------------------------------------------------------------

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
                if (!mShell.hasRoot()) {
                    sb.append("❌ 无 Root 权限\n");
                    setLogText(sb.toString());
                    return;
                }

                // 1. 配置 LSPosed 作用域
                boolean scopeOk = mScopeMgr.ensureScope(sb);

                // 2. 强制停止所有目标应用（无论 scope 配置是否成功都执行，
                //    让已勾选的应用重新加载模块；未勾选的重启后也不注入）
                sb.append("\n----- 强制停止目标应用 -----\n");
                mAppControl.forceStopInstalledTargets(sb);

                if (scopeOk) {
                    sb.append("\n✅ 作用域已配置并重启应用。下次语音指令时各应用会加载最新模块代码。\n");
                } else {
                    sb.append("\n⚠️ 作用域配置未完全成功，已重启应用。请点「检测作用域」确认勾选状态。\n");
                }

                final boolean ok = scopeOk;
                LogManager.i(TAG, "一键勾选作用域并重启结果:\n" + sb.toString());
                final String result = sb.toString();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mLogView.setText(result);
                        Toast.makeText(MainActivity.this, ok ? "作用域已配置并重启应用"
                                : "作用域配置部分失败，请查看日志", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------
    // LSPosed 作用域检测
    // ------------------------------------------------------------------

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
                if (!mShell.hasRoot()) {
                    sb.append("❌ 无 Root 权限，无法读取 LSPosed 配置\n");
                    setLogText(sb.toString());
                    return;
                }
                mScopeMgr.checkScope(sb);
                LogManager.i(TAG, "LSPosed 作用域检测结果:\n" + sb.toString());
                setLogText(sb.toString());
            }
        }).start();
    }

    private void setLogText(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mLogView.setText(text);
            }
        });
    }
}
