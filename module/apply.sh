#!/system/bin/sh
# Shared by post-fs-data.sh (boot) and the app / `usbaudio-apply` (live, no reboot).
# Reads $BASE/config and pushes the resampler properties. Properties only.
BASE="${VTLO_BASE:-/data/adb/vtlo_usbaudio}"
LOG="$BASE/log.txt"

PROFILE=hq
AF_QUALITY=7
ADM_BUFFERING_MS=
CUSTOM_SB=179
CUSTOM_HL=408
CUSTOM_TBW=99
ENABLE_AT=44100
[ -f "$BASE/config" ] && . "$BASE/config"

mkdir -p "$BASE" 2>/dev/null
[ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt 20000 ] && : > "$LOG"
log() { echo "$(date '+%F %T') $*" >> "$LOG"; }
set_prop() {
  if resetprop -n "$1" "$2" 2>>"$LOG"; then log "set $1=$2"; else log "FAILED $1"; fi
}
# isint VALUE MIN MAX
isint() {
  case "$1" in ''|*[!0-9]*) return 1 ;; esac
  [ "$1" -ge "$2" ] && [ "$1" -le "$3" ]
}

log "apply: profile=$PROFILE"
SB=""
case "$PROFILE" in
  hq)    SB=179; HL=408; TBW=99 ;;
  ultra) SB=194; HL=520; TBW=98 ;;
  custom)
    if isint "$CUSTOM_SB" 60 220 && isint "$CUSTOM_HL" 16 800 && isint "$CUSTOM_TBW" 50 150; then
      SB=$CUSTOM_SB; HL=$CUSTOM_HL; TBW=$CUSTOM_TBW
    else
      log "custom values out of range (SB 60-220, HL 16-800, TBW 50-150) - nothing applied"
    fi ;;
esac

if [ -n "$SB" ]; then
  isint "$ENABLE_AT" 0 192000 || ENABLE_AT=44100
  # enable gate: applies to every resampler whose sink rate >= ENABLE_AT
  set_prop ro.audio.resampler.psd.enable_at_samplerate "$ENABLE_AT"
  set_prop ro.audio.resampler.psd.stopband "$SB"
  set_prop ro.audio.resampler.psd.halflength "$HL"
  set_prop ro.audio.resampler.psd.tbwcheat "$TBW"
  isint "$AF_QUALITY" 0 7 && set_prop af.resampler.quality "$AF_QUALITY"
fi

isint "$ADM_BUFFERING_MS" 0 100 && set_prop vendor.audio.adm.buffering.ms "$ADM_BUFFERING_MS"
exit 0
