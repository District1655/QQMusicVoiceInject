package com.syu.voice.hook;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * LSPosed 作用域配置读写（v1.8.8 从 MainActivity 抽出）。
 *
 * v1.8.6：重写。旧版假设 modules 表有 mid(文本)/scope(JSON)/enabled 三列，
 * 但 LSPosed 1.9.x 实际 schema 是
 *   modules(mid INTEGER PK AUTOINCREMENT, module_pkg_name TEXT, apk_path TEXT, enabled INTEGER)
 *   scope(mid INTEGER, app_pkg_name TEXT, user_id INTEGER)
 * 且 su cp 出来的文件属主是 root、chmod 644 对应用 uid 只读 → OPEN_READWRITE 失败。
 * 现在改为：打开后先自省表结构，按真实 schema 读写；缓存文件 chmod 666；
 * 写回时 cp 覆盖原文件（inode/属主/SELinux 上下文均保留）。
 *
 * 所有方法必须在已确认 root 的后台线程调用。
 */
class LsposedScopeManager {

    private final Context mContext;

    LsposedScopeManager(Context context) {
        mContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
    }

    /**
     * 配置 LSPosed 作用域：把已安装的目标包全部加入模块 scope，enabled=1。
     *
     * @param sb 操作日志追加
     * @return true=配置成功，false=失败
     */
    boolean ensureScope(StringBuilder sb) {
        String dbPath = locateDb(sb);
        if (dbPath == null) {
            return false;
        }

        // 收集已安装的目标包
        LinkedHashSet<String> targetPkgs = new LinkedHashSet<String>();
        for (String pkg : ScopeConfig.TARGET_PACKAGES) {
            try {
                mContext.getPackageManager().getPackageInfo(pkg, 0);
                targetPkgs.add(pkg);
            } catch (Throwable ignored) {
            }
        }
        if (targetPkgs.isEmpty()) {
            sb.append("❌ 没有已安装的目标应用\n");
            return false;
        }

        // 备份 + 拷贝（含 -wal/-shm）到缓存目录，chmod 666 让本应用可读写
        String cachedDb = new File(mContext.getCacheDir(), "lspd_modules_write.db").getAbsolutePath();
        String backupDb = new File(mContext.getCacheDir(), "lspd_modules_backup.db").getAbsolutePath();
        try {
            RootShell.suRun("cp " + dbPath + " " + backupDb + "; true");
            RootShell.suRun("cp " + dbPath + " " + cachedDb + "; "
                    + "cp " + dbPath + "-wal " + cachedDb + "-wal 2>/dev/null; "
                    + "cp " + dbPath + "-shm " + cachedDb + "-shm 2>/dev/null; "
                    + "chmod 666 " + cachedDb + " " + cachedDb + "-wal "
                    + cachedDb + "-shm 2>/dev/null; true");
        } catch (Throwable t) {
            sb.append("❌ 拷贝数据库失败: ").append(t.getMessage()).append("\n");
            return false;
        }

        boolean ok = false;
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(cachedDb, null, SQLiteDatabase.OPEN_READWRITE);

            LinkedHashSet<String> tableSet = new LinkedHashSet<String>();
            Cursor tc = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table'", null);
            while (tc.moveToNext()) {
                tableSet.add(tc.getString(0));
            }
            tc.close();
            Set<String> modulesCols = tableColumns(db, "modules");
            Set<String> scopeCols = tableColumns(db, "scope");

            if (tableSet.contains("modules") && modulesCols.contains("module_pkg_name")) {
                ok = writeStandardSchema(db, sb, targetPkgs, modulesCols, scopeCols);
            } else if (tableSet.contains("modules") && modulesCols.contains("scope")) {
                ok = writeLegacyJsonSchema(db, sb, targetPkgs);
            } else {
                sb.append("❌ 无法识别的数据库 schema，表: ").append(tableSet).append("\n");
                sb.append("  modules 列: ").append(modulesCols).append("\n");
                sb.append("  scope 列: ").append(scopeCols).append("\n");
                sb.append("请把本段日志反馈，按实际 schema 适配。\n");
                ok = false;
            }
            if (ok) {
                try {
                    db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)");
                } catch (Throwable ignored) {
                }
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

        // 写回原路径：cp 覆盖已存在文件，inode/属主/权限/SELinux 上下文保持原样；
        // 清掉残留 -wal/-shm；restorecon 兜底修正上下文（失败可忽略）。
        try {
            RootShell.suRun("cp " + cachedDb + " " + dbPath + "; "
                    + "rm -f " + dbPath + "-wal " + dbPath + "-shm; "
                    + "restorecon -F " + dbPath + " 2>/dev/null; true");
        } catch (Throwable t) {
            sb.append("⚠️ 写回数据库失败: ").append(t.getMessage()).append("\n");
            return false;
        }
        new File(cachedDb).delete();
        new File(cachedDb + "-wal").delete();
        new File(cachedDb + "-shm").delete();
        sb.append("  已写回 ").append(dbPath).append("\n");
        sb.append("  提示：LSPosed 守护进程可能缓存作用域，若重启应用后仍未注入，请重启车机。\n");
        return true;
    }

