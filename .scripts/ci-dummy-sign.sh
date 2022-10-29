#!/usr/bin/env bash

mkdir build
mkdir build/output

apksigner sign -v --key keys/ci-dummy.key.pkcs8 --cert keys/ci-dummy.crt --out build/output/apk-debug-cidummy.apk android/app/build/outputs/apk/debug/app-debug.apk
