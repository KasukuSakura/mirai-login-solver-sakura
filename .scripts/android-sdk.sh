#!/usr/bin/env bash

whereis apksigner

# shellcheck disable=SC2164
cd /usr/local/lib/android/sdk/

pushd build-tools

cd $(ls -d */ | sort -Vr | head -n 1)
echo "Build-tools: $(pwd)"
pwd >> $GITHUB_PATH

popd

