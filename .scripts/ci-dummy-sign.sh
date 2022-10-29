#!/usr/bin/env bash

mkdir build
mkdir build/output

cp mirai-login-solver-sakura/build/mirai/* build/output

apksigner sign -v --key keys/ci-dummy.key.pkcs8 --cert keys/ci-dummy.crt --out build/output/apk-debug-cidummy.apk android/app/build/outputs/apk/debug/app-debug.apk

if [ -f "keys/org.key.pkcs8" ]; then
  apksigner sign -v --key keys/org.key.pkcs8 --cert keys/org.crt --out build/output/apk-release.apk "$(find android/app/build/outputs/apk/release | grep -E ".apk$")"
fi
