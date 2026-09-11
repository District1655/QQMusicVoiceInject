package com.syu.voice.hook;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
 * 各作用域进程文件日志的读取 / 清空 / zip 导出（v1.8.8 从 MainActivity 抽出）。
 * 所有方法都设计为在后台线程调用（可能触发 root 授权/阻塞 IO）。
 */
class LogCollector {

    /** LSPosed 框架日志目录候选 */
    private static final String[] LSPOSED_LOG_DIRS = {
            "/data/adb/lspd/log/",
            "/data/misc/lspd/log/",
            "/data/adb/lspd/",
    };

    private static final String[] LSPOSED_LOGCAT_TAGS = {
            "LSPosed", "LSPosed-Bridge", "LSPosedManager", "Xposed", "XSharedPreferences"};

    private final Context mContext;
    private final RootShell mShell;

    LogCollector(Context context, RootShell shell) {
        mContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        mShell = shell;
    }

    /** 某包的候选日志路径：sdcard 外部私有目录 + /data/media（root 绕过 FUSE）+ /data/data 内部私有目录（fallback） */
    private static List<File> logFileCandidates(String pkg, String suffix) {
        List<File> files = new ArrayList<File>();
        String name = ScopeConfig.LOG_NAME + suffix;
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
    byte[] readLogBestEffort(String pkg, String suffix) {
        // 直读（自己目录或 ROM 放开的场景）
        for (File f : logFileCandidates(pkg, suffix)) {
            if (f.exists() && f.isFile() && f.canRead()) {
                try {
                    byte[] data = RootShell.readAll(new FileInputStream(f));
                    if (data.length > 0) {
                        return data;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        // root 兜底：cat 逐个候选路径，读到内容即返回
        if (mShell.hasRoot()) {
            for (File f : logFileCandidates(pkg, suffix)) {
                try {
                    byte[] data = RootShell.suRun("cat " + f.getAbsolutePath());
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
     * @return 版本号字符串（如 1.8.2）；无加载记录返回 null
     */
    static String parseLoadedVersion(byte[] data) {
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

    /** 「查看运行日志」：拼接全部作用域进程日志（无日志的包列在末尾提示）。 */
    String buildLogViewText() {
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        final List<String> empty = new ArrayList<String>();
        for (String pkg : ScopeConfig.LOG_PACKAGES) {
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
        return sb.toString();
    }

    /** 「清空日志」：删除全部进程的日志文件（直删 + root rm 兜底）。返回删除文件数。 */
    int clearLogs() {
        int cleared = 0;
        for (String pkg : ScopeConfig.LOG_PACKAGES) {
            for (String suffix : ScopeConfig.LOG_SUFFIXES) {
                for (File f : logFileCandidates(pkg, suffix)) {
                    if (!f.exists()) {
                        continue;
                    }
                    if (f.delete()) {
                        cleared++;
                    } else if (mShell.hasRoot()) {
                        // 分区存储下删不了其他 App 目录的文件，root 删
                        try {
                            RootShell.suRun("rm -f " + f.getAbsolutePath());
                            cleared++;
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
        return cleared;
    }

    // ------------------------------------------------------------------
    // 一键导出完整日志（v1.6.0；v1.6.1 结果三态化——失败/残缺明确可识别）
    // ------------------------------------------------------------------

    /** 导出结果：成功 / 不完整（缺 root 或作用域日志）/ 失败 三态 */
    static final class ExportResult {
        File zipFile;            // 最终发布的 zip；null=彻底失败
        boolean rootOk;          // 是否拿到 root
        String rootError;        // root 失败原因（可空）
        final List<String> foundPkgs = new ArrayList<String>(); // 读到日志的包名
        boolean logcatOk;        // logcat 是否入包
        String fatalError;       // 致命错误（zip 未生成/一个进程日志都没有）
    }

    /**
     * 导出模块 + 全部作用域进程的完整日志：
     *   &lt;pkg&gt;/fytMusicVoiceInject.log[.1~.3]  各进程文件日志（直读 + root 兜底）
     *   logcat/logcat_filtered.txt            logcat 关键行（fytMusic/Xposed/AndroidRuntime）
     *   logcat/logcat_recent.txt              logcat 最近 30 万行（root 时）
     *   logcat/logcat_lsposed.txt             LSPosed 相关 tag 行（v1.8.5）
     *   lsposed/                              LSPosed 框架日志文件（v1.8.5）
     *   info.txt                              版本/环境/各进程文件清单
     * 有 root：zip 落 /sdcard/Download/（su cp + chmod）；
     * 无 root：zip 落模块自己外部目录，由调用方提示"导出不完整"。
     */
    ExportResult exportZip() {
        ExportResult r = new ExportResult();

        // 1) root 探测（触发 Magisk 授权弹窗；调用方应在后台线程阻塞等待用户响应）
        try {
            byte[] id = RootShell.suRun("id");
            r.rootOk = new String(id, "UTF-8").contains("uid=0");
            if (!r.rootOk) {
                r.rootError = "su id 未返回 uid=0";
            }
        } catch (Throwable t) {
            r.rootOk = false;
            r.rootError = t.getMessage() == null ? t.toString() : t.getMessage();
        }
        mShell.setRootCached(r.rootOk); // 让 readLogBestEffort 与本次探测结果一致，避免重复 su

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String zipName = "fytMusicVoiceInject_logs_" + stamp + ".zip";

        File cacheDir = new File(mContext.getCacheDir(), "export");
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
                for (String pkg : ScopeConfig.LOG_PACKAGES) {
                    StringBuilder pkgLine = new StringBuilder("  ").append(pkg).append(':');
                    boolean hasAny = false;
                    String loadedVer = null;
                    for (String suffix : ScopeConfig.LOG_SUFFIXES) {
                        byte[] data = readLogBestEffort(pkg, suffix);
                        if (data == null) {
                            continue;
                        }
                        putZipEntry(zos, pkg + "/" + ScopeConfig.LOG_NAME + suffix, data);
                        pkgLine.append(" ").append(ScopeConfig.LOG_NAME + suffix)
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
                        logcatAll = RootShell.suRun("logcat -d -t 300000");
                    } catch (Throwable t) {
                        info.add("logcat root 读取失败: " + t.getMessage());
                    }
                }
                if (logcatAll == null) {
                    // 无 root：尝试直读（部分车机 ROM 放开 logcat 权限），只取模块 tag
                    try {
                        logcatAll = RootShell.run(new String[]{
                                "logcat", "-d", "-t", "3000", "-s", "fytMusicVoice"});
                    } catch (Throwable t) {
                        info.add("logcat 直读失败: " + t.getMessage());
                    }
                }
                r.logcatOk = logcatAll != null && logcatAll.length > 0;
                if (r.logcatOk) {
                    putZipEntry(zos, "logcat/logcat_recent.txt", logcatAll);
                    putZipEntry(zos, "logcat/logcat_filtered.txt", filterLines(logcatAll));
                    // v1.8.5：LSPosed 框架日志（模块注入/加载记录）
                    byte[] lsposedLogcat = filterLogcatTags(logcatAll, LSPOSED_LOGCAT_TAGS);
                    if (lsposedLogcat != null && lsposedLogcat.length > 0) {
                        putZipEntry(zos, "logcat/logcat_lsposed.txt", lsposedLogcat);
                    }
                } else {
                    info.add("logcat 不可读（无 root 且 ROM 限制）");
                }

                // v1.8.5：4) LSPosed 框架日志文件（/data/adb/lspd/log/ 等）
                info.add("");
                info.add("LSPosed 框架日志:");
                int lsposedFileCount = 0;
                if (r.rootOk) {
                    for (String dir : LSPOSED_LOG_DIRS) {
                        try {
                            byte[] ls = RootShell.suRun("ls -1 " + dir + " 2>/dev/null");
                            String files = new String(ls, "UTF-8").trim();
                            if (files.isEmpty()) {
                                continue;
                            }
                            for (String fn : files.split("\n")) {
                                fn = fn.trim();
                                if (fn.isEmpty()) {
                                    continue;
                                }
                                // 只取日志文件，排除子目录
                                try {
                                    byte[] fdata = RootShell.suRun("cat " + dir + fn);
                                    if (fdata != null && fdata.length > 0) {
                                        putZipEntry(zos, "lsposed/" + fn, fdata);
                                        info.add("  " + dir + fn + " (" + fdata.length + "B)");
                                        lsposedFileCount++;
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (lsposedFileCount == 0) {
                    info.add("  未找到 LSPosed 日志文件（无 root 或路径不同）");
                }

                // 5) info.txt
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
                RootShell.suRun("cp " + tmpZip.getAbsolutePath() + " " + dst
                        + " && chmod 644 " + dst);
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
                File fallback = new File(mContext.getExternalFilesDir(null), zipName);
                copyFile(tmpZip, fallback);
                r.zipFile = fallback;
            } catch (Throwable t) {
                r.fatalError = "zip 发布失败: " + t.getMessage();
            }
        }
        return r;
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

    /**
     * v1.8.5：按 logcat 标签精确过滤。Android logcat 格式为
     * "MM-DD HH:mm:ss.sss  PID  TID LEVEL TAG: msg"，标签在级别后、冒号前。
     * 匹配 " TAG:" 前缀，避免误伤消息正文。
     */
    private static byte[] filterLogcatTags(byte[] logcat, String[] tags) {
        try {
            String[] lines = new String(logcat, "UTF-8").split("\n");
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                for (String tag : tags) {
                    // 匹配 " TAG:"（空格+标签+冒号），兼容 brief/threadtime 格式
                    if (line.contains(" " + tag + ":")) {
                        sb.append(line).append('\n');
                        break;
                    }
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
}
