#!/usr/bin/env bash
set -euo pipefail
set -- ActionRelay-*.apk
[ "$#" -eq 1 ] && [ -f "$1" ] || { echo "Expected one release APK"; exit 1; }
APK_FILE=$1
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.dalvik.vm.native.bridge
adb install -r "$APK_FILE"

if [ "${CAPTURE_THEME:-light}" = "dark" ]; then
  adb shell cmd uimode night yes
else
  adb shell cmd uimode night no
fi

# Grant runtime permissions so UI screens render cleanly without blocking dialogs
adb shell pm grant cn.local.actionrelay android.permission.BLUETOOTH_SCAN || true
adb shell pm grant cn.local.actionrelay android.permission.BLUETOOTH_CONNECT || true
adb shell pm grant cn.local.actionrelay android.permission.POST_NOTIFICATIONS || true
adb shell pm grant cn.local.actionrelay android.permission.ACCESS_FINE_LOCATION || true
adb shell pm grant cn.local.actionrelay android.permission.ACCESS_COARSE_LOCATION || true

adb shell am start -n cn.local.actionrelay/.MainActivity
sleep 5

python3 - << 'PYEOF'
import re, subprocess, time, xml.etree.ElementTree as ET

def adb(*args):
    return subprocess.check_output(["adb", *args])

def snap(name):
    time.sleep(1.5)
    data = adb("exec-out", "screencap", "-p")
    with open(f"screenshots/{name}", "wb") as f:
        f.write(data)
    print(f"Captured screenshots/{name} ({len(data)} bytes)")

def tap_text(target_text):
    xml_bytes = adb("exec-out", "uiautomator", "dump", "/dev/tty")
    xml_str = xml_bytes.decode("utf-8", errors="ignore")
    start = xml_str.find("<?xml")
    end = xml_str.rfind("</hierarchy>")
    if start == -1 or end == -1:
        raise RuntimeError(f"Failed to dump UI hierarchy when looking for {target_text}")
    root = ET.fromstring(xml_str[start:end + len("</hierarchy>")])
    for node in root.iter("node"):
        txt = node.attrib.get("text", "")
        desc = node.attrib.get("content-desc", "")
        if txt == target_text or desc == target_text:
            bounds = node.attrib.get("bounds", "")
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
            if m:
                x = (int(m.group(1)) + int(m.group(3))) // 2
                y = (int(m.group(2)) + int(m.group(4))) // 2
                adb("shell", "input", "tap", str(x), str(y))
                time.sleep(1.5)
                return True
    raise RuntimeError(f"Required UI element not found: {target_text}")

def go_back():
    adb("shell", "input", "keyevent", "4")
    time.sleep(1.5)

# 1. Home page
snap("01_phone_home.png")

# 2. Camera page
if tap_text("相机"):
    snap("02_phone_camera.png")
    go_back()

# 3. GPS page
if tap_text("GPS"):
    snap("03_phone_gps.png")
    go_back()

# 4. Tools page -> Updates & Logs
if tap_text("工具"):
    snap("04_phone_tools.png")
    if tap_text("检查更新"):
        time.sleep(3)
        snap("05_phone_updates.png")
        go_back()
        tap_text("工具")
    if tap_text("连接记录"):
        snap("06_phone_logs.png")
        go_back()
PYEOF
