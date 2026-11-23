# USB Audio HQ (VTLO)

Makes Android's own resampler high-precision, persistently, for every app (Deezer included) -
this matters because Deezer HiFi is 44.1 kHz and Android's mixer usually runs at 48 kHz.

What it does (properties only, set before audioserver starts; nothing in /system or /vendor is modified):
- ro.audio.resampler.psd.* : 179 dB stopband, half-length 408, tbw 99  (profile `hq`)
- af.resampler.quality     : 7 fallback

Config: /data/adb/vtlo_usbaudio/config (PROFILE=hq|ultra|custom|off, CUSTOM_*, ENABLE_AT, ADM_BUFFERING_MS).
Apply without reboot: su -c usbaudio-apply (new tracks pick it up). Or use the companion app.
Log: /data/adb/vtlo_usbaudio/log.txt.  Remove: uninstall in Magisk, or touch
/data/adb/modules/vtlo_usb_audio_hq/disable and reboot.

Check what the DAC really gets (play music first):  su -c usbaudio-status

Deezer app: Settings -> Audio -> quality HiFi, equalizer OFF, volume normalizer OFF; keep in-app volume at max.

Limits: not bit-perfect (Deezer does not request Android 14 bit-perfect mixer attributes);
it cannot force the DAC rate. Samples from other apps (UAPP, Poweramp exclusive) can.
