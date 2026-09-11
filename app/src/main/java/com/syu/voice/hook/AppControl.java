package com.syu.voice.hook;

import android.content.Context;

/**
 * 作用域应用进程控制（v1.8.8 从 MainActivity 抽出）：
 * 强制停止全部已安装的目标 App，使其重新加载最新模块代码。
 * 必须在已确认 root 的后台线程调用。
 */
class AppControl {

    private final Context mContext;
    private final RootShell mShell;

    AppControl(Context context, RootShell shell) {
        mContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        mShell = shell;
    }

    /**
     * 逐个强制停止目标应用，记录停止前后 pid 到 sb。
     * 系统应用（车助理/TXZ）停止后会被系统自动拉起；普通应用需下次语音指令由模块拉起。
     */
    void forceStopInstalledTargets(StringBuilder sb) {
        int ok = 0, fail = 0, skip = 0;
        for (String pkg : ScopeConfig.TARGET_PACKAGES) {
            boolean installed;
            try {
                mContext.getPackageManager().getPackageInfo(pkg, 0);
                installed = true;
            } catch (Throwable t) {
                installed = false;
            }
            if (!installed) {
                sb.append(pkg).append(": 未安装，跳过\n");
                skip++;
                continue;
            }
            String pidBefore = mShell.getPidOf(pkg);
            String forceErr = null;
            try {
                RootShell.suRun("am force-stop " + pkg);
            } catch (Throwable t) {
                forceErr = t.getMessage();
            }
            try { Thread.sleep(600); } catch (Throwable ignored) {}
            String pidAfter = mShell.getPidOf(pkg);
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
}
