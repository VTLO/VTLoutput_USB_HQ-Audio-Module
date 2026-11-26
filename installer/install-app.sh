#!/system/bin/sh
# Installs the bundled VTLoutput_HQ APK (same signing key as every release, so it updates in place).
# usage: install-app.sh <apk>   -> prints the pm result, exit 0 on success
APK="$1"
[ -f "$APK" ] || { echo "apk not found: $APK"; exit 1; }
TMP=/data/local/tmp/vtloutput_hq.apk
cp "$APK" "$TMP" 2>/dev/null && chmod 644 "$TMP" || TMP="$APK"
out="$(pm install -r -d "$TMP" 2>&1)"
rc=$?
echo "$out"
[ "$TMP" != "$APK" ] && rm -f "$TMP"
case "$out" in *Success*) exit 0 ;; esac
exit "${rc:-1}"
