SKIPUNZIP=0
BASE=/data/adb/vtlo_usbaudio
api="${API:-$(getprop ro.build.version.sdk)}"

ui_print "- USB Audio HQ (VTLO) + VTLoutput_HQ app v1.6"
[ "$api" -ge 31 ] 2>/dev/null || abort "! Android 12+ required (the resampler tuning needs it)"
ui_print "- Android SDK $api"

mkdir -p "$BASE"
if [ ! -f "$BASE/config" ]; then
  cat > "$BASE/config" <<'EOF2'
# USB Audio HQ — edit by hand or use the companion app; then reboot (or "Apply now" in the app).
# PROFILE: hq     = stopband 179 dB, half-length 408, tbw 99   (default, fine on Tensor G3)
#          ultra  = stopband 194 dB, half-length 520, tbw 98   (heavier CPU)
#          custom = CUSTOM_SB / CUSTOM_HL / CUSTOM_TBW below
#          off    = touch nothing
PROFILE=hq
CUSTOM_SB=179
CUSTOM_HL=408
CUSTOM_TBW=99
# resamplers whose output rate is >= this (Hz) get the filter
ENABLE_AT=44100
# af.resampler.quality fallback (0-7, 7 = highest dynamic sinc; read at audioserver start)
AF_QUALITY=7
# Tensor audio-HAL buffering in ms (lower = less jitter, risk of glitches). Empty = untouched.
ADM_BUFFERING_MS=
EOF2
  ui_print "- config created: $BASE/config"
else
  ui_print "- keeping existing config: $BASE/config"
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/bin/usbaudio-status" 0 0 0755
set_perm "$MODPATH/system/bin/usbaudio-apply" 0 0 0755
set_perm "$MODPATH/apply.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/install-app.sh" 0 0 0755

# --- companion app (installed now; retried at boot if it fails, e.g. package manager not ready)
APP="$MODPATH/app/VTLoutput_HQ.apk"
if [ -f "$APP" ]; then
  ui_print "- Installing VTLoutput_HQ app..."
  if res="$(sh "$MODPATH/install-app.sh" "$APP")"; then
    ui_print "  app installed"
    rm -f "$BASE/pending_app_install"
  else
    ui_print "  app install deferred to first boot ($res)"
    : > "$BASE/pending_app_install"
  fi
fi

ui_print "- Open VTLoutput_HQ after the reboot. Then play something in Deezer, then run in Termux:"
ui_print "    su -c usbaudio-status"
