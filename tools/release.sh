#!/bin/bash
# Build only changed apps; reuse the other published APK, then publish one release.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$HOME/Documents/Default Project/안드로이드-에뮬레이터"
export JAVA_HOME="$TOOLS/jdk17/Contents/Home"
export ANDROID_SDK_ROOT="$TOOLS/android-sdk"
REPO="kmnoh12/watchgym"
cd "$ROOT"

FULL_CHECK=0
PUBLISH_BUILT=0
BUILD_PHONE=0
BUILD_WATCH=0
EXPLICIT_SCOPE=0
while [ $# -gt 0 ]; do
    case "$1" in
        --full-check) FULL_CHECK=1; shift ;;
        --publish-built) PUBLISH_BUILT=1; shift ;;
        --phone) BUILD_PHONE=1; EXPLICIT_SCOPE=1; shift ;;
        --watch) BUILD_WATCH=1; EXPLICIT_SCOPE=1; shift ;;
        --both) BUILD_PHONE=1; BUILD_WATCH=1; EXPLICIT_SCOPE=1; shift ;;
        --help)
            echo 'Usage: tools/release.sh [--phone|--watch|--both] [--publish-built] [--full-check] [notes]'
            echo 'Default: detect changed apps since the latest public release; shared code rebuilds both.'
            echo '--publish-built: use existing selected APKs; never falls back to building.'
            echo '--full-check: explicitly request the full test/lint gate (otherwise never runs).'
            exit 0 ;;
        --*) echo "알 수 없는 옵션: $1" >&2; exit 1 ;;
        *) break ;;
    esac
done

if [ -n "$(git status --porcelain)" ]; then
    echo "커밋 안 된 변경이 있어. 배포할 변경을 먼저 정리해." >&2
    exit 1
fi

