#!/bin/bash
# Puts the freshly built APK on the landing page (gh-pages worktree ../forum_app-site): copies it,
# updates version, size and SHA-256 in index.html, writes version.json (read by the app's update
# check, Updates.kt), commits and pushes to the private repo. tools/publish_public.sh publishes it.
set -euo pipefail
cd "$(dirname "$0")/.."
site=../forum_app-site
apk=app/build/outputs/apk/debug/app-debug.apk
[ -f "$apk" ] || { echo "Build first: gradle assembleDebug"; exit 1; }
code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
name=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
cp "$apk" "$site/f650gs.apk"
sha=$(sha256sum "$site/f650gs.apk" | cut -d' ' -f1)
python3 - "$site" "$code" "$name" "$sha" <<'PY'
import json, os, re, sys
site, code, name, sha = sys.argv[1:]
size = f"{os.path.getsize(os.path.join(site, 'f650gs.apk')) / 1e6:.1f}".replace(".", ",")
page = os.path.join(site, "index.html")
html = open(page, encoding="utf-8").read()
html = re.sub(r'<code id="sha">[0-9a-f]{64}</code>', f'<code id="sha">{sha}</code>', html)
html = re.sub(r'(</span> )\d+\.\d+\.\d+ · [\d,]+ MB', rf'\g<1>{name} · {size} MB', html)
open(page, "w", encoding="utf-8").write(html)
json.dump({"versionCode": int(code), "versionName": name, "apk": "https://ed7fanboy.github.io/f650gs/f650gs.apk", "sha256": sha},
          open(os.path.join(site, "version.json"), "w"), indent=1)
print(f"site: {name} ({code}), {size} MB, sha {sha[:8]}…")
PY
git -C "$site" add -A
git -C "$site" commit -q -m "APK $name" || echo "Nothing new on the page."
git -C "$site" push -q origin gh-pages
