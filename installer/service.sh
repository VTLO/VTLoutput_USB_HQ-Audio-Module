#!/system/bin/sh
# After boot: record what the system actually ended up with (useful if a prop was refused).
BASE="${VTLO_BASE:-/data/adb/vtlo_usbaudio}"
LOG="$BASE/log.txt"
i=0
until [ "$(getprop sys.boot_completed)" = 1 ] || [ "$i" -ge 60 ]; do sleep 5; i=$((i+1)); done
for k in ro.audio.resampler.psd.enable_at_samplerate ro.audio.resampler.psd.stopband \
         ro.audio.resampler.psd.halflength ro.audio.resampler.psd.tbwcheat \
         af.resampler.quality vendor.audio.adm.buffering.ms; do
  v="$(getprop "$k")"
  echo "$(date '+%F %T') boot: $k=${v:-<unset>}" >> "$LOG"
done

# Deferred / missing companion app install (once, after boot)
APP="${MODDIR:-${0%/*}}/app/VTLoutput_HQ.apk"
if [ -f "$APP" ] && { [ -f "$BASE/pending_app_install" ] || ! pm path fr.vtlo.usbaudiohq >/dev/null 2>&1; }; then
  if [ -f "$BASE/pending_app_install" ] || [ ! -f "$BASE/app_install_tried" ]; then
    : > "$BASE/app_install_tried"
    if sh "${APP%/app/*}/install-app.sh" "$APP" >> "$LOG" 2>&1; then rm -f "$BASE/pending_app_install"; echo "$(date '+%F %T') app installed" >> "$LOG"; fi
  fi
fi
