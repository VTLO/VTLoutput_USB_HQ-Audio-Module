#!/system/bin/sh
# Runs before audioserver starts. Properties only — nothing on /system or /vendor is modified,
# so it cannot bootloop.
case "$0" in */*) MODDIR="${0%/*}" ;; *) MODDIR=. ;; esac
. "$MODDIR/apply.sh"
