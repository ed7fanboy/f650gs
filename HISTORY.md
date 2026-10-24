# Project history

> One section per minor version; patch releases are folded into their version. The version in use is `versionName` in `app/build.gradle.kts`.

## Before 0.7 (undated)

- Started from a simple WebView wrapper, to grow into a Tapatalk-style app without Tapatalk's drawbacks, on top of the existing forum, its login and posting.
- Native reader for activity, sections, topics, posts, search and locally saved topics; login, replies and new topics through the forum's forms; the unread stream; private messages and the Chatbox in their own areas.
- System, light or dark theme with a BMW-blue accent in the light one; forum pages inside the app styled to match.
- Posts keep paragraphs, quotes and author names readable; images open in a zoomable preview; YouTube embeds become an "Otwórz film na YouTube" link.
- Back returns to the list a topic was opened from; navigation state survives.

## 0.7 (2026-10-22)

- Native Chatbox (bubbles, emoticons, older messages, refresh every 10 s) through the forum chat's AJAX API.
- "Lubię to" under posts with the count and who liked; reaction/follow entries no longer repeat posts in Activity and Unread.
- Native photo preview with pinch and double-tap zoom.
- New debug signing key (the old one was lost): one reinstall and new login.

## 0.8–0.9 (2026-10-22)

- Recent activity as one card per topic; tapping opens the first unread post.
- Topic paging, a floating "scroll to bottom" button, native private messages.
- Native replies with "Cytuj"; after sending, the app jumps to the new post.
- Forum avatars with coloured initials as a fallback; Material bottom bar that hides while typing; "Ukrywaj stopki" hides mobile-app footers.

## 0.10 (2026-10-22)

- Native login and account menu; settings screen with system notifications (about every 15 min), tab order and visibility; red counters.
- Editing your own posts in the app (quotes and photos left untouched); pull down to refresh; search grouped by topic.

## 0.11 (2026-10-22)

- Native member profiles and new private messages with member search.

## 0.12 (2026-10-22/23)

- Parser tests on saved, anonymised forum pages; lists show the last saved copy at once; member photos cached on disk.
- Code split by screen; parsing (`ForumParser`) separate from fetching (`ForumRepository`); `-Pdev` builds a side-by-side test copy.
- App name "f650gs.pl" and the rider icon in Samsung's squircle (`tools/make_photo_icon.py`).
- Native new-topic form, native sections (subsections, sorting, paging, a floating "+"), forum links in posts open natively, first/last page buttons.
- Post numbers count through the whole topic; bare links no longer show "&amp;".
- Multi-word search works (spaces are sent as `%20`, not `+`).
- Fixes: a slow page no longer draws over a newer screen; light status bar on Android 15+.

## 0.13–0.14 (2026-10-23)

- Photos in posts through the member's Google Photos album ("f650gs.pl zdjęcia", shared by link once; the app finds each photo's address on the album page by its SHA-1). Up to 10 at once, placed at the cursor and moved or deleted like a letter, also when editing a post.

## 0.15 (2026-10-23)

- Bottom bar order by dragging a row's ≡ handle; cards show a "2 zdjęcia" tile when a post has photos.
- Galaxy S9 (320 dp, Android 10) fixes: all tabs fit, the tab bar hides while typing, the topic pager stays on one line.

## 0.16 (2026-10-23)

- Photos fill the post's width; runs of photos become a sideways-swipe strip with a "2/4" counter.
- Top bar: search, notifications bell and the member's photo, which opens the Konto screen (profile, followed content, attachments, settings, logout); gear and refresh buttons removed.
- Native notifications and notification settings; settings split into "Aplikacja" and "Forum" tabs; native sign-up.
- Any other forum page is drawn natively from its notes, lists and forms (WebView only for the post editor and uploads); one-tap actions answer the forum's confirm step; member-name boxes suggest members.
- Ostatnie: one card per topic id, previewing the earliest entry not by you.

