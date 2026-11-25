#!/bin/sh
# static aarch64 build of vtlo-alsactl (zig 0.13+): needs `pip install ziglang`
python3 -m ziglang cc -target aarch64-linux-musl -static -Os -s -o vtlo-alsactl-arm64 vtlo-alsactl.c
