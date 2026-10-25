package io.github.hudfree;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

/** 配置界面：开关、属性 ID、通知访问授权。 */
public class MainActivity extends Activity {

    private Switch swEnable;
    private EditText etProps;
    private EditText etField;
    private EditText etValue;
    private TextView tvStatus;

    private final HudWriter mw = new HudWriter();

    private final ServiceConnection mConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mw.attach(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mw.attach(null);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LogFile.init(this);

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("HUDdiy");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        root.addView(title, lp());

        TextView hint = new TextView(this);
        hint.setText("无校验 · 无联网 · 无自毁\n"
                + "HUD 显示当前播放的歌名与歌手（来自媒体会话）\n"
                + "日志：/data/data/io.github.hudfree/files/hud.log");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setTextColor(Color.GRAY);
        root.addView(hint, lp());

        swEnable = new Switch(this);
        swEnable.setText("开机自动运行");
        swEnable.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        root.addView(swEnable, lp());

        etProps = new EditText(this);
        etProps.setHint("续航属性 ID，逗号分隔");
        etProps.setInputType(InputType.TYPE_CLASS_TEXT);
        etProps.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        root.addView(etProps, lp());

        Button save = new Button(this);
        save.setText("保存并启动");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndStart();
            }
        });
        root.addView(save, lp());

        Button notifyAccess = new Button(this);
        notifyAccess.setText("授权通知访问（读歌名需要）");
        notifyAccess.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                } catch (Throwable ignored) {
                }
            }
        });
        root.addView(notifyAccess, lp());

        Button probe = new Button(this);
        probe.setText("探测续航 ID");
        probe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                probeIds();
            }
        });
        root.addView(probe, lp());

        Button diag = new Button(this);
        diag.setText("诊断");
        diag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                diagnose();
            }
        });
        root.addView(diag, lp());

        Button scan = new Button(this);
        scan.setText("扫描属性（找续航）");
        scan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanProps();
            }
        });
        root.addView(scan, lp());

        // ---- 手动写字段（现场调试用）----
        etField = new EditText(this);
        etField.setHint("字段 ID，如 1558");
        etField.setInputType(InputType.TYPE_CLASS_NUMBER);
        etField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        root.addView(etField, lp());

        etValue = new EditText(this);
        etValue.setHint("值（字符串或 0-255）");
        etValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        root.addView(etValue, lp());

        Button wStr = new Button(this);
        wStr.setText("写字符串");
        wStr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manualWrite(true);
            }
        });
        root.addView(wStr, lp());

        Button wByte = new Button(this);
        wByte.setText("写字节");
        wByte.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manualWrite(false);
            }
        });
        root.addView(wByte, lp());

        Button testNav = new Button(this);
        testNav.setText("自检：导航字段");
        testNav.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selfTest(false);
            }
        });
        root.addView(testNav, lp());

        Button testMusic = new Button(this);
        testMusic.setText("自检：音乐字段");
        testMusic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selfTest(true);
            }
        });
        root.addView(testMusic, lp());

        tvStatus = new TextView(this);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvStatus.setTextColor(Color.DKGRAY);
        root.addView(tvStatus, lp());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        swEnable.setChecked(Prefs.enabled(this));
        etProps.setText(Prefs.propIds(this));
        tvStatus.setText("未启动");

        try {
            Intent i = new Intent("com.alap.honda30ea.aochr.meterservice.MeterService");
            i.setPackage("com.alap.honda30ea.aochr.meterservice");
            bindService(i, mConn, Context.BIND_AUTO_CREATE);
        } catch (Throwable ignored) {
        }
    }

    /** 手动往 HUD 写一个字段，现场试值用。 */
    private void manualWrite(final boolean asString) {
        int field;
        try {
            field = Integer.parseInt(etField.getText().toString().trim());
        } catch (Throwable t) {
            tvStatus.setText("字段 ID 不合法");
            return;
        }
        final int f = field;
        final String raw = etValue.getText().toString();

        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok;
                if (asString) {
                    ok = mw.setString(f, raw);
                } else {
                    int b = 0;
                    try {
                        b = Integer.parseInt(raw.trim());
                    } catch (Throwable ignored) {
                    }
                    ok = mw.setByte(f, b);
                }
                final String msg = "字段 " + f + " / " + raw + " → "
                        + (ok ? "已发送" : "失败") + "（ready=" + mw.ready()
                        + " desc=" + mw.descriptor() + " err=" + mw.lastError() + "）";
                LogFile.i("manual", msg);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvStatus.setText(msg);
                    }
                });
            }
        }, "manual-write").start();
    }

    /**
     * 自检：直接把一组固定数据写到 HUD，用来区分「协议没通」和「数据源没来」。
     * 导航自检会先发 mode 9，再写 1558/561033318/1541/1538；
     * 音乐自检使用车机 CarPlay 适配层的 561036833/561036822 及其有效标志。
     */
    private void selfTest(final boolean music) {
        tvStatus.setText(music ? "发送音乐自检…" : "发送导航自检…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append("ready=").append(mw.ready())
                        .append(" desc=").append(mw.descriptor()).append('\n');
                if (music) {
                    // 先退出导航/字符串模式，再走已验证能显示的 1558 通道。
                    mw.clear();
                    sleep(600);
                    boolean a = mw.musicLegacy("MUSIC TEST");
                    sb.append("clear=ok legacy=").append(a).append('\n');
                } else {
                    boolean m = mw.mode(HudWriter.MODE_NAV);
                    boolean a = mw.setString(HudWriter.F_GUIDE_NAME, "HUD TEST");
                    boolean b = mw.setString(HudWriter.F_GUIDE_DIST, "123");
                    boolean c = mw.setByte(HudWriter.F_DIR, 1);
                    boolean d = mw.setByte(HudWriter.F_ICON, 1);
                    boolean e = mw.setByte(HudWriter.F_COLOR, 1);
                    sb.append("mode9=").append(m).append(" name=").append(a)
                            .append(" dist=").append(b).append('\n');
                    sb.append("dir=").append(c).append(" icon=").append(d)
                            .append(" color=").append(e).append('\n');
                }
                sb.append("count=").append(mw.writeCount())
                        .append(" err=").append(mw.lastError());
                final String msg = sb.toString();
                LogFile.i("selftest", "\n" + msg);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvStatus.setText(msg);
                    }
                });
            }
        }, "selftest").start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void saveAndStart() {
        Prefs.setEnabled(this, swEnable.isChecked());
        Prefs.setPropIds(this, etProps.getText().toString().trim());

        Intent svc = new Intent(this, HudService.class);
        startForegroundService(svc);
        tvStatus.setText("已保存，服务已启动");
    }

    /** 逐个读候选属性 ID，把有返回值的列出来，方便在实车上确定用哪个。 */
    private void probeIds() {
        final String csv = etProps.getText().toString().trim();
        tvStatus.setText("探测中…");

        new Thread(new Runnable() {
            @Override
            public void run() {
                MeterClient m = new MeterClient();
                boolean ok = m.open(MainActivity.this, csv);
                StringBuilder sb = new StringBuilder();
                sb.append("CarPropertyManager：").append(ok ? "可用" : "不可用").append('\n');

                for (String part : csv.split(",")) {
                    String s = part.trim();
                    if (s.isEmpty()) {
                        continue;
                    }
                    try {
                        int id = Integer.parseInt(s);
                        float v = m.readOne(id);
                        if (v == Float.MIN_VALUE) {
                            sb.append(String.format("0x%08X  无返回", id)).append('\n');
                        } else {
                            sb.append(String.format("0x%08X  = %.1f", id, v)).append('\n');
                        }
                    } catch (Throwable t) {
                        sb.append(s).append("  解析失败").append('\n');
                    }
                }
                m.close();

                final String out = sb.toString();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvStatus.setText(out);
                    }
                });
            }
        }, "probe").start();
    }

    /** 扫描车机声明的全部属性，找出有值的那些。 */
    private void scanProps() {
        tvStatus.setText("扫描中…（可能十几秒）");
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                MeterClient m = new MeterClient();
                boolean ok = m.open(MainActivity.this, "");
                if (!ok) {
                    sb.append("Car 不可用：").append(m.lastError);
                } else {
                    java.util.List<String> hits =
                            m.scan(new java.io.File("/data/local/tmp/car_props.txt"), 400);
                    sb.append("有值属性 ").append(hits.size()).append(" 个\n");
                    int n = 0;
                    for (String h : hits) {
                        sb.append(h).append('\n');
                        if (++n >= 25) {
                            sb.append("…\n");
                            break;
                        }
                    }
                    if (hits.isEmpty() && m.lastError != null && !m.lastError.isEmpty()) {
                        sb.append(m.lastError).append('\n');
                    }
                    m.close();
                }
                final String out = sb.toString();
                LogFile.i("scan", "\n" + out);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvStatus.setText(out);
                    }
                });
            }
        }, "scan").start();
    }

    /** 一次性把安装位置、权限、服务解析、Car 属性全打出来。 */
    private void diagnose() {        tvStatus.setText("诊断中…");
        final String csv = etProps.getText().toString().trim();

        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();

                try {
                    PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                    sb.append("路径: ").append(pi.applicationInfo.sourceDir).append('\n');
                } catch (Throwable t) {
                    sb.append("包信息: ").append(t).append('\n');
                }
                sb.append("binder: ready=").append(mw.ready())
                        .append(" desc=").append(mw.descriptor())
                        .append(" writes=").append(mw.writeCount())
                        .append(" err=").append(mw.lastError()).append('\n');

                String[] perms = {
                        "android.car.permission.CAR_MILEAGE",
                        "android.car.permission.READ_VEHICLE_STATE",
                        "android.car.permission.CAR_VENDOR_EXTENSION"
                };
                for (String p : perms) {
                    int st;
                    try {
                        st = checkSelfPermission(p);
                    } catch (Throwable t) {
                        st = -999;
                    }
                    sb.append(p.replace("android.car.permission.", ""))
                            .append(": ").append(st == 0 ? "已授予" : "未授予(" + st + ")")
                            .append('\n');
                }

                try {
                    Intent i = new Intent("com.alap.honda30ea.aochr.meterservice.MeterService");
                    i.setPackage("com.alap.honda30ea.aochr.meterservice");
                    List<ResolveInfo> list = getPackageManager().queryIntentServices(i, 0);
                    sb.append("MeterService 解析: ").append(list.size()).append(" 个\n");
                    for (ResolveInfo ri : list) {
                        sb.append("  ").append(ri.serviceInfo.name).append('\n');
                    }
                } catch (Throwable t) {
                    sb.append("解析服务: ").append(t).append('\n');
                }

                MeterClient m = new MeterClient();
                boolean ok = m.open(MainActivity.this, csv);
                sb.append("Car: ").append(ok ? "ok" : "fail");
                if (!ok) {
                    sb.append(" (").append(m.lastError).append(')');
                }
                sb.append('\n');

                if (ok) {
                    for (String part : csv.split(",")) {
                        String s = part.trim();
                        if (s.isEmpty()) {
                            continue;
                        }
                        try {
                            int id = Integer.parseInt(s);
                            float v = m.readOne(id);
                            if (v == Float.MIN_VALUE) {
                                sb.append(String.format("0x%08X  -  %s", id, m.lastError)).append('\n');
                            } else {
                                sb.append(String.format("0x%08X  = %.1f", id, v)).append('\n');
                            }
                        } catch (Throwable t) {
                            sb.append(s).append(" 解析失败\n");
                        }
                    }
                    m.close();
                }

                final String out = sb.toString();
                LogFile.i("diag", "\n" + out);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvStatus.setText(out);
                    }
                });
            }
        }, "diag").start();
    }
}
