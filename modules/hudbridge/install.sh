#!/system/bin/sh
SKIPMOUNT=false
LATESTARTSERVICE=true
POSTFSDATA=false
PROPFILE=false
print_modname() {
  ui_print "*******************************"
  ui_print "  高德桥接 HUD（自由版）"
  ui_print "*******************************"
}
on_install() {
  unzip -o "$ZIPFILE" -d "$MODPATH" >&2
}
set_permissions() {
  [ -d "$MODPATH" ] && set_perm_recursive "$MODPATH" 0 0 0755 0644
  [ -f "$MODPATH/service.sh" ] && set_perm "$MODPATH/service.sh" 0 0 0755
}
