#!/usr/bin/env bash
# 电脑上的一条命令开发循环：构建安装 → 端口转发 → 启动 App。
#
# 真机（无线调试，推荐）：
#   adb pair <手机IP>:<配对端口>
#   adb connect <手机IP>:<调试端口>
#   ./scripts/deploy.sh
#
# 网关侧（另开终端，跑在 WSL 里；端口和 --public-url 用回环地址配合 adb reverse）：
#   cd ~/code/pi-mobile-gateway
#   npm start -- --pair "我的手机" --data-port 3080 --public-url ws://127.0.0.1:3080/ws/mobile
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

ADB="$ANDROID_HOME/platform-tools/adb"
GATEWAY_PORT="${GATEWAY_PORT:-3080}"  # 与 App 默认 endpoint 一致
# 应用身份带 Preview 后缀，Activity 类名仍使用源码 namespace。
PACKAGE="com.finecat.pimobile.android.preview"
ACTIVITY="com.finecat.pimobile.android.MainActivity"

cd "$(dirname "$0")/.."

DEVICE="${ANDROID_SERIAL:-}"
if [ -z "$DEVICE" ]; then
  DEVICE=$("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')
fi

if [ -z "$DEVICE" ]; then
  echo "没有在线设备：先无线调试连真机（adb pair / adb connect）" >&2
  exit 1
fi

echo "目标设备：$DEVICE"
./gradlew :androidApp:installDebug
"$ADB" -s "$DEVICE" reverse "tcp:$GATEWAY_PORT" "tcp:$GATEWAY_PORT" > /dev/null
"$ADB" -s "$DEVICE" logcat -c
"$ADB" -s "$DEVICE" shell am start -n "$PACKAGE/$ACTIVITY" > /dev/null

echo "Pi Mobile Preview 已安装并启动。"
echo "网关日志：adb -s $DEVICE logcat -s PiGateway:D '*:S'"
