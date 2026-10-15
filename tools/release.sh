#!/bin/bash
# Build phone + watch APKs and publish them as a GitHub Release.
# The apps read releases/latest/download/update-{phone,watch}.json.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$HOME/Documents/Default Project/안드로이드-에뮬레이터"
export JAVA_HOME="$TOOLS/jdk17/Contents/Home"
export ANDROID_SDK_ROOT="$TOOLS/android-sdk"
REPO="kmnoh12/kcoach"

cd "$ROOT"
if [ -n "$(git status --porcelain)" ]; then
    echo "커밋 안 된 변경이 있어. 먼저 커밋해." >&2
    exit 1
fi

# One final integration pass after all implementation and parallel work is committed.
# Do not run a second full pass outside this script before publishing.
"$TOOLS/gradle-8.11.1/bin/gradle" -Pkotlin.incremental=false \
    :core:test :updates:testDebugUnitTest :phone:testDebugUnitTest :wear:testDebugUnitTest \
    :phone:lint :wear:lint :phone:assembleOta :wear:assembleOta

# A successful shrink/build does not guarantee the JNI entry points survive R8.
# Run the existing APK inspection before any push or public release. This does
# not verify native initialization or pose detection on the user's phone.
python3 tools/verify_mediapipe_jni.py phone/build/outputs/apk/ota/phone-ota.apk

version() { sed -n "s/.*$2 = \"\{0,1\}\([^\"]*\)\"\{0,1\}$/\1/p" "$1/build.gradle.kts" | head -1; }
PHONE_CODE=$(version phone versionCode)
PHONE_NAME=$(version phone versionName)
WATCH_CODE=$(version wear versionCode)
WATCH_NAME=$(version wear versionName)
TAG="p${PHONE_NAME}-w${WATCH_NAME}"

OUT="$ROOT/build/release/$TAG"
rm -rf "$OUT" && mkdir -p "$OUT"
cp phone/build/outputs/apk/ota/phone-ota.apk "$OUT/kcoach-phone.apk"
cp wear/build/outputs/apk/ota/wear-ota.apk "$OUT/kcoach-watch.apk"

manifest() { # kind code name
    local sha
    sha=$(shasum -a 256 "$OUT/kcoach-$1.apk" | cut -d' ' -f1)
    cat > "$OUT/update-$1.json" <<EOF
{
  "versionCode": $2,
  "versionName": "$3",
  "apkUrl": "https://github.com/$REPO/releases/download/$TAG/kcoach-$1.apk",
  "sha256": "$sha"
}
EOF
}
manifest phone "$PHONE_CODE" "$PHONE_NAME"
manifest watch "$WATCH_CODE" "$WATCH_NAME"

git push origin HEAD
gh release create "$TAG" --repo "$REPO" --target "$(git rev-parse HEAD)" --latest \
    --title "폰 $PHONE_NAME · 워치 $WATCH_NAME" --notes "${1:-}" \
    "$OUT"/kcoach-phone.apk "$OUT"/kcoach-watch.apk "$OUT"/update-phone.json "$OUT"/update-watch.json

echo "배포 완료: https://github.com/$REPO/releases/tag/$TAG"
