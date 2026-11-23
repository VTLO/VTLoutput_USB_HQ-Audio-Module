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
