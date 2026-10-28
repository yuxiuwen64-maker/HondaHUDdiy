/*
 * Copyright (C) 2026 yuxiuwen64-maker
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.hudfree;

import android.os.IBinder;
import android.os.Parcel;

import java.util.List;

/**
 * 写入车机 HUD 显示区。
 *
 * 协议来自车机自带服务 com.alpine.servicebinder.IMeterServiceManager，
 * 通过裸 transact 调用：
 *
 *   1 = sendByteToMS(int field, byte value)
 *   2 = sendByteArrayToMS(int field, byte[] value)
 *   3 = sendStringToMS(int field, String value)
 *   4 = sendBitmapToMS(int field, Bitmap value)
 *   5 = sendTBTInfoToMS(List&lt;String&gt; json)
 *   6 = sendMapInMeterState(int state)
 *
 * 导航走 CarPlay map-in-meter（mode 7），箭头用 1284 字节数组；
 * 音乐走 CarLife 文本模式（mode 9），用 1558 显示一行文本。
 * 两条通道都在 2026-10-25 的实车 TCC8050_AVN 上验证过。
 */
public final class HudWriter {

    private static final String DESCRIPTOR = "com.alpine.servicebinder.IMeterServiceManager";

    public static final int TX_BYTE = 1;
    public static final int TX_BYTE_ARRAY = 2;
    public static final int TX_STRING = 3;
    public static final int TX_BITMAP = 4;
    public static final int TX_TBT = 5;
    public static final int TX_MODE = 6;

    /* ---------------- 导航字段（原版高德桥接） ---------------- */
    /** MS_GUIDE_POINT_NAME_INFO，导航引导点/路名。 */
    public static final int F_GUIDE_NAME = 1558;
    /** 导航剩余距离，十进制字符串。 */
    public static final int F_GUIDE_DIST = 561033318;
    /** CarPlay 当前道路名；长距离时用它在仪表上保持路名。 */
    public static final int F_GUIDE_CURRENT = 561036824;
    /** HUD 显示状态：写 byte 0 + string "1" 让仪表退出导航显示。 */
    public static final int F_STATE = 561033300;
    /** 转向箭头角度/类型，0-15。 */
    public static final int F_DIR = 1541;
    /** 转向图标序号。 */
    public static final int F_ICON = 1538;
    /** 箭头颜色，0 表示车机默认。 */
    public static final int F_COLOR = 1537;

    /**
     * map-in-meter 状态：7=CarPlay 进入（tbtApp=2，支持 1284 箭头），
     * 9=CarLife 进入（tbtApp=0，只验证了文本/距离）。
     */
    public static final int MODE_NAV = 7;
    /** 纯文本/音乐用 CarLife 进入（tbtApp=0），实车验证能显示 1558。 */
    public static final int MODE_MUSIC = 9;
    /** 原版清屏时用的 state。 */
    public static final int MODE_CLEAR = 12;

    private volatile IBinder binder;
    private volatile String lastError = "";
    private volatile String descriptor = "";
    private volatile int writeCount;
    private volatile long lastWriteAt;

    public synchronized void attach(IBinder b) {
        binder = b;
        descriptor = "";
        if (b == null) {
            return;
        }
        try {
            if (b.isBinderAlive()) {
                descriptor = b.getInterfaceDescriptor();
            }
        } catch (Throwable t) {
            descriptor = "? " + t.getClass().getSimpleName();
        }
    }

