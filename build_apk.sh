#!/usr/bin/env bash

# Build APK Script for Love CCTV
set -e

echo "=========================================="
echo "      Building Love CCTV APK...          "
echo "=========================================="

# Ensure Java environment is ready
java -version

# Run Gradle assembleDebug task
gradle assembleDebug

echo "=========================================="
echo " Build Completed Successfully!           "
echo " APK Path: app/build/outputs/apk/debug/app-debug.apk "
echo "=========================================="
