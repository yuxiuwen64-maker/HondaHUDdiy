/*
 * Copyright (C) 2026 yuxiuwen64-maker
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.hudfree;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 兜底取词：用 uiautomator dump 拿当前界面 XML，再从中挑歌词行。
 *
 * 不依赖无障碍服务，只需要 root。dump 一次约 1–2 秒，所以做了 3 秒节流。
 */
public final class UiDumpLyric {

    private static final String TMP = "/data/local/tmp/hud_ui.xml";
    private static final long MIN_INTERVAL_MS = 3000;

    private static final Pattern TEXT = Pattern.compile("text=\"([^\"]{3,24})\"");
    private static final String[] NOISE = {
            "播放", "暂停", "下一首", "上一首", "歌词", "搜索", "列表",
            "QQ音乐", "网易云", "酷狗", "音质", "下载", "收藏", "评论"
    };

    private static long lastRun;
    private static String lastLine;

    private UiDumpLyric() {
    }

    /** 返回最近一次 dump 里像歌词的那行；失败或节流期内返回上次结果。 */
    public static String read() {
        long now = System.currentTimeMillis();
        if (now - lastRun < MIN_INTERVAL_MS) {
            return lastLine;
        }
        lastRun = now;

        String xml = dump();
        if (xml == null || xml.isEmpty()) {
            return lastLine;
        }

        Matcher m = TEXT.matcher(xml);
        String best = null;
        while (m.find()) {
            String s = m.group(1).trim();
            if (isLyricLike(s)) {
                best = s;
            }
        }
        if (best != null) {
            lastLine = best;
        }
        return lastLine;
    }

    private static String dump() {
        exec("su", "-c", "uiautomator dump " + TMP + " >/dev/null 2>&1");
        byte[] b = execBytes("su", "-c", "cat " + TMP);
        return b == null ? null : new String(b);
    }

    private static boolean isLyricLike(String s) {
        if (s.length() < 3 || s.length() > 24) {
            return false;
        }
        for (String n : NOISE) {
            if (s.contains(n)) {
                return false;
            }
        }
        return s.matches(".*[\\u4e00-\\u9fa5A-Za-z].*");
    }

    private static void exec(String... cmd) {
        execBytes(cmd);
    }

    private static byte[] execBytes(String... cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            p.waitFor();
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }
}
