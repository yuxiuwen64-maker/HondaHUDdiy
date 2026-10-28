/*
 * Copyright (C) 2026 yuxiuwen64-maker
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.hudfree;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 取当前歌词行。
 *
 * 两条来源：
 *  A. MediaSession 的 metadata（音乐 App 自己暴露的当前播放信息）
 *  B. QQ 音乐车机版的歌词缓存文件（本机文件，UTF-16LE 的 JSON）
 *
 * 不解析任何加密歌词格式。
 */
public final class LyricReader {

    private static final String QQ_CACHE =
            "/data/data/com.tencent.qqmusiccar/cache/QPlayEdge/lyric/";

    private static final class Line {
        final long time;
        final String text;

        Line(long time, String text) {
            this.time = time;
            this.text = text;
        }
    }

    private final List<Line> lines = new ArrayList<>();
    private long positionMs;
    private String lastSource = "";

    /** 每秒调用一次：刷新播放进度与歌词行。 */
    public String currentLine(Context context) {
        // C 路优先级最高：屏幕上已经显示出来的那行最新
        String onScreen = LyricA11y.fresh(6000);
        if (onScreen != null) {
            return onScreen.length() > 16 ? onScreen.substring(0, 16) : onScreen;
        }

        String lrc = fromSession(context);
        if (lrc == null || lrc.isEmpty()) {
            lrc = fromCache();
        }
        if (lrc == null || lrc.isEmpty()) {
            // D 路兜底：无障碍拿不到时，用 uiautomator dump 读界面
            String dumped = UiDumpLyric.read();
            if (dumped != null) {
                return dumped.length() > 16 ? dumped.substring(0, 16) : dumped;
            }
            return null;
        }
        if (lines.isEmpty() || !lastSource.equals(lrc)) {
            parse(lrc);
            lastSource = lrc;
        }
        return pick();
    }

    // ---------- A：MediaSession ----------

    private String fromSession(Context context) {
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) {
                return null;
            }
            ComponentName cn = new ComponentName(context, LyricListener.class);
            List<MediaController> sessions = msm.getActiveSessions(cn);
            for (MediaController c : sessions) {
                String pkg = c.getPackageName();
                if (pkg == null) {
                    continue;
                }
                PlaybackState st = c.getPlaybackState();
                positionMs = st == null ? 0 : st.getPosition();

                MediaMetadata md = c.getMetadata();
                if (md == null) {
                    continue;
                }
                String lyric = md.getString("song_lyric");
                if (lyric != null && !lyric.isEmpty()) {
                    return lyric;
                }
            }
        } catch (Throwable ignored) {
            // 没有通知访问权限时会抛 SecurityException
        }
        return null;
    }

    // ---------- B：QQ 音乐缓存 ----------

    private String fromCache() {
        try {
            String name = exec("su", "-c", "ls -t " + QQ_CACHE + " 2>/dev/null | head -1");
            if (name == null || name.trim().isEmpty()) {
                return null;
            }
            byte[] bytes = execBytes("su", "-c", "cat " + QQ_CACHE + name.trim());
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            String text = new String(bytes, "UTF-16LE");
            if (!text.contains("song_lyric")) {
                text = new String(bytes, "UTF-8");
            }
            return extractSongLyric(text);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 从 JSON 文本里取出 song_lyric 的字符串值（处理换行、引号、反斜杠和 u-XXXX 转义）。 */
    private static String extractSongLyric(String json) {
        String key = "\"song_lyric\":\"";
        int start = json.indexOf(key);
        if (start < 0) {
            return null;
        }
        int i = start + key.length();
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') {
                break;
            }
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(i + 1);
                if (n == 'n') {
                    sb.append('\n');
                    i += 2;
                    continue;
                }
                if (n == '"' || n == '\\' || n == '/') {
                    sb.append(n);
                    i += 2;
                    continue;
                }
                if (n == 'u' && i + 5 < json.length()) {
                    try {
                        sb.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                        i += 6;
                        continue;
                    } catch (Throwable ignored) {
                    }
                }
                sb.append(n);
                i += 2;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    // ---------- LRC ----------

    private void parse(String lrc) {
        lines.clear();
        for (String raw : lrc.split("\n")) {
            String s = raw.trim();
            if (!s.startsWith("[")) {
                continue;
            }
            int close = s.indexOf(']');
            if (close < 4) {
                continue;
            }
            String stamp = s.substring(1, close);
            String text = s.substring(close + 1).trim();
            if (text.isEmpty() || text.startsWith("[")) {
                continue;
            }
            long t = parseStamp(stamp);
            if (t >= 0) {
                lines.add(new Line(t, text));
            }
        }
    }

    private static long parseStamp(String stamp) {
        try {
            String[] p = stamp.split("[:.]");
            if (p.length < 2) {
                return -1;
            }
            int min = Integer.parseInt(p[0]);
            int sec = Integer.parseInt(p[1]);
            int ms = p.length > 2 ? Integer.parseInt(p[2]) : 0;
            return min * 60_000L + sec * 1000L + ms;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private String pick() {
        if (lines.isEmpty()) {
            return null;
        }
        Line current = lines.get(0);
        for (Line l : lines) {
            if (l.time <= positionMs) {
                current = l;
            } else {
                break;
            }
        }
        String text = current.text;
        return text.length() > 16 ? text.substring(0, 16) : text;
    }

    // ---------- shell ----------

    private static String exec(String... cmd) {
        byte[] b = execBytes(cmd);
        return b == null ? null : new String(b).trim();
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
