#!/data/data/com.termux/files/usr/bin/bash
# usbaudio — all-in-one USB-C audio quality toolkit for Termux
#
#   usbaudio install            install dependencies + register `usbaudio` command
#   usbaudio doctor             report DAC capabilities, audio path, CPU cores, tools
#   usbaudio tune [vol_index]   wake-lock, battery whitelist, UI-sound off, music volume
#   usbaudio play <files|urls>  high-quality player (soxr resampling, float, EQ, core pinning)
#   usbaudio master [-o dir] [-r rate|keep] [--no-eq] <files|dirs>
#                               pre-render library to 24-bit FLAC at the DAC-friendly rate
#   usbaudio eq <file|url|off>  load an AutoEq "ParametricEQ.txt" headphone profile
#   usbaudio test               channel check, 997 Hz tone, 20 Hz–20 kHz sweep
#   usbaudio config             show / edit configuration
#
# Works without root. Root (su) or Shizuku (rish) unlocks: DAC stream info,
# audio-flinger dump, battery whitelist, real-time priority boost.

set -u

CFG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/usbaudio"
CFG="$CFG_DIR/config"
EQ_FILE="$CFG_DIR/eq.txt"

# ---------- defaults (override in $CFG) ----------
RATE=48000          # 48000 = Android mixer native rate (no 2nd resample). 'keep' = don't resample
FMT=float           # float | s32 | s16  (player output format)
AO=auto             # auto | aaudio | audiotrack | opensles | pulse
BUFFER=0.4          # seconds of audio buffer (bigger = fewer underruns)
REPLAYGAIN=album    # no | track | album
CROSSFEED=0         # 0 = off, else 0.1–0.6 (headphone crossfeed strength)
USE_EQ=1            # apply eq.txt if present
PIN=auto            # auto | none | "4,5,6"  (CPU cores for the player)
NICE=-10            # priority boost (needs root/Shizuku)
OUT_DIR="$HOME/storage/music/mastered"
JOBS=""             # parallel master jobs (default: nproc/2)

# shellcheck disable=SC1090
[ -f "$CFG" ] && . "$CFG"

# ---------- helpers ----------
say()  { printf '\033[1;36m▸\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m✔\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m✘\033[0m %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "missing '$1' — run: usbaudio install"; }
have() { command -v "$1" >/dev/null 2>&1; }

PRIV=""; PRIV_DONE=0
detect_priv() {
  [ "$PRIV_DONE" = 1 ] && return
  PRIV_DONE=1
  if have su && timeout 8 su -c id 2>/dev/null | grep -q 'uid=0'; then PRIV=su
  elif have rish && timeout 8 rish -c id 2>/dev/null | grep -q 'uid='; then PRIV=rish
  fi
}
priv() {
  detect_priv
  case "$PRIV" in
    su)   su -c "$*" ;;
    rish) rish -c "$*" ;;
    *)    return 127 ;;
  esac
}
rd() { cat "$1" 2>/dev/null || priv "cat $1" 2>/dev/null; }

SOXR=0; SOXR_DONE=0
detect_soxr() {
  [ "$SOXR_DONE" = 1 ] && return
  SOXR_DONE=1
  have ffmpeg || return
  ffmpeg -hide_banner -loglevel error -f lavfi -i anullsrc=r=44100 -t 0.05 \
    -af aresample=48000:resampler=soxr -f null - >/dev/null 2>&1 && SOXR=1
}

resampler_opts() {
  detect_soxr
  if [ "$SOXR" = 1 ]; then
    echo "resampler=soxr:precision=28:cutoff=0.99:dither_method=triangular_hp"
  else
    echo "filter_size=64:phase_shift=14:cutoff=0.98:filter_type=kaiser:dither_method=triangular_hp"
  fi
}

# Mid-cluster cores (neither the slowest nor the fastest): steady clocks, low jitter,
# keeps the prime core free for the UI.  Prints e.g. "4,5,6".
auto_cores() {
  local list="" c f lo hi
  for c in /sys/devices/system/cpu/cpu[0-9]*; do
    f=$(cat "$c/cpufreq/cpuinfo_max_freq" 2>/dev/null) || continue
    list+="${c##*/cpu} $f"$'\n'
  done
  [ -z "$list" ] && return 0
  lo=$(echo "$list" | awk 'NF{print $2}' | sort -n | head -1)
  hi=$(echo "$list" | awk 'NF{print $2}' | sort -n | tail -1)
  [ "$lo" = "$hi" ] && return 0
  echo "$list" | awk -v lo="$lo" -v hi="$hi" '$2>lo && $2<hi {printf "%s%s",(s?",":""),$1; s=1}'
}

