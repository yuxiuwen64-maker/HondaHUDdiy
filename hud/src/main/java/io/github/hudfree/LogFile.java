/*
 * Copyright (C) 2026 yuxiuwen64-maker
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.hudfree;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 同时写 logcat 和文件。
 *
 * 内部存储（root 可直接读）：/data/data/io.github.hudfree/files/hud.log
 * 外部镜像（方便文件管理器取）：/sdcard/Android/data/io.github.hudfree/files/hud.log
 *
 * 超过 512KB 自动滚成 hud.log.1。
 */
public final class LogFile {

    private static final long MAX = 512 * 1024;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static File primary;
    private static File mirror;
    private static boolean inited;

    private LogFile() {
    }

    public static synchronized void init(Context context) {
        if (inited || context == null) {
            return;
        }
        inited = true;
        try {
            primary = new File(context.getFilesDir(), "hud.log");
        } catch (Throwable ignored) {
        }
        try {
            File ext = context.getExternalFilesDir(null);
            if (ext != null) {
                mirror = new File(ext, "hud.log");
            }
        } catch (Throwable ignored) {
        }
    }

    public static File file() {
        return primary;
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        write("I/" + tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        write("W/" + tag, msg);
    }

    private static synchronized void write(String tag, String msg) {
        String line = FMT.format(new Date()) + " " + tag + ": "
                + (msg == null ? "null" : msg) + "\n";
        append(primary, line);
        append(mirror, line);
    }

    private static void append(File f, String line) {
        if (f == null) {
            return;
        }
        try {
            if (f.length() > MAX) {
                File old = new File(f.getParentFile(), f.getName() + ".1");
                if (old.exists()) {
                    old.delete();
                }
                f.renameTo(old);
            }
            FileWriter w = new FileWriter(f, true);
            try {
                w.write(line);
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
