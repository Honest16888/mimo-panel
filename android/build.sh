#!/usr/bin/env bash
# MiMo 面板 APK 无 Gradle 构建脚本
# 依赖：android.jar、build-tools(aapt2/d8/zipalign/apksigner)、ecj.jar、自签名 keystore
set -e
cd "$(dirname "$0")"

AJ=${ANDROID_JAR:?需设置 ANDROID_JAR，例如 /opt/android-13/android.jar}
BT=${BUILD_TOOLS:?需设置 BUILD_TOOLS，例如 /opt/build-tools}
ECJ=${ECJ_JAR:?需设置 ECJ_JAR，例如 ./ecj.jar}
KS=${KEYSTORE:-./release.jks}

rm -rf res.zip gen classes dexout stage unsigned.apk aligned.apk
mkdir -p gen classes dexout stage/assets

# 1) 资源编译
"$BT/aapt2" compile --dir res -o res.zip

# 2) 资源链接 + 生成 R.java
"$BT/aapt2" link -o unsigned.apk -I "$AJ" --manifest AndroidManifest.xml -R res.zip \
  --auto-add-overlay --min-sdk-version 21 --target-sdk-version 33 --java gen

# 3) Java 编译（含 R.java）
java -jar "$ECJ" -source 8 -target 8 -nowarn -bootclasspath "$AJ" -d classes \
  $(find src gen -name '*.java')

# 4) dex
"$BT/d8" --release --lib "$AJ" --min-api 21 --output dexout $(find classes -name '*.class')

# 5) 打包 assets 与 classes.dex
cp dexout/classes.dex stage/
cp assets/index.html stage/assets/
"$BT/aapt2" link -o unsigned.apk -I "$AJ" --manifest AndroidManifest.xml -R res.zip \
  --auto-add-overlay --min-sdk-version 21 --target-sdk-version 33
(cd stage && zip -q -r ../unsigned.apk assets classes.dex)

# 6) 对齐 + 签名
"$BT/zipalign" -f -p 4 unsigned.apk aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --min-sdk-version 21 \
  --out MiMoPanel.apk aligned.apk
"$BT/apksigner" verify MiMoPanel.apk
echo "构建完成：MiMoPanel.apk"
