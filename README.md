<p align="center"><img src="assets/banner.svg" alt="VTLoutput_HQ - made for Pixel 8 Pro, Android 17 (CP4A.261105.040.A1)" width="100%"></p>

# VTLoutput USB HQ Audio Module

> **Built for Pixel 8 Pro - Android 17 (build CP4A.261105.040.A1).** Other devices/ROMs are untested.

High-quality USB-C audio on rooted Android: a Magisk module, a companion app (**VTLoutput_HQ**) and a Termux toolkit.

## Contents
- `module/` - Magisk module `vtlo_usb_audio_hq` (v1.1): persistent high-precision Android resampler settings (properties only, nothing in /system is modified). See `module/README.md`.
- `app/` - VTLoutput_HQ Android app (v1.5, pure Java): module installer, resampler profiles with filter-response graph, live USB signal path, spectrum (beta), AutoEq + 10-band float multiband equalizer (128 bands, auto headroom, no resampling), USB DAC hardware gain control (ALSA mixer, root) and an output-impedance calculator. The module zip is bundled in `app/app/src/main/assets/module.zip`.
- `tools/` - `vtlo-alsactl`: tiny static aarch64 ALSA mixer tool (C source + binary) used by the app for the DAC hardware gain.
- `termux-usbaudio.sh` - Termux all-in-one playback script.
- `releases/` - prebuilt `VTLoutput_HQ_v1.5.apk` and `vtlo_usb_audio_hq_v1.1.zip`.

## Build the app
```
cd app
echo "sdk.dir=/path/to/Android/sdk" > local.properties
gradle assembleRelease      # JDK 17+, AGP 8.7
gradle testDebugUnitTest    # Robolectric tests
```
Without a `keystore.properties` (storeFile, storePassword, keyAlias, keyPassword) the release build is signed with the debug key.

## Limits
- The EQ uses Android's DynamicsProcessing (float, session 0 + announced sessions): best-effort, apps using the Android 14+ bit-perfect USB path bypass effects, and any EQ is by definition not bit-perfect.
- Hardware gain only works for DACs that expose a USB volume control to the kernel; untested on real hardware.
- Affects Android's system resampler only; not bit-perfect, cannot force the DAC sample rate.
- Android cannot read or change a USB DAC's output impedance: the impedance card is a calculator fed with spec-sheet values.
- Verified with unit tests only; the spectrum view depends on ROM support.