# AutoEq ParametricEQ.txt  ->  ffmpeg filter chain
eq_to_filters() {
  awk '
    /^Preamp:/ { pre=$2; next }
    /^Filter/ && $3=="ON" {
      t=$4; fc=0; g=0; q=0.707
      for(i=5;i<=NF;i++){ if($i=="Fc")fc=$(i+1); if($i=="Gain")g=$(i+1); if($i=="Q")q=$(i+1) }
      if(t=="PK")                  f=sprintf("equalizer=f=%s:t=q:w=%s:g=%s",fc,q,g)
      else if(t=="LSC"||t=="LS")   f=sprintf("lowshelf=f=%s:t=q:w=%s:g=%s",fc,q,g)
      else if(t=="HSC"||t=="HS")   f=sprintf("highshelf=f=%s:t=q:w=%s:g=%s",fc,q,g)
      else next
      out = out (out!="" ? "," : "") f
    }
    END {
      if(pre!="") out = sprintf("volume=%sdB",pre) (out!="" ? "," : "") out
      print out
    }' "$1"
}

# Full DSP chain. $1=master adds a 24-bit integer output stage.
build_chain() {
  local c="" f
  if [ "$USE_EQ" = 1 ] && [ -s "$EQ_FILE" ]; then
    f=$(eq_to_filters "$EQ_FILE"); [ -n "$f" ] && c="$f"
  fi
  [ "${CROSSFEED:-0}" != 0 ] && c="${c:+$c,}crossfeed=strength=$CROSSFEED"
  c="${c:+$c,}alimiter=limit=0.98:level=0"            # safety vs clipping after EQ boost
  [ "$RATE" != keep ] && c="$c,aresample=$RATE:$(resampler_opts)"
  if [ "${1:-}" = master ]; then
    c="$c,aformat=sample_fmts=s32"
    [ "$RATE" != keep ] && c="$c:sample_rates=$RATE"
  fi
  echo "$c"
}

pick_ao() {
  [ "$AO" != auto ] && { echo "$AO"; return; }
  have mpv || return 0
  local list a; list=$(mpv --ao=help 2>/dev/null)
  for a in aaudio audiotrack opensles pulse; do
    echo "$list" | grep -qE "^[[:space:]]+$a[[:space:]]" && { echo "$a"; return; }
  done
}

# ---------- commands ----------
cmd_install() {
  say "Installing packages"
  pkg update -y && pkg install -y mpv ffmpeg sox termux-api util-linux procps curl coreutils || die "pkg failed"
  [ -d "$HOME/storage" ] || termux-setup-storage
  mkdir -p "$CFG_DIR"
  if [ ! -f "$CFG" ]; then
    cat > "$CFG" <<'EOF'
# usbaudio configuration (sourced as shell)
RATE=48000
FMT=float
AO=auto
BUFFER=0.4
REPLAYGAIN=album
CROSSFEED=0
USE_EQ=1
PIN=auto
NICE=-10
OUT_DIR="$HOME/storage/music/mastered"
JOBS=""
EOF
  fi
  local self; self=$(realpath "$0" 2>/dev/null || echo "$0")
  chmod +x "$self"
  [ "$self" = "$PREFIX/bin/usbaudio" ] || ln -sf "$self" "$PREFIX/bin/usbaudio"
  ok "installed → run: usbaudio doctor"
  warn "also install the 'Termux:API' app (F-Droid) for termux-usb / termux-volume"
}

usb_info() {
  local n id s found=0
  for n in 0 1 2 3 4 5 6 7; do
    id=$(rd "/proc/asound/card$n/id") || continue
    [ -z "$id" ] && continue
    s=$(rd "/proc/asound/card$n/stream0")
    if [ -n "$s" ]; then
      found=1
      echo "  card$n ($id)"
      echo "$s" | grep -E 'Playback|Format|Channels|Rates|Bits|Endpoint|Sync|Data packet' | sed 's/^/     /'
    fi
  done
  [ "$found" = 0 ] && warn "no USB stream info (no DAC plugged in, or /proc/asound unreadable without root)"
  if have termux-usb; then
    echo "  termux-usb: $(termux-usb -l 2>/dev/null | tr -d '\n ' | cut -c1-120)"
  fi
  if detect_priv; [ -n "$PRIV" ]; then
    say "Audio flinger (USB / mixer)"
    priv "dumpsys media.audio_flinger" 2>/dev/null \
      | grep -iE 'usb|Sample rate|HAL format|Format:|Output thread' | head -20 | sed 's/^/  /'
  fi
}

