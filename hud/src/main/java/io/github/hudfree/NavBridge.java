package io.github.hudfree;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 高德导航投屏。
 *
 * 监听高德的 AUTONAVI_STANDARD_BROADCAST_SEND：
 *   KEY_TYPE=10019  导航状态：EXTRA_STATE 8=进入导航，9/2=结束（清屏）
 *   KEY_TYPE=10001  导航数据：ICON / ROUTE_REMAIN_DIS / SEG_REMAIN_DIS / NEXT_ROAD_NAME
 *
 * 写 HUD：1558=路名，561033318=剩余距离，1541=转向类型，1538=图标，1537=颜色（可选）。
 */
public class NavBridge extends BroadcastReceiver {

    public static final String ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";

    private static final int TYPE_STATE = 10019;
    private static final int TYPE_DATA = 10001;
    /** 导航数据可能几秒才来一条；太长会让音乐永远盖住导航，太短会在路口被音乐抢屏。 */
    private static final long NAV_HOLD_MS = 60_000;

    private final HudWriter writer;
    private final int color;

    private boolean inNavi;
    private int lastIcon = -1;
    private int dirAlt;
    private long lastDistAt;
    private long lastAt;

    /** 最近一帧导航数据，供 HudService 周期重写，压住原车媒体区的覆盖。 */
    private boolean hasFrame;
    private String lastRoad = "";
    private int lastDist;
    private int lastDir;
    private int lastIdx;
    private long lastRefreshLog;

    public NavBridge(HudWriter writer, int color) {
        this.writer = writer;
        this.color = color;
    }

    /** 导航状态为进行中，且最近仍收到过数据；60 秒无数据才交还给音乐。 */
    public boolean active() {
        return inNavi && System.currentTimeMillis() - lastAt < NAV_HOLD_MS;
    }

