/*
 * Copyright (C) 2026 yuxiuwen64-maker
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.hudfree;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private static final String FILE = "hud";

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return get(c).getBoolean("enabled", false);
    }

    public static void setEnabled(Context c, boolean v) {
        get(c).edit().putBoolean("enabled", v).apply();
    }

    /** 逗号分隔的十进制属性 ID。0x2140B1B7 = 557887927 已实车确认是续航 */
    public static String propIds(Context c) {
        String s = get(c).getString("props", "557887927");
        return s == null || s.trim().isEmpty() ? "557887927" : s;
    }

    public static void setPropIds(Context c, String v) {
        get(c).edit().putString("props", v).apply();
    }

    /** 高德导航投屏开关 */
    public static boolean amapNavi(Context c) {
        return get(c).getBoolean("amap_navi", true);
    }

    public static void setAmapNavi(Context c, boolean v) {
        get(c).edit().putBoolean("amap_navi", v).apply();
    }

    /** 转向箭头颜色（0 = 用默认） */
    public static int arrowColor(Context c) {
        return get(c).getInt("color", 0);
    }

    /** 暂停图标值；-1 表示不写 */
    public static int iconPaused(Context c) {
        return get(c).getInt("icon_paused", -1);
    }
}