    public boolean ready() {
        IBinder b = binder;
        if (b == null) {
            return false;
        }
        try {
            return b.isBinderAlive() && b.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public String descriptor() {
        return descriptor;
    }

    public String lastError() {
        return lastError;
    }

    public int writeCount() {
        return writeCount;
    }

    public long lastWriteAt() {
        return lastWriteAt;
    }

    /** 进入 HUD 显示模式（原版在高德每次更新前都会发）。 */
    public boolean enter() {
        return mode(MODE_NAV);
    }

    /**
     * 清屏。字段和顺序照抄原版 sendClearHud：
     * mode 12 -> 561033300 byte 0 -> 561033300 "1" -> 1558 " " ->
     * 561033318 "0" -> 1541 0 -> 1538 7。
     */
    public void clear() {
        mode(MODE_CLEAR);
        setByte(F_STATE, 0);
        setString(F_STATE, "1");
        setString(F_GUIDE_NAME, " ");
        setString(F_GUIDE_DIST, "0");
        setByte(F_DIR, 0);
        setByte(F_ICON, 7);
    }

    /** 写一次完整的导航显示。 */
    public boolean nav(String road, int distance, int dir, int icon, int color) {
        boolean ok = enter();
        String r = road == null ? "" : road;
        ok &= setString(F_GUIDE_NAME, r);
        // 1558 在剩余距离 >1km 时原车仪表不显示；561036824 是当前道路字段，
        // 长距离时用它兜底，让仪表一直有路名。
        ok &= setString(F_GUIDE_CURRENT, r);
        ok &= setString(F_GUIDE_DIST, String.valueOf(distance));
        ok &= setByte(F_DIR, dir);
        ok &= setByte(F_ICON, icon);
        if (color != 0) {
            ok &= setByte(F_COLOR, color);
        }
        return ok;
    }

    /**
     * 导航时清掉车机媒体区的歌名/歌手，避免仪表同时显示导航和歌曲。
     * 这几个是 CarPlay MeterAdapter 使用的音频字段；即使本地音乐不走它们，
     * 清零也能让车机音频区回到无内容状态。
     */
    public boolean hideMusic() {
        boolean ok = setByte(561033607, 0);
        ok &= setString(561036833, "");
        ok &= setByte(561033604, 0);
        ok &= setString(561036822, "");
        ok &= setByte(561033602, 0);
        return ok;
    }

    /**
     * 用导航/字符串通道显示一行音乐文本（原版 sendLyric 的做法）：
     * mode 9 -> 1558 文本 -> 561033318 "0" -> 1541 0 -> 1538 0。
     * 车机音频屏字段（561036833/561036822）在本地音乐源下不一定被接受，
     * 这条通道已经在实车 HUD TEST 中验证能显示。
     */
    public boolean musicLegacy(String text) {
        boolean ok = mode(MODE_MUSIC);
        ok &= setString(F_GUIDE_NAME, text == null ? "" : text);
        ok &= setString(F_GUIDE_DIST, "0");
        ok &= setByte(F_DIR, 0);
        // 7 = 清空图标；0 会留下一个直行箭头。
        ok &= setByte(F_ICON, 7);
        return ok;
    }

    public boolean setString(int field, String value) {
        return transactString(field, value);
    }

    public boolean setByte(int field, int value) {
        return transactByte(field, value);
    }

    /**
     * 车机 CarPlay 适配层真正用来画转向箭头的字段：
     * 1284 = [maneuverType, angle/22.5]。
     * 原版高德桥接写的 1541/1538 在实车上只出文本/距离，不出箭头。
     */
    public boolean arrow(int maneuverType, int angle) {
        return setBytes(1284, new byte[]{(byte) maneuverType, (byte) angle});
    }

    public boolean setBytes(int field, byte[] value) {
        IBinder b = binder;
        if (b == null || value == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(field);
            data.writeByteArray(value);
            boolean ok = b.transact(TX_BYTE_ARRAY, data, reply, 0);
            reply.readException();
            noteWrite(ok, field, "bytes[" + value.length + "]");
            return ok;
        } catch (Throwable t) {
            fail(field, "bytes", t);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * 发送一条 TBT JSON。车机 MeterServiceImpl 会把 List.get(0).toString()
     * 交给 RoadInformation.sendBaiDuTbTInfo(String)。保留给后续实验。
     */
    public boolean sendTbt(List<String> lines) {
        IBinder b = binder;
        if (b == null || lines == null || lines.isEmpty()) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeStringList(lines);
            boolean ok = b.transact(TX_TBT, data, reply, 0);
            reply.readException();
            noteWrite(ok, 0, "tbt[" + lines.size() + "]");
            return ok;
        } catch (Throwable t) {
            fail(0, "tbt", t);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** 直接发 map-in-meter state，调试用。 */
    public boolean mode(int state) {
        IBinder b = binder;
        if (b == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(state);
            boolean ok = b.transact(TX_MODE, data, reply, 0);
            reply.readException();
            noteWrite(ok, 0, "mode " + state);
            return ok;
        } catch (Throwable t) {
            fail(-1, "mode " + state, t);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean transactString(int field, String value) {
        IBinder b = binder;
        if (b == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(field);
            data.writeString(value);
            boolean ok = b.transact(TX_STRING, data, reply, 0);
            reply.readException();
            noteWrite(ok, field, "str");
            return ok;
        } catch (Throwable t) {
            fail(field, "str", t);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean transactByte(int field, int value) {
        IBinder b = binder;
        if (b == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(field);
            data.writeByte((byte) value);
            boolean ok = b.transact(TX_BYTE, data, reply, 0);
            reply.readException();
            noteWrite(ok, field, "byte " + value);
            return ok;
        } catch (Throwable t) {
            fail(field, "byte " + value, t);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void noteWrite(boolean ok, int field, String kind) {
        writeCount++;
        lastWriteAt = System.currentTimeMillis();
        if (!ok) {
            lastError = "transact=false field=" + field + " " + kind;
            LogFile.w("HudWrite", lastError);
        }
    }

    private void fail(int field, String kind, Throwable t) {
        lastError = "field=" + field + " " + kind + " -> "
                + t.getClass().getSimpleName() + ": " + t.getMessage();
        LogFile.w("HudWrite", lastError);
    }
}
