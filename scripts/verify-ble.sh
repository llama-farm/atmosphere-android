#!/bin/bash
# Verify BLE Transport Initialization
# Run this script after installing the updated APK with BLE fix

set -e

echo "🔍 Verifying BLE Transport..."
echo

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo "❌ No Android device connected via ADB"
    exit 1
fi

echo "✅ Device connected"
echo

# Check if app is installed
if ! adb shell pm list packages | grep -q "com.llamafarm.atmosphere"; then
    echo "❌ Atmosphere app not installed"
    exit 1
fi

echo "✅ App installed"
echo

# Clear logcat
echo "Clearing logcat..."
adb logcat -c

# Start the app
echo "Starting app..."
adb shell am start -n com.llamafarm.atmosphere/.MainActivity

echo
echo "⏳ Waiting 5 seconds for app to initialize..."
sleep 5

echo
echo "📋 Checking BLE logs..."
echo

# Check for BLE initialization logs
if adb logcat -d | grep -q "Added BLE endpoint"; then
    echo "✅ BLE endpoint was added to saved mesh"
else
    echo "⚠️  BLE endpoint not found in logs (might not have joined/reconnected yet)"
fi

if adb logcat -d | grep -q "Starting BLE transport"; then
    echo "✅ BLE transport initialization started"
else
    echo "⚠️  BLE transport not started yet"
fi

if adb logcat -d | grep -q "BLE transport started"; then
    echo "✅ BLE transport successfully started"
else
    echo "⚠️  BLE transport not fully initialized"
fi

if adb logcat -d | grep -i "bluetooth\|ble" | grep -q "advertis"; then
    echo "✅ BLE advertising detected"
else
    echo "⚠️  BLE advertising not detected"
fi

echo
echo "📊 Full BLE-related logs:"
echo "─────────────────────────────────────────"
adb logcat -d | grep -i "ble\|bluetooth" | tail -30

echo
echo "💡 Tip: To monitor BLE in real-time, run:"
echo "   adb logcat | grep -i 'ble\|bluetooth\|advertis'"
