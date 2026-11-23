#!/system/bin/sh
# Magisk "Action" button: show the live USB audio status.
case "$0" in */*) MODDIR="${0%/*}" ;; *) MODDIR=. ;; esac
sh "$MODDIR/system/bin/usbaudio-status"
