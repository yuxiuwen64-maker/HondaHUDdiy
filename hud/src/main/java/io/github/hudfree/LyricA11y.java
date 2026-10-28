/*
 * Copyright (C) 2026 yuxiuwen64-maker
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.hudfree;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * 读屏取词：从音乐 App 已经显示出来的界面上取当前歌词行。
 *
 * 只是读取屏幕上已有的文字，不解析任何加密文件。
 */
public class LyricA11y extends AccessibilityService {

    private static final String[] PKGS = {
            "com.tencent.qqmusic",
            "com.netease.cloudmusic",
            "com.kugou.android",
            "com.ximalaya.ting.android"
    };

    private static final String[] NOISE = {
            "播放", "暂停", "下一首", "上一首", "歌词", "搜索", "列表",
            "QQ音乐", "网易云", "酷狗", "音质", "下载", "收藏", "评论"
    };

    private static volatile String line;
    private static volatile long at;

    /** 取最近 maxAgeMs 内捕获到的歌词行；过期或没有则返回 null。 */
    public static String fresh(long maxAgeMs) {
        String s = line;
        if (s == null) {
            return null;
        }
        if (System.currentTimeMillis() - at > maxAgeMs) {
            return null;
        }
        return s;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !allowed(pkg.toString())) {
            return;
        }

        String best = null;
        for (CharSequence cs : event.getText()) {
            String s = cs == null ? null : cs.toString().trim();
            if (isLyricLike(s)) {
                best = s;
            }
        }
        if (best == null) {
            CharSequence desc = event.getContentDescription();
            if (desc != null && isLyricLike(desc.toString().trim())) {
                best = desc.toString().trim();
            }
        }
        if (best != null) {
            line = best;
            at = System.currentTimeMillis();
        }
    }

    private static boolean allowed(String pkg) {
        for (String p : PKGS) {
            if (pkg.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLyricLike(String s) {
        if (s == null) {
            return false;
        }
        int len = s.length();
        if (len < 3 || len > 24) {
            return false;
        }
        for (String n : NOISE) {
            if (s.contains(n)) {
                return false;
            }
        }
        // 纯标点/数字（进度条、时间）不要
        return s.matches(".*[\\u4e00-\\u9fa5A-Za-z].*");
    }

    @Override
    public void onInterrupt() {
    }
}
