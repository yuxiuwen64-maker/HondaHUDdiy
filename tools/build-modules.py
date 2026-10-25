#!/usr/bin/env python3
"""把 modules/ 下的 Magisk 模块打包到 release/。

hudbridge 需要模块根目录里有 HudBridge.apk；如果源码目录没有，
自动从 release/hud-free.apk 复制一份，打完包再删掉。
"""

import os
import shutil
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODULES = os.path.join(ROOT, "modules")
RELEASE = os.path.join(ROOT, "release")
HUD_APK = os.path.join(
    MODULES, "hudbridge", "system", "product", "priv-app", "HudBridge", "HudBridge.apk"
)
SOURCE_APK = os.path.join(RELEASE, "hud-free.apk")

os.makedirs(RELEASE, exist_ok=True)

for name in sorted(os.listdir(MODULES)):
    src = os.path.join(MODULES, name)
    if not os.path.isdir(src):
        continue
    created_apk = False
    if name == "hudbridge" and not os.path.exists(HUD_APK):
        os.makedirs(os.path.dirname(HUD_APK), exist_ok=True)
        if not os.path.exists(SOURCE_APK):
            raise SystemExit("缺少 release/hud-free.apk，先构建 HUD APK")
        shutil.copyfile(SOURCE_APK, HUD_APK)
        created_apk = True

    dst = os.path.join(RELEASE, name + ".zip")
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
        for dp, _dn, fn in os.walk(src):
            for f in sorted(fn):
                p = os.path.join(dp, f)
                arc = os.path.relpath(p, src).replace(os.sep, "/")
                z.write(p, arc)
    print("%-12s %8d bytes" % (name + ".zip", os.path.getsize(dst)))

    if created_apk:
        os.remove(HUD_APK)
