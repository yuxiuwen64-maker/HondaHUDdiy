#!/system/bin/sh
# Copyright (C) 2026 yuxiuwen64-maker
# SPDX-License-Identifier: AGPL-3.0-or-later
SKIPMOUNT=false
LATESTARTSERVICE=true
POSTFSDATA=false
PROPFILE=false
print_modname() {
  ui_print "*******************************"
  ui_print "  hondahuddiy"
  ui_print "*******************************"
}
on_install() {
  unzip -o "$ZIPFILE" -d "$MODPATH" >&2
}
set_permissions() {
  [ -d "$MODPATH" ] && set_perm_recursive "$MODPATH" 0 0 0755 0644
  [ -f "$MODPATH/service.sh" ] && set_perm "$MODPATH/service.sh" 0 0 0755
}
