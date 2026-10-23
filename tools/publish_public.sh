#!/bin/bash
# Publishes the current main to the public repo (remote "public", github.com/ed7fanboy/f650gs) as
# one new commit, plus the landing page (gh-pages). The private repo (origin, f650gs-dev) keeps
# the full history; the public one gets one commit per release.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -z "$(git status --porcelain)" ] || { echo "Commit your changes first."; exit 1; }
[ "$(git branch --show-current)" = main ] || { echo "Run on main."; exit 1; }
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
git fetch -q public 2>/dev/null || true
parent=$(git rev-parse -q --verify refs/remotes/public/main || true)
if [ -n "$parent" ] && [ "$(git rev-parse "$parent^{tree}")" = "$(git rev-parse HEAD^{tree})" ]; then
  echo "Public main already has these files."
else
  commit=$(git commit-tree "HEAD^{tree}" ${parent:+-p "$parent"} -m "f650gs.pl $version")
  git push public "$commit:refs/heads/main"
fi
git push public gh-pages
