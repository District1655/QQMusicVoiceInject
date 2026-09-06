package com.syu.voice.hook;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 在线更新：GitHub Releases 检查 -> DownloadManager 下载 -> 安装。
 *
 * 版本约定：Actions 发布 Release 的 tag 形如 vX.Y.Z（如 v1.1.0），
 * 与 app versionCode（10100 = 1*10000+1*100+0）对应。
 */
public final class UpdateManager {

    public static final String TAG = MainHook.TAG;

    /** GitHub 仓库（owner/repo） */
    public static final String REPO = "District1655/QQMusicVoiceInject";
    private static final String RELEASES_API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    /** 最新 Release 信息 */
    public static final class ReleaseInfo {
        public String tagName;
        public String body;
        public String downloadUrl;
        public int versionCode;
    }

    private UpdateManager() {
    }

    /** 检查最新 Release（网络操作，需在子线程调用） */
    public static ReleaseInfo checkLatest() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(RELEASES_API);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "QQMusicVoiceInject");
            int code = conn.getResponseCode();
            if (code != 200) {
                LogManager.w(TAG, "检查更新 HTTP " + code);
                return null;
            }
            InputStream is = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            ReleaseInfo info = new ReleaseInfo();
            info.tagName = root.optString("tag_name", "");
            info.body = root.optString("body", "");
            info.versionCode = parseTagVersion(info.tagName);
            JSONArray assets = root.optJSONArray("assets");
            if (assets != null) {
                // 优先选择本模块命名规范的产物（QQMusicVoiceInject-*.apk），
                // 避免误选历史遗留的同名 app-debug.apk
                String fallback = null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String name = a.optString("name", "");
                    if (!name.endsWith(".apk")) {
                        continue;
                    }
                    if (fallback == null) {
                        fallback = a.optString("browser_download_url", "");
                    }
                    if (name.startsWith("QQMusicVoiceInject")) {
                        info.downloadUrl = a.optString("browser_download_url", "");
                        break;
                    }
                }
                if (info.downloadUrl == null) {
                    info.downloadUrl = fallback;
                }
            }
            return info;
        } catch (Throwable t) {
            LogManager.e(TAG, "检查更新失败", t);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 解析 tag（vX.Y.Z）为 versionCode */
    public static int parseTagVersion(String tag) {
        try {
            String v = tag.replaceAll("[^0-9.]", "");
            String[] parts = v.split("\\.");
            int code = 0;
            int[] weight = {10000, 100, 1};
            for (int i = 0; i < parts.length && i < weight.length; i++) {
                code += Integer.parseInt(parts[i]) * weight[i];
            }
            return code;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 是否比当前版本新 */
    public static boolean isNewer(ReleaseInfo info, int currentVersionCode) {
        return info != null && info.downloadUrl != null && info.versionCode > currentVersionCode;
    }

    /** 通过 DownloadManager 下载 APK 到公共 Download 目录，返回 downloadId */
    public static long download(Context context, String url) {
        try {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("QQ音乐语音注入更新");
            req.setDescription("正在下载新版本 APK");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "QQMusicVoiceInject.apk");
            return dm.enqueue(req);
        } catch (Throwable t) {
            LogManager.e(TAG, "发起下载失败", t);
            return -1;
        }
    }

    /** 根据 downloadId 获取下载完成的 APK Uri（DownloadManager 自带 provider，可直接安装） */
    public static Uri getDownloadedApkUri(Context context, long downloadId) {
        try {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            Uri uri = dm.getUriForDownloadedFile(downloadId);
            if (uri != null) {
                return uri;
            }
            // 兜底：直接指向 Download 目录文件
            return Uri.fromFile(new java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "QQMusicVoiceInject.apk"));
        } catch (Throwable t) {
            LogManager.e(TAG, "获取下载文件失败", t);
            return null;
        }
    }

    /** 安装 APK（下载完成后调用） */
    public static void install(Context context, Uri apkUri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
            LogManager.i(TAG, "发起安装: " + apkUri);
        } catch (Throwable t) {
            LogManager.e(TAG, "发起安装失败（请在系统设置允许未知来源安装）", t);
        }
    }

    /** 注册下载完成广播接收器 */
    public static void registerDownloadReceiver(Context context, long downloadId) {
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != downloadId) {
                    return;
                }
                ctx.unregisterReceiver(this);
                Uri uri = getDownloadedApkUri(ctx, downloadId);
                if (uri != null) {
                    install(ctx, uri);
                }
            }
        }, filter);
    }

    // 备用：查询下载状态（供 UI 显示）
    public static int queryDownloadStatus(Context context, long downloadId) {
        try {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Query q = new DownloadManager.Query();
            q.setFilterById(downloadId);
            Cursor c = dm.query(q);
            if (c != null && c.moveToFirst()) {
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                c.close();
                return status;
            }
        } catch (Throwable t) {
            Log.e(TAG, "queryDownloadStatus", t);
        }
        return -1;
    }
}
