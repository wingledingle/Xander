#!/usr/bin/env bash
# Checks what each published APK contains (and doesn't): package id, launcher, and that Xander Files carries none
# of the separate apps. Run after: ./gradlew :app:assembleMobileFilesRelease :app:assembleMobileSuiteRelease
#   :apps:groovesmith:assembleRelease :apps:oleander:assembleRelease :apps:quinten:assembleRelease
set -eu
cd "$(dirname "$0")/.."
AAPT2=${AAPT2:-$(find "${ANDROID_HOME:-/opt/android-sdk}" ~/.gradle -name aapt2 -type f 2>/dev/null | head -1)}
fail=0
check() { if eval "$2"; then echo "ok - $1"; else echo "FAILED: $1"; fail=1; fi; }
pkg() { "$AAPT2" dump badging "$1" | sed -n "s/^package: name='\([^']*\)'.*/\1/p"; }
has() { unzip -l "$1" | grep -qE "$2"; }
classes() { for d in $(unzip -l "$1" | grep -o 'classes[0-9]*\.dex'); do unzip -p "$1" "$d"; done | strings; }

F=$(ls app/build/outputs/apk/mobileFiles/release/*.apk); S=$(ls app/build/outputs/apk/mobileSuite/release/*.apk)
G=$(ls apps/groovesmith/build/outputs/apk/release/*.apk); O=$(ls apps/oleander/build/outputs/apk/release/*.apk); Q=$(ls apps/quinten/build/outputs/apk/release/*.apk)

check "Xander Files is com.xander.files" '[ "$(pkg $F)" = com.xander.files ]'
check "Xander Files has no Studio web app" '! has $F "assets/modules/studio/"'
check "Xander Files has no Oleander" '! has $F "assets/web/oleander.wasm" && ! classes $F | grep -q "dev/oleander/app/MainActivity"'
check "Xander Files has no Quinten" '! classes $F | grep -q "Lapp/quinten/MainActivity"'
check "Xander Files keeps the 3D viewer and Quick Look" 'has $F "assets/modules/model3d/" && has $F "assets/modules/quicklook/"'
check "Suite is com.xander.files.suite with everything" '[ "$(pkg $S)" = com.xander.files.suite ] && has $S "assets/modules/studio/" && has $S "assets/web/oleander.wasm"'
check "Groovesmith is dev.groovesmith.studio, Studio only" '[ "$(pkg $G)" = dev.groovesmith.studio ] && has $G "assets/modules/studio/index.html" && ! has $G "assets/modules/model3d/"'
check "Oleander is dev.oleander.app, no Xander code" '[ "$(pkg $O)" = dev.oleander.app ] && has $O "assets/web/oleander.wasm" && ! classes $O | grep -q "com/xander"'
check "Quinten is app.quinten" '[ "$(pkg $Q)" = app.quinten ]'
for a in $F $S $G $O $Q; do check "$(basename $a) has a launcher" '"$AAPT2" dump badging $a | grep -q "^launchable-activity"'; done
exit $fail