cmd_doctor() {
  say "Device"
  echo "  $(getprop ro.product.manufacturer) $(getprop ro.product.model) — Android $(getprop ro.build.version.release) (SDK $(getprop ro.build.version.sdk))"
  detect_priv
  if [ -n "$PRIV" ]; then ok "privileged shell: $PRIV"; else warn "no root/Shizuku — DAC details & priority boost limited"; fi
  say "Tools"
  local t; for t in mpv ffmpeg sox taskset termux-usb termux-volume; do
    if have "$t"; then ok "$t"; else warn "$t missing"; fi
  done
  detect_soxr
  if [ "$SOXR" = 1 ]; then ok "ffmpeg soxr resampler (precision 28)"; else warn "no soxr — falling back to swr/kaiser (still good)"; fi
  have mpv && echo "  mpv audio output: $(pick_ao)"
  say "CPU"
  local cores; cores=$(auto_cores)
  echo "  player cores: ${cores:-n/a (cpufreq unreadable or single cluster)}  — nproc=$(nproc)"
  say "USB audio"
  usb_info
  say "Notes"
  cat <<'EOF'
  • Android's mixer renders everything at 48 kHz float. Feeding it 48 kHz/float
    (this script's default) means the system resampler is bypassed.
  • For true bit-perfect / >48 kHz output use a player with its own USB driver
    (UAPP, Neutron, Poweramp "USB exclusive") on files made with:
        usbaudio master -r keep ...
  • Keep player/app volume at 100 % and set level with the Android (or DAC) volume.
EOF
}

cmd_tune() {
  have termux-wake-lock && termux-wake-lock && ok "wake-lock held"
  if priv "dumpsys deviceidle whitelist +com.termux" >/dev/null 2>&1; then
    ok "Termux exempt from Doze/battery limits"
  else
    warn "set manually: Settings → Apps → Termux → Battery → Unrestricted"
  fi
  if [ -n "$PRIV" ]; then
    priv "settings put system sound_effects_enabled 0; settings put system dtmf_tone 0" >/dev/null 2>&1 \
      && ok "UI sounds off (nothing mixed into the DAC stream)"
  fi
  if [ -n "${1:-}" ] && have termux-volume; then
    termux-volume music "$1" >/dev/null && ok "music volume index → $1"
  fi
  ok "tuned"
}

cmd_play() {
  need mpv; detect_soxr
  [ $# -gt 0 ] || die "usage: usbaudio play <file|dir|url>..."
  local ao chain fmt cores
  ao=$(pick_ao)
  chain=$(build_chain)
  case "$FMT" in s32|s16|float) fmt="$FMT" ;; *) fmt=float ;; esac

  if [ "$ao" = pulse ]; then
    have pulseaudio || die "ao=pulse needs: pkg install pulseaudio"
    pgrep -x pulseaudio >/dev/null || { pulseaudio --start --exit-idle-time=-1 >/dev/null 2>&1; pactl load-module module-sles-sink >/dev/null 2>&1; }
  fi

  local args=(--no-video --audio-display=no --volume=100 --volume-max=100
              --gapless-audio=yes --audio-buffer="$BUFFER" --audio-stream-silence=yes
              --audio-channels=stereo --audio-format="$fmt"
              --replaygain="$REPLAYGAIN" --replaygain-clip=no
              "--af=lavfi=[$chain]")
  [ "$RATE" != keep ] && args+=(--audio-samplerate="$RATE")
  [ -n "$ao" ] && args+=(--ao="$ao")

  case "$PIN" in
    auto) cores=$(auto_cores) ;;
    none) cores="" ;;
    *)    cores="$PIN" ;;
  esac
  local pre=()
  if [ -n "$cores" ] && have taskset; then pre=(taskset -c "$cores"); fi

  # priority boost once mpv is up (needs privilege; silently skipped otherwise)
  ( sleep 3; pid=$(pgrep -n -x mpv); [ -n "$pid" ] && priv "renice -n $NICE -p $pid" >/dev/null 2>&1 ) &

  say "ao=${ao:-default} fmt=$fmt rate=$RATE cores=${cores:-all} soxr=$SOXR eq=$([ "$USE_EQ" = 1 ] && [ -s "$EQ_FILE" ] && echo on || echo off)"
  "${pre[@]}" mpv "${args[@]}" "$@"
}

