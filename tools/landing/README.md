# Landing-page screenshots

The page itself lives on the `gh-pages` branch (served by GitHub Pages, with `f650gs.apk` next to it).

1. Phone on USB, app signed in, dark theme. `source tools/landing/lib.sh`
2. Per screen: navigate with `home`, `tap "<text or icon description>"`, `taptext "<visible text>"`, then `shot <name>`. Raw captures go to `build/landing/raw`, finished ones to `build/landing/out`.
3. `blur.py` keeps the app's own labels (string literals in the Kotlin source) and the forum's form labels (from the anonymised test pages) sharp and blurs everything else members wrote, plus photos and avatars; it paints a plain 12:30 status bar. `NOBLUR=1 shot …` only paints the status bar and the account photo (for demo content); add `NOACCOUNT=1` on screens without the account photo (photo preview).
4. The topic and gallery screenshots use demo content (invented members, generated landscape pictures served to the phone with `python3 -m http.server 8765` + `adb reverse tcp:8765 tcp:8765`) through a temporary patch in `renderTopic` that is never committed.
5. Convert to 720 px WebP and copy into `img/` on `gh-pages`, with the new `f650gs.apk` and its SHA-256 in `index.html`.
