package io.github.hudfree;

import android.content.Context;

import java.lang.reflect.Method;

/**
 * 读车机续航/里程。
 *
 * 车机把续航放在 android.car 的厂商私有属性里，属性 ID 因车型而异，
 * 所以这里用反射 + 可配置 ID 列表，避免编译期依赖 android.car。
 */
public final class MeterClient {

    private Object car;
    private Object propertyManager;
    private int[] ids = new int[0];

    /** 最近一次失败的异常摘要，给诊断页用 */
    public String lastError = "";

    public boolean open(Context ctx, String csvIds) {
        ids = parseIds(csvIds);
        try {
            Class<?> carCls = Class.forName("android.car.Car");
            Method createCar = carCls.getMethod("createCar", Context.class);
            car = createCar.invoke(null, ctx.getApplicationContext());

            Method getManager = carCls.getMethod("getCarManager", String.class);
            propertyManager = getManager.invoke(car, "property");
            if (propertyManager == null) {
                lastError = "getCarManager(property) 返回 null";
            }
            return propertyManager != null;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            close();
            return false;
        }
    }

    public boolean ready() {
        return propertyManager != null;
    }

    /** 依次尝试配置的属性 ID，返回第一个读到的数值；全部失败返回 -1。 */
    public float read() {
        if (propertyManager == null) {
            return -1;
        }
        for (int id : ids) {
            float v = readOne(id);
            if (v != Float.MIN_VALUE) {
                return v;
            }
        }
        return -1;
    }

    /** 读单个属性 ID；不存在或无返回时返回 Float.MIN_VALUE。 */
    public float readOne(int id) {
        if (propertyManager == null) {
            return Float.MIN_VALUE;
        }
        try {
            Method getProperty = propertyManager.getClass()
                    .getMethod("getProperty", int.class, int.class);
            Object value = getProperty.invoke(propertyManager, id, 0);
            if (value == null) {
                return Float.MIN_VALUE;
            }
            Object raw = value.getClass().getMethod("getValue").invoke(value);
            if (raw instanceof Integer) {
                return ((Integer) raw).floatValue();
            }
            if (raw instanceof Float) {
                return (Float) raw;
            }
            if (raw instanceof Double) {
                return ((Double) raw).floatValue();
            }
        } catch (Throwable t) {
            Throwable c = t;
            while (c.getCause() != null) {
                c = c.getCause();
            }
            lastError = c.getClass().getSimpleName() + ": " + c.getMessage();
        }
        return Float.MIN_VALUE;
    }

    public void close() {
        propertyManager = null;
        if (car != null) {
            try {
                car.getClass().getMethod("disconnect").invoke(car);
            } catch (Throwable ignored) {
            }
            car = null;
        }
    }

    /**
     * 扫描车机声明的全部属性 ID，返回「有值」的那些。
     * 属性清单由模块开机时导出到 /data/local/tmp/car_props.txt。
     */
    public java.util.List<String> scan(java.io.File f, int limit) {
        java.util.List<String> hits = new java.util.ArrayList<String>();
        if (f == null || !f.exists()) {
            lastError = "属性清单不存在：" + f;
            return hits;
        }
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader(f));
            String line;
            int tried = 0;
            while ((line = r.readLine()) != null && tried < limit) {
                line = line.trim();
                if (!line.startsWith("0x")) {
                    continue;
                }
                int id;
                try {
                    id = (int) Long.parseLong(line.substring(2), 16);
                } catch (Throwable t) {
                    continue;
                }
                tried++;
                float v = readOne(id);
                if (v == Float.MIN_VALUE) {
                    continue;
                }
                if (v == 0f || v == 65535f || v == -1f) {
                    continue;
                }
                hits.add(String.format("%s = %.1f", line, v));
            }
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return hits;
    }

    private static int[] parseIds(String csv) {        if (csv == null || csv.trim().isEmpty()) {
            return new int[0];
        }
        String[] parts = csv.split(",");
        int[] out = new int[parts.length];
        int n = 0;
        for (String p : parts) {
            try {
                out[n++] = Integer.parseInt(p.trim());
            } catch (Throwable ignored) {
            }
        }
        int[] trimmed = new int[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }
}
