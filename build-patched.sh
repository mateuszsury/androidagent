#!/data/data/com.termux/files/usr/bin/bash
# 合并构建：复用已安装官方 APK 的 assets 与 native 库（终端、Docker、USB 资源不在源码里），
# 仅替换资源表、AndroidManifest 与 classes.dex。输出 build-patched/buddy-release.apk。
set -euo pipefail
cd "$(dirname "$0")"
SDK_JAR=${SDK_JAR:-$HOME/android-sdk/android-36/android.jar}
KEYSTORE=${KEYSTORE:-$HOME/.buddy-keys/release.jks}
PACKAGE=${PACKAGE:-dev.mateuszsury.androidagent}
SOURCE_PACKAGE=${SOURCE_PACKAGE:-dev.mateuszsury.androidagent}
SRC=app/src/main
OUT=${OUT:-$PWD/build-patched8}
APK=$(pm path "$SOURCE_PACKAGE" | sed 's/^package://' | head -1)
[ -f "$APK" ] || { echo "未找到已安装的 $SOURCE_PACKAGE APK" >&2; exit 1; }
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/newres"

echo "1/7 编译资源"
aapt2 compile --dir "$SRC/res" -o "$OUT/res.zip"
sed "s|<manifest |<manifest package=\"$PACKAGE\" |" "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
aapt2 link -I "$SDK_JAR" --manifest "$OUT/AndroidManifest.xml" --min-sdk-version 30 \
  --target-sdk-version 36 --version-code 500 --version-name 0.5.0 \
  -0 gz -0 zip -o "$OUT/base.apk" "$OUT/res.zip"
python3 tools/gen-rjava.py "$OUT/base.apk" "$OUT/R.java"

echo "2/7 编译 Java"
javac --release 8 -classpath "$SDK_JAR" -Xlint:-options -encoding UTF-8 \
  -d "$OUT/classes" $(find "$SRC/java" -name '*.java') "$OUT/R.java"

echo "3/7 Dex"
d8 --release --min-api 30 --lib "$SDK_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "4/7 合并已安装 APK 的资源"
cp "$APK" "$OUT/merged.apk"
zip -q -d "$OUT/merged.apk" 'res/*' 'res' 'AndroidManifest.xml' 'resources.arsc' 'classes.dex' \
  'termux/*' 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.DSA' 'META-INF/*.EC' 'META-INF/*.MF' >/dev/null 2>&1 || true
(cd "$OUT/newres" && unzip -q -o "$OUT/base.apk" 'AndroidManifest.xml' 'resources.arsc' 'res/*')
(cd "$OUT/newres" && zip -q -X -0 "$OUT/merged.apk" resources.arsc)
(cd "$OUT/newres" && zip -q -rX "$OUT/merged.apk" AndroidManifest.xml res)
(cd "$OUT/dex" && zip -q -X "$OUT/merged.apk" classes.dex)
mkdir -p "$OUT/pkgassets/assets"
cp -r termux "$OUT/pkgassets/assets/"
(cd "$OUT/pkgassets" && zip -q -X "$OUT/merged.apk" assets/termux/buddy-run assets/termux/system-prompt.md \
  assets/termux/android-app/SKILL.md assets/termux/service-launch.sh)

echo "5/7 对齐"
python3 tools/zipalign.py "$OUT/merged.apk" "$OUT/aligned.apk"

echo "6/7 签名"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"; chmod 700 "$(dirname "$KEYSTORE")"
  PASS=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
  printf '%s' "$PASS" > "$(dirname "$KEYSTORE")/password"; chmod 600 "$(dirname "$KEYSTORE")/password"
  keytool -genkeypair -keystore "$KEYSTORE" -alias buddy -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" -dname "CN=Buddy, O=Personal" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYSTORE" --ks-key-alias buddy \
  --ks-pass "file:$(dirname "$KEYSTORE")/password" \
  --out "$OUT/buddy-release.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/buddy-release.apk"
ls -l "$OUT/buddy-release.apk"
echo "完成：$OUT/buddy-release.apk"
