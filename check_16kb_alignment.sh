#!/bin/bash

# Script to check 16 KB alignment of native libraries in APK
# Based on Android's check_elf_alignment.sh

APK_FILE="$1"

if [ -z "$APK_FILE" ]; then
    echo "Usage: $0 <apk_file>"
    exit 1
fi

if [ ! -f "$APK_FILE" ]; then
    echo "Error: APK file '$APK_FILE' not found"
    exit 1
fi

echo "Checking 16 KB alignment for: $APK_FILE"
echo "================================================"

# Create temporary directory
TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

# Extract APK
unzip -q "$APK_FILE" -d "$TEMP_DIR"

# Check if lib directory exists
if [ ! -d "$TEMP_DIR/lib" ]; then
    echo "No native libraries found in APK"
    exit 0
fi

# Find Android SDK and NDK paths
ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
if [ ! -d "$ANDROID_HOME" ]; then
    echo "Error: Android SDK not found. Please set ANDROID_HOME"
    exit 1
fi

# Find the latest build-tools version
BUILD_TOOLS_VERSION=$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)
ZIPALIGN="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/zipalign"

if [ ! -f "$ZIPALIGN" ]; then
    echo "Error: zipalign not found at $ZIPALIGN"
    exit 1
fi

# Check zipalign for 16 KB
echo "Checking zipalign for 16 KB alignment..."
if "$ZIPALIGN" -c -P 16 -v 4 "$APK_FILE"; then
    echo "✅ APK is properly aligned for 16 KB page size"
    ZIPALIGN_OK=true
else
    echo "❌ APK is NOT properly aligned for 16 KB page size"
    ZIPALIGN_OK=false
fi

echo ""
echo "Checking individual shared libraries..."
echo "======================================"

# Check each architecture
for arch in arm64-v8a x86_64; do
    if [ -d "$TEMP_DIR/lib/$arch" ]; then
        echo "Checking $arch libraries:"
        for so_file in "$TEMP_DIR/lib/$arch"/*.so; do
            if [ -f "$so_file" ]; then
                filename=$(basename "$so_file")
                echo -n "  $filename: "

                # Use objdump to check ELF alignment (if available)
                if command -v objdump >/dev/null 2>&1; then
                    # Check LOAD segments alignment
                    alignment_check=$(objdump -p "$so_file" 2>/dev/null | grep "LOAD" | awk '{print $NF}' | sed 's/2\*\*//' | sort -n | head -1)
                    if [ -n "$alignment_check" ] && [ "$alignment_check" -ge 14 ]; then
                        echo "✅ ALIGNED (2**$alignment_check)"
                    else
                        echo "❌ UNALIGNED (2**$alignment_check)"
                    fi
                else
                    echo "⚠️  Cannot check (objdump not available)"
                fi
            fi
        done
        echo ""
    fi
done

if [ "$ZIPALIGN_OK" = true ]; then
    echo "✅ Overall: APK appears to be 16 KB compatible"
    exit 0
else
    echo "❌ Overall: APK needs to be rebuilt for 16 KB compatibility"
    exit 1
fi