    /** 标准 LSPosed 1.9.x schema：modules + scope 两张表。返回是否成功。 */
    private boolean writeStandardSchema(SQLiteDatabase db, StringBuilder sb,
                                        LinkedHashSet<String> targetPkgs,
                                        Set<String> modulesCols, Set<String> scopeCols) {
        sb.append("schema: modules+scope 标准结构（module_pkg_name）\n");
        // 1) 找/建模块行
        int mid = -1;
        int enabled = -1;
        Cursor c = db.rawQuery(
                "SELECT mid, enabled FROM modules WHERE module_pkg_name=?",
                new String[]{ScopeConfig.MODULE_PKG});
        if (c.moveToFirst()) {
            mid = c.getInt(0);
            enabled = c.getInt(1);
        }
        c.close();
        if (mid < 0) {
            String apkPath;
            try {
                apkPath = mContext.getPackageManager()
                        .getApplicationInfo(ScopeConfig.MODULE_PKG, 0).sourceDir;
            } catch (Throwable t) {
                apkPath = "";
            }
            if (modulesCols.contains("apk_path")) {
                db.execSQL("INSERT INTO modules (module_pkg_name, apk_path, enabled)"
                        + " VALUES (?, ?, 1)", new Object[]{ScopeConfig.MODULE_PKG, apkPath});
            } else {
                db.execSQL("INSERT INTO modules (module_pkg_name, enabled)"
                        + " VALUES (?, 1)", new Object[]{ScopeConfig.MODULE_PKG});
            }
            sb.append("  modules 表无本模块记录，已新建（mid 自动分配）\n");
            c = db.rawQuery("SELECT mid FROM modules WHERE module_pkg_name=?",
                    new String[]{ScopeConfig.MODULE_PKG});
            if (c.moveToFirst()) {
                mid = c.getInt(0);
            }
            c.close();
        } else if (enabled != 1) {
            db.execSQL("UPDATE modules SET enabled=1 WHERE mid=?",
                    new Object[]{mid});
            sb.append("  模块原 enabled=").append(enabled).append("，已置 1\n");
        }
        if (mid < 0) {
            sb.append("❌ 无法获取/创建模块 mid，终止\n");
            return false;
        }
        // 2) 合并 scope 行
        LinkedHashSet<String> existing = new LinkedHashSet<String>();
        c = db.rawQuery("SELECT app_pkg_name FROM scope WHERE mid=?",
                new String[]{String.valueOf(mid)});
        while (c.moveToNext()) {
            existing.add(c.getString(0));
        }
        c.close();
        LinkedHashSet<String> added = new LinkedHashSet<String>();
        boolean hasUser = scopeCols.contains("user_id");
        for (String pkg : targetPkgs) {
            if (!existing.contains(pkg)) {
                if (hasUser) {
                    db.execSQL("INSERT OR IGNORE INTO scope (mid, app_pkg_name, user_id)"
                            + " VALUES (?, ?, 0)", new Object[]{mid, pkg});
                } else {
                    db.execSQL("INSERT OR IGNORE INTO scope (mid, app_pkg_name)"
                            + " VALUES (?, ?)", new Object[]{mid, pkg});
                }
                added.add(pkg);
            }
        }
        // 3) 回读确认
        LinkedHashSet<String> finalScope = new LinkedHashSet<String>();
        c = db.rawQuery("SELECT app_pkg_name FROM scope WHERE mid=?",
                new String[]{String.valueOf(mid)});
        while (c.moveToNext()) {
            finalScope.add(c.getString(0));
        }
        c.close();
        sb.append("✅ 作用域已配置，当前共 ").append(finalScope.size()).append(" 个包：\n");
        sb.append("  ").append(TextUtils.join(", ", finalScope)).append("\n");
        if (!added.isEmpty()) {
            sb.append("  本次新增勾选: ").append(TextUtils.join(", ", added)).append("\n");
        }
        return true;
    }