# Query once. An unchanged app always comes from this public release, never a stale local APK.
LATEST=$(gh api "repos/$REPO/releases/latest")
LATEST_TAG=$(printf '%s' "$LATEST" | python3 -c 'import json,sys; print(json.load(sys.stdin)["tag_name"])')
if [ "$EXPLICIT_SCOPE" = 0 ]; then
    BASE=$(printf '%s' "$LATEST" | python3 -c 'import json,sys; print(json.load(sys.stdin)["target_commitish"])')
    # Old releases may name a branch. Only a commit SHA or an exact release tag is a usable baseline.
    if ! [[ "$BASE" =~ ^[0-9a-f]{40}$ ]] || ! git cat-file -e "$BASE^{commit}" 2>/dev/null; then
        if ! BASE=$(git rev-parse --verify "refs/tags/$LATEST_TAG^{commit}" 2>/dev/null); then
            echo "최신 배포의 기준 커밋을 찾지 못했어. --phone / --watch / --both로 범위를 지정해." >&2
            exit 1
        fi
    fi
    while IFS= read -r path; do
        case "$path" in
            phone/*) BUILD_PHONE=1 ;;
            wear/*) BUILD_WATCH=1 ;;
            core/*|updates/*|build.gradle*|settings.gradle*|gradle.properties|gradle/*|buildSrc/*|build-logic/*|gradlew*)
                BUILD_PHONE=1; BUILD_WATCH=1 ;;
        esac
    done < <(git diff --name-only "$BASE" HEAD)
    if [ "$BUILD_PHONE" = 0 ] && [ "$BUILD_WATCH" = 0 ]; then
        echo "배포할 앱 변경이 없어. 빌드·검사·업로드를 실행하지 않았어."
        exit 0
    fi
fi

GRADLE="$TOOLS/gradle-8.11.1/bin/gradle"
PHONE_APK=phone/build/outputs/apk/ota/phone-ota.apk
WEAR_APK=wear/build/outputs/apk/ota/wear-ota.apk
version() { sed -n "s/.*$2 = \"\{0,1\}\([^\"]*\)\"\{0,1\}$/\1/p" "$1/build.gradle.kts" | head -1; }
PHONE_CODE=$(version phone versionCode)
PHONE_NAME=$(version phone versionName)
WATCH_CODE=$(version wear versionCode)
WATCH_NAME=$(version wear versionName)
TAG="p${PHONE_NAME}-w${WATCH_NAME}"
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    echo "이미 있는 배포 버전이야: $TAG. 변경한 앱의 버전을 먼저 올려." >&2
    exit 1
fi

WORK=$(mktemp -d "$ROOT/build-release.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
LOG_DIR="$ROOT/work/release"
mkdir -p "$LOG_DIR"
if [ "$FULL_CHECK" = 1 ]; then
    echo "명시적으로 요청한 전체 검사 실행 · $LOG_DIR/full-check.log"
    if ! "$GRADLE" --offline :core:test :updates:testDebugUnitTest :phone:testDebugUnitTest :wear:testDebugUnitTest \
        :phone:lint :wear:lint > "$LOG_DIR/full-check.log" 2>&1; then
        rg -n '(^e:|FAILED|FAILURE:|error:|What went wrong|^> )' "$LOG_DIR/full-check.log" | tail -40 || true
        exit 1
    fi
fi

TASKS=()
[ "$BUILD_PHONE" = 0 ] || TASKS+=(":phone:assembleOta")
[ "$BUILD_WATCH" = 0 ] || TASKS+=(":wear:assembleOta")
if [ "$PUBLISH_BUILT" = 1 ]; then
    if { [ "$BUILD_PHONE" = 1 ] && [ ! -f "$PHONE_APK" ]; } || { [ "$BUILD_WATCH" = 1 ] && [ ! -f "$WEAR_APK" ]; }; then
        echo "선택한 앱의 APK가 없어. --publish-built는 빌드를 자동 실행하지 않아." >&2
        exit 1
    fi
    echo "기존 APK 사용 · 재빌드 안 함"
else
    echo "선택한 앱만 빌드: ${TASKS[*]} · $LOG_DIR/build.log"
    if ! "$GRADLE" --offline "${TASKS[@]}" > "$LOG_DIR/build.log" 2>&1; then
        rg -n '(^e:|FAILED|FAILURE:|error:|What went wrong|^> )' "$LOG_DIR/build.log" | tail -40 || true
        exit 1
    fi
fi

# Reuse a locally cached public APK only when its hash matches the fetched public manifest.
reuse_published() {
    local role="$1"
    local cached="$ROOT/build/release/$LATEST_TAG/kcoach-$role.apk"
    gh release download "$LATEST_TAG" --repo "$REPO" --pattern "update-$role.json" --dir "$WORK"
    if [ -f "$cached" ] && python3 - "$cached" "$WORK/update-$role.json" <<'PYHASH'
import hashlib,json,sys
from pathlib import Path
expected=json.loads(Path(sys.argv[2]).read_text())['sha256']
sys.exit(0 if hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest()==expected else 1)
PYHASH
    then
        cp "$cached" "$WORK/kcoach-$role.apk"
    else
        gh release download "$LATEST_TAG" --repo "$REPO" --pattern "kcoach-$role.apk" --dir "$WORK"
    fi
}

if [ "$BUILD_PHONE" = 1 ]; then
    cp "$PHONE_APK" "$WORK/kcoach-phone.apk"
    # A short file check for the previous MediaPipe/R8 initialization regression; no runtime test.
    python3 tools/verify_mediapipe_jni.py "$PHONE_APK" --mapping phone/build/outputs/mapping/ota/mapping.txt \
        > "$LOG_DIR/mediapipe.log" 2>&1 || { cat "$LOG_DIR/mediapipe.log"; exit 1; }
else
    reuse_published phone
fi
if [ "$BUILD_WATCH" = 1 ]; then
    cp "$WEAR_APK" "$WORK/kcoach-watch.apk"
else
    reuse_published watch
fi

# Verify the exact upload files, including reused artifacts. No device or screen checks.
EXPECTED_SIGNER="45130eab69678aeee288c565de466912b97dab48c317cbcf0983430a22c1da2c"
for ROLE in phone watch; do
    APK="$WORK/kcoach-$ROLE.apk"
    SIGNER_INFO=$("$ANDROID_SDK_ROOT/build-tools/35.0.0/apksigner" verify --print-certs "$APK")
    SIGNER=$(printf '%s\n' "$SIGNER_INFO" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
    if [ "$SIGNER" != "$EXPECTED_SIGNER" ]; then
        echo "기존 앱과 업데이트 서명이 달라: $APK" >&2
        exit 1
    fi
    "$ANDROID_SDK_ROOT/build-tools/35.0.0/aapt2" dump badging "$APK" > "$WORK/$ROLE-badging.txt"
done

python3 - "$WORK" "$REPO" "$TAG" "$PHONE_CODE" "$PHONE_NAME" "$WATCH_CODE" "$WATCH_NAME" <<'PY'
import hashlib,json,re,sys
from pathlib import Path
work=Path(sys.argv[1]); repo,tag=sys.argv[2:4]
for role,code,name in [('phone',int(sys.argv[4]),sys.argv[5]),('watch',int(sys.argv[6]),sys.argv[7])]:
    badging=(work/f'{role}-badging.txt').read_text().splitlines()[0]
    assert "name='dev.kyungmin.kcoach'" in badging, f'{role}: wrong package'
    assert re.search(r"versionCode='(\d+)'",badging).group(1)==str(code), f'{role}: APK versionCode differs from source'
    assert re.search(r"versionName='([^']+)'",badging).group(1)==name, f'{role}: APK versionName differs from source'
    sha=hashlib.sha256((work/f'kcoach-{role}.apk').read_bytes()).hexdigest()
    manifest=work/f'update-{role}.json'
    if manifest.exists():
        old=json.loads(manifest.read_text())
        assert old['sha256']==sha and old['versionCode']==code and old['versionName']==name, f'{role}: published artifact mismatch'
    manifest.write_text(json.dumps(dict(versionCode=code,versionName=name,
        apkUrl=f'https://github.com/{repo}/releases/download/{tag}/kcoach-{role}.apk',sha256=sha),indent=2)+'\n')
PY

OUT="$ROOT/build/release/$TAG"
mkdir -p "$OUT"
for ASSET in kcoach-phone.apk kcoach-watch.apk update-phone.json update-watch.json; do
    mv "$WORK/$ASSET" "$OUT/$ASSET"
done
# Keep literal multiline notes in a file.
printf '%s\n' "${1:-}" > "$WORK/notes.md"
git push origin HEAD
gh release create "$TAG" --repo "$REPO" --target "$(git rev-parse HEAD)" --latest \
    --title "폰 $PHONE_NAME · 워치 $WATCH_NAME" --notes-file "$WORK/notes.md" \
    "$OUT"/kcoach-phone.apk "$OUT"/kcoach-watch.apk "$OUT"/update-phone.json "$OUT"/update-watch.json

echo "배포 완료: https://github.com/$REPO/releases/tag/$TAG"
