# VTLoutput USB HQ Audio Module

High-quality USB-C audio on rooted Android: a Magisk module, a companion app (**VTLoutput_HQ**) and a Termux toolkit.

## Contents
- `module/` - Magisk module `vtlo_usb_audio_hq` (v1.1): persistent high-precision Android resampler settings (properties only, nothing in /system is modified). See `module/README.md`.
- `app/` - VTLoutput_HQ Android app (v1.4, pure Java): module installer, resampler profiles with filter-response graph, live USB signal path, spectrum (beta), and an output-impedance calculator (DAC output impedance vs headphone impedance). The module zip is bundled in `app/app/src/main/assets/module.zip`.
- `termux-usbaudio.sh` - Termux all-in-one playback script.
- `releases/` - prebuilt `VTLoutput_HQ_v1.4.apk` and `vtlo_usb_audio_hq_v1.1.zip`.

## Build the app
```
cd app
echo "sdk.dir=/path/to/Android/sdk" > local.properties
gradle assembleRelease      # JDK 17+, AGP 8.7
gradle testDebugUnitTest    # Robolectric tests
```
Without a `keystore.properties` (storeFile, storePassword, keyAlias, keyPassword) the release build is signed with the debug key.

## Limits
- Affects Android's system resampler only; not bit-perfect, cannot force the DAC sample rate.
- Android cannot read or change a USB DAC's output impedance: the impedance card is a calculator fed with spec-sheet values.
- Verified with unit tests only; the spectrum view depends on ROM support.