    /** 旧 JSON schema 兜底（modules.mid 文本 + scope JSON 列）。返回是否成功。 */
    private boolean writeLegacyJsonSchema(SQLiteDatabase db, StringBuilder sb,
                                          LinkedHashSet<String> targetPkgs) {
        sb.append("schema: modules.scope JSON 结构\n");
        String currentScope = null;
        Cursor c = db.rawQuery(
                "SELECT scope FROM modules WHERE mid=?", new String[]{ScopeConfig.MODULE_PKG});
        if (c.moveToFirst()) {
            currentScope = c.getString(0);
        }
        c.close();
        LinkedHashSet<String> merged = new LinkedHashSet<String>();
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
        LinkedHashSet<String> added = new LinkedHashSet<String>();
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
        int rows = db.update("modules", createScopeValues(newScopeStr),
                "mid=?", new String[]{ScopeConfig.MODULE_PKG});
        if (rows == 0) {
            db.execSQL("INSERT INTO modules (mid, enabled, scope) VALUES (?, 1, ?)",
                    new Object[]{ScopeConfig.MODULE_PKG, newScopeStr});
        }
        sb.append("✅ 作用域已配置（共 ").append(merged.size()).append(" 个包）\n");
        if (!added.isEmpty()) {
            sb.append("  新增勾选: ").append(TextUtils.join(", ", added)).append("\n");
        }
        return true;
    }

    /** ContentValues 小工具（modules.scope JSON 兜底分支用） */
    private static ContentValues createScopeValues(String scopeJson) {
        ContentValues v = new ContentValues();
        v.put("scope", scopeJson);
        v.put("enabled", 1);
        return v;
    }

