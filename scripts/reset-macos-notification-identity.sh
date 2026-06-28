#!/usr/bin/env bash
set -euo pipefail

LSREGISTER="/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"

BUNDLE_IDS=(
  "com.yourcompany.notilinker.macos"
  "com.suseoaa.castpigeon.macos"
  "com.suseoaa.castpigeon.macos.debug"
  "com.suseoaa.castpigeon.castpigeonFlutter"
  "com.suseoaa.Castpigeon"
)

APP_PATHS=(
  "/Applications/CastPigeon.app"
  "/Applications/castpigeon_flutter.app"
  "$HOME/Applications/CastPigeon.app"
  "$HOME/Applications/castpigeon_flutter.app"
)

if [ -d "$HOME/Library/Developer/Xcode/DerivedData" ]; then
  while IFS= read -r app_path; do
    APP_PATHS+=("$app_path")
  done < <(
    find "$HOME/Library/Developer/Xcode/DerivedData" \
      \( -path "*/Build/Products/*/CastPigeon.app" -o -path "*/Build/Products/*/castpigeon_flutter.app" \) \
      -type d 2>/dev/null
  )
fi

echo "Quit CastPigeon before running this script if it is open."
echo

if [ -x "$LSREGISTER" ]; then
  for app_path in "${APP_PATHS[@]}"; do
    if [ -d "$app_path" ]; then
      echo "Unregistering LaunchServices record: $app_path"
      "$LSREGISTER" -u "$app_path" >/dev/null 2>&1 || true
    fi
  done
else
  echo "lsregister not found; skipping LaunchServices unregister."
fi

for bundle_id in "${BUNDLE_IDS[@]}"; do
  echo "Resetting notification permission: $bundle_id"
  tccutil reset UserNotifications "$bundle_id" >/dev/null 2>&1 || true
done

if [ "${1:-}" != "" ] && [ -d "${1:-}" ] && [ -x "$LSREGISTER" ]; then
  echo "Registering current app: $1"
  "$LSREGISTER" -f -R -trusted "$1" >/dev/null 2>&1 || true
fi

echo
echo "Done. Launch CastPigeon again and allow notifications when macOS asks."