cmd_master() {
  need ffmpeg; detect_soxr
  local out="$OUT_DIR"
  while [ $# -gt 0 ]; do
    case "$1" in
      -o) out="$2"; shift 2 ;;
      -r) RATE="$2"; shift 2 ;;
      --no-eq) USE_EQ=0; shift ;;
      --) shift; break ;;
      -*) die "unknown option $1" ;;
      *) break ;;
    esac
  done
  [ $# -gt 0 ] || die "usage: usbaudio master [-o dir] [-r 48000|keep] [--no-eq] <files|dirs>"
  mkdir -p "$out" || die "cannot create $out"
  local chain; chain=$(build_chain master)
  local jobs="${JOBS:-$(( $(nproc) / 2 ))}"; [ "$jobs" -lt 1 ] && jobs=1
  say "→ $out  rate=$RATE  24-bit FLAC  jobs=$jobs  soxr=$SOXR"

  convert() { # in dst
    local in="$1" dst="$2"
    mkdir -p "$(dirname "$dst")"
    [ -e "$dst" ] && { echo "  skip $(basename "$dst")"; return; }
    if ffmpeg -nostdin -hide_banner -loglevel error -i "$in" \
         -map 0:a:0 -map '0:v:0?' -c:v copy -disposition:v attached_pic -map_metadata 0 \
         -af "$chain" -c:a flac -compression_level 8 -sample_fmt s32 -bits_per_raw_sample 24 \
         "$dst.part.flac"; then
      mv "$dst.part.flac" "$dst"; ok "$(basename "$dst")"
    else
      rm -f "$dst.part.flac"; warn "failed: $in"
    fi
  }

  local src base rel f
  for src in "$@"; do
    if [ -d "$src" ]; then base=$(dirname "$src"); else base=$(dirname "$src"); fi
    while IFS= read -r -d '' f; do
      rel="${f#"$base"/}"
      while [ "$(jobs -rp | wc -l)" -ge "$jobs" ]; do wait -n; done
      convert "$f" "$out/${rel%.*}.flac" &
    done < <(find "$src" -type f \( -iname '*.flac' -o -iname '*.wav' -o -iname '*.mp3' -o -iname '*.m4a' \
              -o -iname '*.ogg' -o -iname '*.opus' -o -iname '*.aif*' -o -iname '*.ape' -o -iname '*.wv' \) -print0)
  done
  wait
  ok "done"
}

cmd_eq() {
  mkdir -p "$CFG_DIR"
  case "${1:-}" in
    off)  rm -f "$EQ_FILE"; ok "EQ removed"; return ;;
    "")   ;;
    http*) need curl; curl -fsSL "$1" -o "$EQ_FILE.tmp" || die "download failed"; mv "$EQ_FILE.tmp" "$EQ_FILE" ;;
    *)    [ -f "$1" ] || die "no such file: $1"; cp "$1" "$EQ_FILE" ;;
  esac
  if [ -s "$EQ_FILE" ]; then
    say "active EQ chain:"; eq_to_filters "$EQ_FILE" | tr ',' '\n' | sed 's/^/  /'
  else
    echo "no EQ set — usage: usbaudio eq <ParametricEQ.txt|url|off>"
    echo "profiles: https://github.com/jaakkopasanen/AutoEq (use the ParametricEQ.txt of your headphones)"
  fi
}

cmd_test() {
  need ffmpeg; need mpv
  local d; d=$(mktemp -d)
  ffmpeg -nostdin -loglevel error -f lavfi -i "sine=f=440:d=2:r=48000" -f lavfi -i "sine=f=660:d=2:r=48000" \
    -filter_complex "[0][1]join=inputs=2:channel_layout=stereo,volume=0.25" -c:a pcm_f32le "$d/1_channels_L440_R660.wav"
  ffmpeg -nostdin -loglevel error -f lavfi -i "sine=f=997:d=4:r=48000" -af "volume=0.25,pan=stereo|c0=c0|c1=c0" -c:a pcm_f32le "$d/2_997Hz.wav"
  ffmpeg -nostdin -loglevel error -f lavfi -i "aevalsrc='0.25*sin(2*PI*20*10/log(1000)*(pow(1000,t/10)-1))':d=10:s=48000:c=stereo" \
    -c:a pcm_f32le "$d/3_sweep_20-20k.wav"
  say "left ear = low tone, right ear = higher tone; then 997 Hz; then sweep (listen for distortion/dropouts)"
  cmd_play "$d"/*.wav
  rm -rf "$d"
}

cmd_config() {
  mkdir -p "$CFG_DIR"; [ -f "$CFG" ] || cmd_install
  echo "$CFG"; "${EDITOR:-nano}" "$CFG" 2>/dev/null || cat "$CFG"
}

cmd_help() { sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; }

case "${1:-help}" in
  install) shift; cmd_install "$@" ;;
  doctor)  shift; cmd_doctor "$@" ;;
  tune)    shift; cmd_tune "$@" ;;
  play)    shift; cmd_play "$@" ;;
  master)  shift; cmd_master "$@" ;;
  eq)      shift; cmd_eq "$@" ;;
  test)    shift; cmd_test "$@" ;;
  config)  shift; cmd_config "$@" ;;
  *)       cmd_help ;;
esac
