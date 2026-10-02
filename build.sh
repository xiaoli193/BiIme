#!/usr/bin/env bash
# 不依赖 Gradle / Android Studio：aapt + javac + d8 + zipalign + apksigner 直接出 APK。
#
# 依赖：
#   JDK 17
#   Android SDK：platforms;android-34、build-tools;34.0.0
#   aapt / zipalign：Debian/Ubuntu 上是 `apt-get install -y aapt zipalign`
#
# 说明：Google 官方 build-tools 里的 aapt2 只有 x86_64 版，在 arm64 机器上跑不起来，
# 所以这里用系统自带的原生 aapt（v1 打包流程）+ build-tools 里的 Java 版 d8 / apksigner。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
OUT="${OUT:-$ROOT/build}"
APP_ID="com.dsh.biime"
VERSION_NAME="1.0"

# JDK：优先用 PATH 里的，找不到再在常见位置里挑一个
if ! command -v javac >/dev/null 2>&1; then
  for c in /usr/lib/jvm/java-17-openjdk-arm64 /usr/lib/jvm/java-17-openjdk-amd64 \
           /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/default-java; do
    if [ -x "$c/bin/javac" ]; then
      export JAVA_HOME="$c"
      export PATH="$c/bin:$PATH"
      break
    fi
  done
fi
command -v javac >/dev/null 2>&1 || { echo "找不到 javac，请安装 JDK 17"; exit 1; }

command -v aapt >/dev/null || { echo "缺少 aapt（apt-get install -y aapt zipalign）"; exit 1; }
command -v zipalign >/dev/null || { echo "缺少 zipalign（apt-get install -y aapt zipalign）"; exit 1; }
for f in "$BT/d8" "$BT/apksigner" "$PLATFORM"; do
  [ -e "$f" ] || { echo "缺少 $f，请安装 Android SDK build-tools;34.0.0 与 platforms;android-34"; exit 1; }
done
[ -s "$ROOT/app/assets/pinyin.dict" ] || {
  echo "缺少词典资源 app/assets/pinyin.dict。先跑一次："
  echo "  bash tools/fetch_data.sh"
  echo "（它会下载 CC-CEDICT + jieba 词频表并编译成 pinyin.dict 与 assoc.idx）"
  exit 1
}

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$ROOT/dist"

echo "==> 1/6 aapt 打包资源 + 生成 R.java"
aapt package -f -m \
  -J "$OUT/gen" \
  -M "$ROOT/app/AndroidManifest.xml" \
  -S "$ROOT/app/res" \
  -A "$ROOT/app/assets" \
  -I "$PLATFORM" \
  --min-sdk-version 21 \
  --target-sdk-version 34 \
  --version-code 1 \
  --version-name "$VERSION_NAME" \
  -F "$OUT/base.apk"

echo "==> 2/6 javac"
find "$ROOT/app/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -encoding UTF-8 -nowarn \
  -bootclasspath "$PLATFORM" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"
[ -d "$OUT/classes/${APP_ID//.//}" ] || { echo "javac 没产出 class"; exit 1; }

echo "==> 3/6 d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --lib "$PLATFORM" --min-api 21 --output "$OUT/dex" @"$OUT/classes.txt"

echo "==> 4/6 写入 classes.dex"
( cd "$OUT/dex" && zip -q -u "$OUT/base.apk" classes.dex )
unzip -l "$OUT/base.apk" | grep -E "classes.dex|pinyin.dict" || true

echo "==> 5/6 zipalign"
zipalign -f 4 "$OUT/base.apk" "$OUT/app-aligned.apk"

echo "==> 6/6 签名"
KS="$ROOT/dist/ime.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias ime -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=BiIme, O=DSH, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android --ks-key-alias ime \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$ROOT/dist/BiIme-$VERSION_NAME.apk" "$OUT/app-aligned.apk"

"$BT/apksigner" verify --print-certs "$ROOT/dist/BiIme-$VERSION_NAME.apk" | head -3
ls -la "$ROOT/dist/BiIme-$VERSION_NAME.apk"
echo "APK 产出: $ROOT/dist/BiIme-$VERSION_NAME.apk"
