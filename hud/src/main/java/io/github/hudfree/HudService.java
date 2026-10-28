/*
 * Copyright (C) 2026 yuxiuwen64-maker
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.hudfree;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.IBinder;

/**
 * 前台服务。
 *
 * 有导航时：HUD 显示高德的转向图标 / 路名 / 剩余距离（NavBridge 直接写）。
 * 没导航时：HUD 显示当前播放的歌名与歌手。
 *
 * 歌词相关实现保留但未接线。
 */
public class HudService extends Service {

    private static final String TAG = "HudFree";
    private static final String CHANNEL = "hud";
    private static final String METER_PKG = "com.alap.honda30ea.aochr.meterservice";
    private static final String METER_ACTION = "com.alap.honda30ea.aochr.meterservice.MeterService";

    private final HudWriter writer = new HudWriter();
    private final MeterClient meter = new MeterClient();

    private NavBridge nav;
    private boolean navRegistered;

    private volatile boolean running;
    private Thread worker;
    private volatile boolean bound;
    private volatile boolean meterOpened;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            bound = true;
            writer.attach(service);
            LogFile.i(TAG, "meter connected descriptor=" + writer.descriptor()
                    + " alive=" + writer.ready());
            meterOpened = meter.open(HudService.this, Prefs.propIds(HudService.this));
            LogFile.i(TAG, "meter client opened=" + meterOpened
                    + (meterOpened ? "" : " err=" + meter.lastError));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            writer.attach(null);
            meter.close();
            meterOpened = false;
            LogFile.w(TAG, "meter disconnected");
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        LogFile.init(this);
        LogFile.i(TAG, "service created");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(
                    new NotificationChannel(CHANNEL, "HUD", NotificationManager.IMPORTANCE_MIN));
        }
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("HUDdiy")
                .setContentText("运行中")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
        startForeground(1, n);

        bindMeter();

        if (Prefs.amapNavi(this)) {
            nav = new NavBridge(writer, Prefs.arrowColor(this));
            try {
                registerReceiver(nav, new IntentFilter(NavBridge.ACTION));
                navRegistered = true;
                LogFile.i(TAG, "nav receiver registered");
            } catch (Throwable t) {
                LogFile.w(TAG, "register nav: " + t);
            }
        }
    }

    private void bindMeter() {
        if (bound) {
            return;
        }
        try {
            // 与原版一致：只给 package + action，让系统按 intent-filter 解析。
            // 服务真实类名是 ...meterservice.service.MeterService，不能写死 ComponentName。
            Intent i = new Intent(METER_ACTION);
            i.setPackage(METER_PKG);
            bound = bindService(i, conn, Context.BIND_AUTO_CREATE);
            LogFile.i(TAG, "bind meter -> " + bound);
        } catch (Throwable t) {
            bound = false;
            LogFile.w(TAG, "bind meter failed: " + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startLoop();
        return START_STICKY;
    }

    private synchronized void startLoop() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "hud-loop");
        worker.start();
    }

    private void loop() {
        String lastTitle = null;
        String lastArtist = null;
        boolean lastPlaying = false;
        boolean navWasActive = false;

        while (running) {
            try {
                if (!writer.ready() && !bound) {
                    bindMeter();
                }

                boolean navNow = nav != null && nav.active();

                if (navNow) {
                    if (!navWasActive) {
                        // 从音乐模式切到导航时先清一次，并清掉媒体区歌曲；
                        // 最后再写导航帧，保证仪表只显示导航。
                        writer.clear();
                        sleep(200);
                        writer.hideMusic();
                        if (nav != null) {
                            nav.refresh();
                        }
                        LogFile.i(TAG, "nav start, music hidden");
                    }
                    navWasActive = true;
                    // 每秒重写一次最近导航帧，防止原车媒体区把路名盖掉。
                    if (nav != null) {
                        nav.refresh();
                    }
                } else {
                    if (navWasActive) {
                        // 导航刚结束：按原版清屏，再把显示切回音乐。
                        if (writer.ready()) {
                            writer.clear();
                        }
                        navWasActive = false;
                        lastTitle = null;
                        lastArtist = null;
                    }

                    NowPlaying np = NowPlaying.read(this);
                    String title = np.title;
                    String artist = np.artist;
                    boolean textChanged = !title.equals(lastTitle)
                            || !artist.equals(lastArtist);
                    boolean changed = textChanged || np.playing != lastPlaying;
                    if (changed) {
                        LogFile.i(TAG, "music title=[" + title + "] artist=[" + artist
                                + "] playing=" + np.playing + " writer=" + writer.ready());
                        if (writer.ready()) {
                            if (title.isEmpty() && artist.isEmpty()) {
                                writer.clear();
                            } else {
                                // 仪表的文本帧是锁存的：同一模式里只改 1558 不会刷新。
                                // 启动时/每次换歌都先清一次，再写新歌名。
                                if (textChanged) {
                                    writer.clear();
                                    sleep(500);
                                }
                                // 实车验证：1558 通道能显示，车机音频屏字段 561036833
                                // 在本地音乐源下不出现。这里沿用原版 sendLyric 的写法。
                                writer.musicLegacy(np.line());
                            }
                            LogFile.i(TAG, "music write count=" + writer.writeCount()
                                    + " lastError=" + writer.lastError());
                            // 只有真的写过才记状态；绑定晚于第一帧时下一轮会重试。
                            lastTitle = title;
                            lastArtist = artist;
                            lastPlaying = np.playing;
                        }
                    }
                }
            } catch (Throwable t) {
                LogFile.w(TAG, "loop: " + t);
            }
            sleep(1000);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        if (navRegistered && nav != null) {
            try {
                unregisterReceiver(nav);
            } catch (Throwable ignored) {
            }
            navRegistered = false;
        }
        if (bound) {
            try {
                unbindService(conn);
            } catch (Throwable ignored) {
            }
            bound = false;
        }
        meter.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
