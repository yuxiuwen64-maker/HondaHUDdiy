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

import java.util.List;
import java.util.Locale;

/** 当前播放信息：歌名、作者、是否在播。 */
public final class NowPlaying {

    public String title = "";
    public String artist = "";
    public boolean playing;

    public static NowPlaying read(Context context) {
        NowPlaying np = new NowPlaying();
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) {
                return np;
            }
            ComponentName cn = new ComponentName(context, LyricListener.class);
            List<MediaController> sessions = msm.getActiveSessions(cn);

            MediaController best = null;
            String bestTitle = "";
            String bestArtist = "";
            boolean bestPlaying = false;

            for (MediaController c : sessions) {
                MediaMetadata md = c.getMetadata();
                if (md == null) {
                    continue;
                }
                String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                if (t == null) {
                    continue;
                }
                t = t.trim();
                if (t.isEmpty() || isPlaceholder(t)) {
                    continue;
                }
                String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                a = a == null ? "" : a.trim();
                if (isPlaceholder(a)) {
                    a = "";
                }

                PlaybackState st = c.getPlaybackState();
                boolean isPlaying = st != null && st.getState() == PlaybackState.STATE_PLAYING;

                // 优先选真正在播放的会话；没有在播的再退回第一条有效元数据。
                if (best == null || (isPlaying && !bestPlaying)) {
                    best = c;
                    bestTitle = t;
                    bestArtist = a;
                    bestPlaying = isPlaying;
                }
                if (isPlaying) {
                    break;
                }
            }

            if (best != null) {
                np.title = bestTitle;
                np.artist = bestArtist;
                np.playing = bestPlaying;
            }
        } catch (Throwable ignored) {
            // 未授予通知访问权限时会失败
        }
        return np;
    }

    /**
     * 车机上有些音频源没有真正播放时，也会给 MediaSession 塞一条
     * 「暂无播放内容」之类的占位元数据；不能把它写到仪表上。
     */
    private static boolean isPlaceholder(String s) {
        if (s == null) {
            return true;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return true;
        }
        String lower = t.toLowerCase(Locale.US);
        if (lower.equals("unknown") || lower.equals("no media") || lower.equals("nothing playing")) {
            return true;
        }
        String[] bad = {
                "暂无播放内容", "暂无内容", "无播放内容", "暂无歌曲", "暂无音乐",
                "未播放", "未知歌曲", "未知艺术家", "未知", "无"
        };
        for (String b : bad) {
            if (t.equals(b)) {
                return true;
            }
        }
        return false;
    }

    /** HUD 上显示的一行文本：歌名 - 作者（没有作者就只显示歌名）。 */
    public String line() {
        String s = artist.isEmpty() ? title : title + " - " + artist;
        return s.length() > 24 ? s.substring(0, 24) : s;
    }
}