    /**
     * 只读检测：模块启用状态 + 各目标 App 勾选状态，结果追加到 sb。
     * 找不到数据库时追加错误说明并返回 false。
     */
    boolean checkScope(StringBuilder sb) {
        String dbPath = locateDb(sb);
        if (dbPath == null) {
            sb.append("可能 LSPosed 未安装或路径不同，请手动打开 LSPosed 管理器查看。\n");
            return false;
        }
        sb.append("\n");

        // 拷贝数据库到模块缓存目录（含 -wal/-shm，用 Android SQLite API 读，
        // 避免 sqlite3 命令缺失）。自省 schema 后按真实结构查询。
        String cachedDb = new File(mContext.getCacheDir(), "lspd_modules.db").getAbsolutePath();
        LinkedHashSet<String> scopePkgs = new LinkedHashSet<String>();
        int enabled = -1;
        try {
            RootShell.suRun("cp " + dbPath + " " + cachedDb + "; "
                    + "cp " + dbPath + "-wal " + cachedDb + "-wal 2>/dev/null; "
                    + "cp " + dbPath + "-shm " + cachedDb + "-shm 2>/dev/null; "
                    + "chmod 666 " + cachedDb + " " + cachedDb + "-wal "
                    + cachedDb + "-shm 2>/dev/null; true");
            SQLiteDatabase db = SQLiteDatabase.openDatabase(cachedDb, null,
                    SQLiteDatabase.OPEN_READONLY);
            try {
                LinkedHashSet<String> tables = new LinkedHashSet<String>();
                Cursor tc = db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table'", null);
                while (tc.moveToNext()) {
                    tables.add(tc.getString(0));
                }
                tc.close();
                Set<String> modulesCols = tableColumns(db, "modules");
                if (tables.contains("modules") && modulesCols.contains("module_pkg_name")) {
                    // 标准 LSPosed schema
                    sb.append("schema: modules+scope 标准结构\n");
                    int mid = -1;
                    Cursor c = db.rawQuery(
                            "SELECT mid, enabled FROM modules WHERE module_pkg_name=?",
                            new String[]{ScopeConfig.MODULE_PKG});
                    if (c.moveToFirst()) {
                        mid = c.getInt(0);
                        enabled = c.getInt(1);
                    }
                    c.close();
                    if (mid >= 0) {
                        c = db.rawQuery("SELECT app_pkg_name FROM scope WHERE mid=?",
                                new String[]{String.valueOf(mid)});
                        while (c.moveToNext()) {
                            scopePkgs.add(c.getString(0));
                        }
                        c.close();
                    }
                } else if (tables.contains("modules") && modulesCols.contains("scope")) {
                    // 旧 JSON schema 兜底
                    sb.append("schema: modules.scope JSON 结构\n");
                    String scopeJson = null;
                    Cursor c = db.rawQuery(
                            "SELECT scope, enabled FROM modules WHERE mid=?",
                            new String[]{ScopeConfig.MODULE_PKG});
                    if (c.moveToFirst()) {
                        scopeJson = c.getString(0);
                        enabled = c.getInt(1);
                    }
                    c.close();
                    if (scopeJson != null) {
                        try {
                            JSONArray arr = new JSONArray(scopeJson);
                            for (int i = 0; i < arr.length(); i++) {
                                scopePkgs.add(arr.getString(i));
                            }
                        } catch (JSONException ignored) {
                        }
                    }
                } else {
                    sb.append("❌ 无法识别的数据库 schema，表: ").append(tables).append("\n");
                    sb.append("  modules 列: ").append(modulesCols).append("\n");
                }
            } finally {
                db.close();
            }
            new File(cachedDb).delete();
            new File(cachedDb + "-wal").delete();
            new File(cachedDb + "-shm").delete();
        } catch (Throwable t) {
            sb.append("❌ 读取数据库失败: ").append(t.getMessage()).append("\n");
            return false;
        }

        if (enabled == 0) {
            sb.append("❌ 模块在 LSPosed 中未启用！请在 LSPosed 管理器里打开 fytMusicVoiceInject 开关。\n");
        } else if (enabled == 1) {
            sb.append("✅ 模块已启用\n");
        } else {
            sb.append("⚠️ 无法确定模块启用状态\n");
        }

        if (enabled == -1) {
            sb.append("❌ 未查到模块记录（modules 表无 com.syu.voice.hook）\n");
            sb.append("请在 LSPosed 管理器 → 模块 → fytMusicVoiceInject 里勾选作用域。\n");
        } else {
            sb.append("\n作用域勾选状态（目标 App）：\n");
            for (String pkg : ScopeConfig.TARGET_PACKAGES) {
                boolean checked = scopePkgs.contains(pkg);
                boolean installed;
                try {
                    mContext.getPackageManager().getPackageInfo(pkg, 0);
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
        return true;
    }

    /** 定位 LSPosed 配置数据库，找不到返回 null（并写日志） */
    private String locateDb(StringBuilder sb) {
        for (String p : ScopeConfig.LSPD_DB_PATHS) {
            try {
                byte[] out = RootShell.suRun("ls -l " + p + " 2>/dev/null");
                if (new String(out, "UTF-8").trim().length() > 0) {
                    sb.append("数据库: ").append(p).append("\n");
                    return p;
                }
            } catch (Throwable ignored) {
            }
        }
        sb.append("❌ 未找到 LSPosed 配置数据库（已尝试 ").append(ScopeConfig.LSPD_DB_PATHS.length)
                .append(" 个常见路径）\n");
        return null;
    }

    /** 自省表列名（PRAGMA table_info） */
    private static Set<String> tableColumns(SQLiteDatabase db, String table) {
        LinkedHashSet<String> cols = new LinkedHashSet<String>();
        try {
            Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
            int nameIdx = c.getColumnIndex("name");
            while (c.moveToNext()) {
                cols.add(c.getString(nameIdx));
            }
            c.close();
        } catch (Throwable ignored) {
        }
        return cols;
    }
}
