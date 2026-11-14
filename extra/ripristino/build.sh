#!/usr/bin/env bash
# Compila MG4 Ripristino Home (com.mg4.homerescue) con gli stessi strumenti del launcher:
# aapt2 -> javac -> D8 -> firma platform (v1+v2). Gli strumenti stanno in ../../tools (esclusi da git):
# se mancano, lancia prima ../../build.sh, che li scarica e li controlla.
# Uso: extra/ripristino/build.sh   →  build/MG4HomeRescue-<versione>.apk
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
T="$ROOT/tools"
OUT="$ROOT/build/ripristino"
ANDROID_JAR="$T/android.jar"
KEYSTORE="${KEYSTORE:-$ROOT/keys/platform.p12}"
KEY_PASS="${KEY_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-platform}"
VERSION_CODE=1
VERSION_NAME=1.0

for f in aapt2 android.jar d8.jar; do
  [ -f "$T/$f" ] || { echo "manca tools/$f: lancia prima ./build.sh del launcher"; exit 1; }
done
SIGN_CP="$T/d8.jar"
unzip -l "$T/d8.jar" 'com/android/apksig/ApkSigner.class' >/dev/null 2>&1 || SIGN_CP="$T/d8.jar:$T/apksig.jar"

rm -rf "$OUT" && mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/tools"
echo "==> risorse (aapt2)"
"$T/aapt2" compile --dir "$HERE/res" -o "$OUT/res/compiled.zip"
"$T/aapt2" link -I "$ANDROID_JAR" --manifest "$HERE/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 28 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$OUT/gen" -o "$OUT/base.apk" "$OUT/res/compiled.zip"
echo "==> java (javac, bytecode 8)"
# la classe di soccorso è quella condivisa in extra/HomeRescue (la stessa che usano le altre app MG4)
find "$HERE/src" "$ROOT/extra/HomeRescue" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -encoding UTF-8 --release 8 -classpath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 \
  | grep -v -E '^(Picked up JAVA_TOOL_OPTIONS|Note: )' || true
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
[ -s "$OUT/classes.txt" ] || { echo "compilazione fallita"; exit 1; }
echo "==> dex (D8)"
java -cp "$T/d8.jar" com.android.tools.r8.D8 --release --min-api 26 --lib "$ANDROID_JAR" \
  --output "$OUT/dex" @"$OUT/classes.txt" 2>&1 | grep -v 'Picked up JAVA_TOOL_OPTIONS' || true
test -f "$OUT/dex/classes.dex" || { echo "D8 fallito"; exit 1; }
echo "==> pacchetto e firma"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes*.dex)
javac -nowarn -d "$OUT/tools" -cp "$SIGN_CP" "$T/src/Sign.java" 2>&1 | grep -v 'Picked up' || true
java -cp "$OUT/tools:$SIGN_CP" Sign "$KEYSTORE" "$KEY_PASS" "$KEY_ALIAS" \
  "$OUT/unsigned.apk" "$ROOT/build/MG4HomeRescue-$VERSION_NAME.apk" 2>&1 | grep -v 'Picked up' || true
test -f "$ROOT/build/MG4HomeRescue-$VERSION_NAME.apk"
ls -la "$ROOT/build/MG4HomeRescue-$VERSION_NAME.apk"