## 0.17 (2026-10-23)

- Photo preview works like a gallery: swipe sideways for the next or previous photo of the post ("Zdjęcie 2 z 4"); photos in posts are stacked again, as the sideways strip clashed with the back gesture.
- Download button in the photo preview: saves the original file to Pictures/f650gs.pl.
- Emoji from the Android keyboard: the forum stores 3-byte text only, so posts with emoji did not go through; they now become the forum's emoticons in posts and ASCII codes in titles and the Chatbox, the rest is dropped.
- Saved test pages anonymised (names, titles, texts, ids, files), also in the published history.
- 0.17.1: photos ask Google for one permission only, `photoslibrary.appendonly` (add new photos, create the app's album); the unused read permission is gone.

## 0.18 (2026-10-23)

- Update check: once a day the app reads `version.json` on the landing page; a newer version brings up "Instaluj / Anuluj", the APK is downloaded, checked against its SHA-256 and handed to Android's installer (settings: "Sprawdź aktualizacje").
- Moje załączniki and Obserwowana zawartość are native: thumbnails, the post each attachment is in, further pages; attachments open in the photo preview.
- Switches have clearer on/off contrast in both themes; locked ones look faded.
- 0.18.1: no more double posts. Administrators' header notice ("a new IPS Community Suite version is available") was read as a failed send, and a second tap on Wyślij while sending posted again; both are fixed (header notices are ignored everywhere, the send box takes one tap at a time).
- 0.18.2: icons for the moderator and admin panels; report pages in moderation follow the dark theme; opening the admin panel no longer signs the app out, and a still-valid session is restored on start.

## 0.19 (2026-10-24)

- Chatbox tab shows a red dot when someone else wrote since the last message seen in the app.
- Dark theme switches: light track with a dark thumb when on, opaque grey when off, so the two states are easy to tell apart.
- Pasting an image address (jpg, png, gif, webp, avif) or a YouTube link into a post or message inserts it as a photo or an embedded film (480 x 270, like most films on the forum).
- Ostatnie previews the first post you have not read (the forum's unread stream), or the latest one when all are read, with "+N" counting only the posts after it.
- Mentions: Tapatalk's `[mention]` tags show as links to the member's profile; typing `@` and three letters suggests members, people in the topic first, and sends a forum mention that notifies them.
- Unsent text is kept per topic, conversation and Chatbox, also after the app is closed (photos, films and mentions included).
- Back and tab switches open the screen at the top instead of at the previous screen's scroll position.
- 0.19.1: YouTube films in posts show as a card with the thumbnail and title (from the forum, else YouTube); "Pełny ekran" above the reply box while typing hides the posts and gives the whole screen to the text (Zwiń or Back returns).

## 0.20 (2026-10-24)

- Staff view: member links in topics open the native profile for administrators and moderators too (the forum adds a `wr=` parameter to them for staff).
- Moderating posts: a checkbox on each post the account may moderate; with posts ticked, a bar offers the actions all of them allow (Połącz, Ukryj/Odkryj, Usuń, Zatwierdź), with a confirmation before deleting or merging. Splitting stays on the forum page.
- Moderating topics: "Moderuj temat…" under the title lists the forum's own staff menu with icons (Promuj, Podepnij, Ukryj, Zamknij, Przenieś, Połącz, Usuń, and their undo forms on a promoted, pinned, hidden or closed topic), plus "Na wysypisko", a one-tap move to Zaplecze → Wysypisko.

## Known limitations

- The forum is served over HTTP, so traffic, including login and the session, is not encrypted.
- Unread content, the Chatbox and some actions depend on being logged in and on the account's permissions.
- The parser relies on the forum's HTML and may need changes after the forum's pages are updated.
- YouTube videos open outside the app.
- Photos depend on Google Photos: reading the shared album page is unofficial, and until Google verifies the app only listed test users can sign in.
