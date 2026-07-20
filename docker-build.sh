#!/bin/bash
set -e
echo "Building builder docker image..."
docker build -t onliecodes-builder .

echo "Compiling Android app..."
docker run --rm -v "$(pwd)":/project onliecodes-builder gradle assembleDebug

echo "Done! The debug APK is generated at: app/build/outputs/apk/debug/app-debug.apk"
