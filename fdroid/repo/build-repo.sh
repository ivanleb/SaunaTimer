#!/usr/bin/env bash
# Builds the self-hosted F-Droid repo (https://ivanleb.github.io/SaunaTimer/repo) from the APKs
# attached to the GitHub Releases. The APKs are not rebuilt: they keep our release signature, so
# users can later move to the main F-Droid repo (reproducible builds) without reinstalling.
#
# Usage: build-repo.sh <output dir>
# Env:   REPO_KEYSTORE       path to the repo index signing keystore (PKCS12)
#        REPO_KEYSTORE_PASS  its password (store and key password are the same in PKCS12)
#        REPO_KEY_ALIAS      key alias (default: saunapulse-repo)
#        GITHUB_TOKEN        optional, only to avoid the API rate limit
# Needs: fdroidserver, python3, curl, java. Writes <output dir>/site, ready for GitHub Pages.
set -euo pipefail

SRC=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${1:?usage: build-repo.sh <output dir>}
GH_REPO=ivanleb/SaunaTimer
: "${REPO_KEYSTORE:?}" "${REPO_KEYSTORE_PASS:?}"
export REPO_KEYSTORE_PASS
REPO_KEY_ALIAS=${REPO_KEY_ALIAS:-saunapulse-repo}

rm -rf "$OUT"
mkdir -p "$OUT/repo" "$OUT/metadata"
cd "$OUT"

# App metadata: the fdroiddata recipes plus the fastlane store texts and icons.
for pair in com.saunapulse.app:mobile com.saunapulse.wear:wear; do
  id=${pair%%:*} module=${pair#*:}
  cp "$SRC/fdroid/metadata/$id.yml" metadata/
  cp -r "$SRC/$module/fastlane/metadata/android" "metadata/$id"
done
cp "$SRC/mobile/fastlane/metadata/android/en-US/images/icon.png" icon.png  # copied into repo/icons/ by fdroid

# Every APK from every published (non-draft, non-prerelease) release.
auth=()
[ -n "${GITHUB_TOKEN:-}" ] && auth=(-H "Authorization: Bearer $GITHUB_TOKEN")
curl -sSfL "${auth[@]}" "https://api.github.com/repos/$GH_REPO/releases?per_page=100" \
  | python3 -c '
import json, sys
for r in json.load(sys.stdin):
    if r["draft"] or r["prerelease"]:
        continue
    for a in r["assets"]:
        if a["name"].endswith(".apk"):
            print(a["browser_download_url"])
' | while read -r url; do
  echo "Downloading $url"
  curl -sSfL -o "repo/$(basename "$url")" "$url"
done
ls repo/*.apk >/dev/null

cat > config.yml <<EOF
repo_url: https://ivanleb.github.io/SaunaTimer/repo
repo_name: Sauna Pulse
repo_icon: icon.png
repo_description: >-
  Official repository of Sauna Pulse, a sauna session tracker for Wear OS watches with a companion
  phone app. Releases appear here as soon as they are published on GitHub. Source code:
  https://github.com/$GH_REPO
archive_older: 0
keystore: $REPO_KEYSTORE
repo_keyalias: $REPO_KEY_ALIAS
keystorepass: {env: REPO_KEYSTORE_PASS}
keypass: {env: REPO_KEYSTORE_PASS}
EOF
# Only for local runs without an Android SDK; the GitHub runner has one.
[ -n "${FDROID_APKSIGNER:-}" ] && echo "apksigner: $FDROID_APKSIGNER" >> config.yml
chmod 600 config.yml

fdroid update --verbose

# Publish only the repo directory, plus a landing page at the site root.
mkdir site
cp -r repo site/repo
cp "$SRC/fdroid/repo/index.html" site/index.html
echo "Site ready in $OUT/site"
