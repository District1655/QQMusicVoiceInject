package com.syu.voice.hook;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * root（su）命令执行封装（v1.8.8 从 MainActivity 抽出）。
 * 一个实例持有一份 root 探测缓存；需要重新探测（如用户刚完成 Magisk 授权）时调 {@link #invalidate()}。
 */
class RootShell {

    private Boolean mRootCache;

    /** 是否有可用 root（结果缓存；首次探测可能触发 Magisk 授权弹窗，应在后台线程调用） */
    boolean hasRoot() {
        if (mRootCache == null) {
            try {
                mRootCache = new String(suRun("id"), "UTF-8").contains("uid=0");
            } catch (Throwable t) {
                mRootCache = false;
            }
        }
        return mRootCache;
    }

    /** 清除缓存的 root 状态，下次 hasRoot() 重新探测 */
    void invalidate() {
        mRootCache = null;
    }

    /** 外部已自行探测过 root 时直接写入结果，避免后续重复 su 调用/弹窗 */
    void setRootCached(boolean root) {
        mRootCache = root;
    }

    /**
     * 执行 su 命令并返回 stdout（先读完输出再 waitFor，防大输出撑爆管道死锁）。
     * 退出码非 0 抛 RuntimeException（携带 stderr）。
     */
    static byte[] suRun(String cmd) throws Exception {
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

    /** 执行普通（无 root）命令并返回 stdout */
    static byte[] run(String[] cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(cmd);
        byte[] out = readAll(p.getInputStream());
        p.waitFor();
        return out;
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }

    /**
     * 取某包的主进程 pid（root 下 pidof）。返回空串表示未运行/失败。
     * pidof 可能返回多个 pid（多进程 App），取第一个。
     */
    String getPidOf(String pkg) {
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

    /** 重启设备：reboot 优先，极少数设备不生效时 fallback 到 svc power reboot。失败返回 false。 */
    boolean reboot() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "reboot"});
            p.waitFor();
            try {
                Runtime.getRuntime().exec(new String[]{"su", "-c", "svc power reboot"});
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
