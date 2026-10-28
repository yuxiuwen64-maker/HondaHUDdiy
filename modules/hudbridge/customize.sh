#!/system/bin/sh
# Copyright (C) 2026 yuxiuwen64-maker
# SPDX-License-Identifier: AGPL-3.0-only
# 不依赖 Magisk 的自动解压：自己解整包
SKIPUNZIP=1
ui_print "- 释放 HUD 应用"

unzip -o "$ZIPFILE" -d "$MODPATH" >&2

[ -d "$MODPATH" ] && set_perm_recursive "$MODPATH" 0 0 0755 0644
[ -f "$MODPATH/service.sh" ] && set_perm "$MODPATH/service.sh" 0 0 0755
