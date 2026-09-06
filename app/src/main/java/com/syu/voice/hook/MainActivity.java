package com.syu.voice.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

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
        ensureAllFilesPermission();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setBackgroundColor(0xFFF4F3EE);

        TextView title = new TextView(this);
        title.setText("方易通语音助手第三方音乐适配模块");
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
        mStatusView.setText("状态：尚未检查更新\n日志目录：/sdcard/QQMusicVoiceInject/logs/");
        mStatusView.setTextSize(13);
        mStatusView.setTextColor(0xFF4B5563);
        mStatusView.setPadding(12, 12, 12, 12);
        mStatusView.setBackgroundColor(0xFFFFFFFF);
        root.addView(mStatusView);

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

        ScrollView scroll = new ScrollView(this);
        mLogView = new TextView(this);
        mLogView.setTextSize(11);
        mLogView.setTypeface(Typeface.MONOSPACE);
        mLogView.setTextColor(0xFF1A1B1C);
        mLogView.setPadding(8, 8, 8, 8);
        scroll.addView(mLogView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = 8;
        scroll.setLayoutParams(lp);
        root.addView(scroll);

        setContentView(root);
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

    /** Android 11+ 读取 /sdcard 日志需要授权（一次性） */
    private void ensureAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            File logDir = new File(Environment.getExternalStorageDirectory(), "QQMusicVoiceInject/logs");
            if (!logDir.canRead()) {
                new AlertDialog.Builder(this)
                        .setTitle("需要存储权限")
                        .setMessage("查看日志需要访问 /sdcard/QQMusicVoiceInject/logs，请在授权页点击“允许”后返回。\n（注入功能本身不依赖该权限）")
                        .setPositiveButton("去授权", (d, w) -> {
                            try {
                                Intent intent = new Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                                startActivity(intent);
                            } catch (Throwable t) {
                                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
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

    private File logFile() {
        return new File(Environment.getExternalStorageDirectory(),
                "QQMusicVoiceInject/logs/QQMusicVoiceInject.log");
    }

    private void loadLog() {
        try {
            File f = logFile();
            if (!f.exists()) {
                mLogView.setText("（暂无日志。请先重启车机让模块生效，或触发一次语音指令）");
                return;
            }
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            mLogView.setText(sb.length() == 0 ? "（日志为空）" : sb.toString());
        } catch (Throwable t) {
            mLogView.setText("读取日志失败: " + t.getMessage()
                    + "\n请在系统设置中允许「所有文件访问」后重试");
        }
    }

    private void clearLog() {
        File f = logFile();
        if (f.exists()) {
            f.delete();
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        }
        mLogView.setText("");
    }
}