    /**
     * 仪表文本帧会被原车媒体区周期性覆盖（路名一闪就没）。
     * 导航期间每秒用最近一帧重写一次，保证导航内容一直在最上层。
     */
    public void refresh() {
        if (!inNavi || !hasFrame || !writer.ready()) {
            return;
        }
        writer.nav(lastRoad, lastDist, lastDir, lastIdx, color);
        writer.arrow(maneuverType(lastIcon), 0);
        long now = System.currentTimeMillis();
        if (now - lastRefreshLog > 5000) {
            lastRefreshLog = now;
            LogFile.i("HudFree", "nav refresh road=" + lastRoad + " dist=" + lastDist
                    + " ok=" + writer.ready());
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        int type = intent.getIntExtra("KEY_TYPE", -1);

        if (type == TYPE_STATE) {
            int state = intent.getIntExtra("EXTRA_STATE", -1);
            if (state == 9 || state == 2) {
                // 高德退出时会连发 9 和 2。第一次结束已经清屏并交还音乐，
                // 第二个结束事件不能再清一次，否则会把刚写回去的歌名清成空白。
                if (inNavi) {
                    inNavi = false;
                    clear();
                    LogFile.i("HudFree", "nav state end=" + state);
                } else {
                    LogFile.i("HudFree", "nav duplicate end ignored=" + state);
                }
            } else if (state == 8) {
                inNavi = true;
                LogFile.i("HudFree", "nav state start");
            }
            return;
        }

        if (type != TYPE_DATA) {
            return;
        }

        int icon = intent.getIntExtra("ICON", -1);
        int dist = intent.getIntExtra("SEG_REMAIN_DIS",
                intent.getIntExtra("ROUTE_REMAIN_DIS", 0));
        String road = intent.getStringExtra("NEXT_ROAD_NAME");

        // 高德有时只发 10001 数据，不发 10019/EXTRA_STATE=8。
        // 只要有有效 ICON + 路名，就认为导航已经开始，主动接管 HUD。
        if (!inNavi) {
            if (road == null || road.trim().isEmpty() || icon < 0) {
                return;
            }
            inNavi = true;
            LogFile.i("HudFree", "nav inferred from data icon=" + icon + " road=" + road);
        }

        if (road == null || road.trim().isEmpty()) {
            // 高德在路口/到达前会插一些没有 NEXT_ROAD_NAME 的帧。
            // 不能因为一帧没路名就清屏，否则仪表的路名会一闪就消失。
            LogFile.i("HudFree", "nav frame without road ignored");
            return;
        }

        long now = System.currentTimeMillis();
        boolean iconChanged = icon != lastIcon;
        boolean distDue = now - lastDistAt > 1000;
        if (!iconChanged && !distDue) {
            return;
        }

        if (!writer.ready()) {
            LogFile.w("HudFree", "nav skip: writer not ready");
            return;
        }

        int[] pair = map(icon);
        lastRoad = road.trim();
        lastDist = dist;
        lastDir = pair[0];
        lastIdx = pair[1];
        hasFrame = true;
        boolean ok = writer.nav(lastRoad, lastDist, lastDir, lastIdx, color);
        // CarPlay 的 1284 = [maneuverType, angle/22.5]，实车 mode 7 下能画箭头。
        writer.arrow(maneuverType(icon), 0);
        LogFile.i("HudFree", "nav icon=" + icon + " type=" + pair[0] + " idx=" + pair[1]
                + " road=" + road + " dist=" + dist + " ok=" + ok
                + " err=" + writer.lastError());

        lastIcon = icon;
        lastDistAt = now;
        lastAt = now;
    }

    private void clear() {
        if (writer.ready()) {
            writer.clear();
        }
        lastAt = System.currentTimeMillis();
    }

    /**
     * 高德 ICON → 车机 1284 的 maneuverType。
     * 实车验证：1=左转、2=右转（待逐个确认 3=直行、4=掉头）。
     * 高德码表：0 直行、1 左转、2 左前、3 右转、4 右前、5 掉头、
     * 6/7/13/15-21/23 直行类、8 右掉头、9/12 右前、10/11 左前、14 掉头。
     */
    private static int maneuverType(int icon) {
        switch (icon) {
            case 1:
            case 2:
            case 10:
            case 11:
                return 1;   // 左 / 左前
            case 3:
            case 4:
            case 9:
            case 12:
                return 2;   // 右 / 右前
            case 5:
            case 8:
            case 14:
                return 4;   // 掉头
            default:
                return 3;   // 直行
        }
    }

    /**
     * 高德 ICON → {字段 1541 转向类型, 字段 1538 图标序号}。
     *
     * 表从原版 sendToHud 的 switch 反推（入口 v2=9、v5=0、v8=1、v9=12、v0=11、v1=2、v4=6）：
     * 有的图标走 1541 类型码，有的走 1538 序号，两者互补（另一项为 0）。
     * 未列出的图标按原版默认分支处理：0/4 交替（双向车道方向）。
     *
     * 参考：原车百度地图 TBTUtil 的两张表，可佐证 1541 是角度量、1538 是图标序号。
     *   turnKindArrowAngle: 1→0 2→2 3→4 4→6 5→8 6→10 7→12 8→14（0..14，偶数，像 22.5° 步进）
     *   turnKindTranslate : 9→1 10→2 11/12→5 24→9 25..28→8 29→11 30→10 5→6
     */
    int[] map(int icon) {
        switch (icon) {
            case 2:
                return new int[]{12, 0};
            case 3:
                return new int[]{4, 0};
            case 4:
                return new int[]{14, 0};
            case 5:
                return new int[]{2, 0};
            case 6:
                return new int[]{9, 0};
            case 7:
                return new int[]{7, 0};
            case 8:
                return new int[]{0, 6};
            case 11:
                return new int[]{0, 1};
            case 12:
                return new int[]{0, 2};
            case 14:
                return new int[]{0, 10};
            case 15:
                return new int[]{0, 9};
            case 16:
                return new int[]{0, 11};
            case 17:
                return new int[]{0, 1};
            case 18:
                return new int[]{0, 2};
            case 65:
                return new int[]{15, 0};
            case 66:
                return new int[]{2, 0};
            default:
                dirAlt ^= 1;
                return new int[]{0, dirAlt * 4};
        }
    }
}
