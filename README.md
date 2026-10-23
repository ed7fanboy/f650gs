# f650gs.pl for Android

A lightweight native reader for [http://f650gs.pl/](http://f650gs.pl/). The app presents activity, forum sections, topics, posts, search results, and locally saved topics in native Android screens. It reads the forum's public HTML directly; it does not use Tapatalk or an app-specific tracking SDK.

## What it does

- Shows a native recent-activity feed and forum/category browser.
- Topic cards open at the first unread post (`?do=getNewComment`, redirects followed by hand to keep `#comment-ID`), outlined in orange; previous/next page buttons and a floating "scroll to bottom" button (jumps to the last page when needed).
- Native topic replies: pinned reply box under the topic, "Cytuj" per post (forum quote markup), posted through the topic's own comment form; then jumps to the new post (`?do=getLastComment`). Locked topics fall back to the forum page.
- Settings: theme, and "Ukrywaj stopki" (default on) that hides mobile-app footers such as "Wysłane z … przy użyciu Tapatalka". Empty paragraphs and repeated line breaks in posts are always collapsed.
- Native login screen (forum login form via HTTP POST; session cookies handed to the WebView) and the Konto screen (profile, followed content, attachments, app and forum settings, logout that also clears WebView cookies).
- Settings screen (Konto → Ustawienia; "Aplikacja" tab): theme, footer filter, system notifications (private messages / forum notifications / new unread topics, checked every ~15 min by `ForumCheckJob`, JobScheduler), tab bar visibility, and tab order by dragging a row's ≡ handle (`RowDrag` in `AccountScreens.kt`).
- Top bar: search, notifications bell and the member's photo, which opens the Konto screen (profile, followed content, attachments, app/forum settings, logout); pull down to refresh any screen.
- Ostatnie: one card per topic (grouped by topic id), previewing the topic's earliest entry in the stream that is not your own, with later ones summarised; tapping opens the first unread post.
- Red count badges: unread conversations on Wiadomości, unread topics on Nieprzecz., forum notifications on the bell (header counters from `/discover/unread/`).
- Native notifications (bell, signed in only): the forum's notification list with older pages, entries open the topic natively; native notification settings built from the forum's own form (switches save on their own).
- Other forum pages (account settings, e-mail/password/username change, ignored members, password reset, …) are drawn natively from their notes, lists and IPS forms by `FormPages.kt` (`ForumParser.nativePage`); pages with the post editor, uploads or unknown fields fall back to the WebView.
- Native sign-up: the forum's registration form rendered field by field (labels, anti-bot question, terms box), errors from the forum shown next to the fields.
- Post photos fill the post's width (tall ones capped at 3/4 of the screen). The photo preview swipes between the post's photos ("Zdjęcie 2 z 4") and its download button saves the original to Pictures/f650gs.pl (DownloadManager).
- Update check (`Updates.kt`): once a day reads `https://ed7fanboy.github.io/f650gs/version.json` (written by `tools/update_site.sh`); a newer version offers "Instaluj / Anuluj", downloads the APK, verifies its SHA-256 and installs it through `PackageInstaller` (needs `REQUEST_INSTALL_PACKAGES`).
- Emoji typed on Android become forum emoticons in posts and ASCII codes in titles and the Chatbox (`Emoji.kt`); the forum stores 3-byte UTF-8 only.
- "Edytuj" on own posts while the forum allows editing, in the reply box: quotes stay before the text and photos/attachments after it, unchanged; only the author's own text is edited.
- Search results grouped one card per topic.
- Picture tile on Recent/Unread cards for posts with photos ("2 zdjęcia"). The stream's snippets carry no trace of images, so `PostImages.kt` reads each card's post once from its topic page on a background thread and keeps the count per comment id (`post_images` preferences); `ForumParser.imageCount` skips emoticons, Tapatalk emoji and quoted images.
- Native member profiles: tap an author's name or photo (activity cards, topic posts, Chatbox, private messages, profile links in posts, the Konto screen). Shows photo, group, online status, forum stats, profile fields and recent activity grouped per topic, with a "Napisz wiadomość" button.
- Pull to refresh on every list (`PullRefreshScrollView`).
- Material-style tab bar (vector icons, tonal pill on the selected tab); every tab gets an equal share of the screen width, so six tabs fit on a 320 dp phone (labels end in "…"). Hides while the keyboard is open (IME inset on Android 11+, visible-frame height before that). Home header reads "F650GS.PL".
- Native private messages: conversation list, conversations as bubbles (photos as tappable links), reply from the pinned composer via the conversation's own comment form. New conversations are native too: pick a member by name (forum member search, min. 3 letters) or start from their profile; sent through the forum's `/messenger/compose/` form, then the new conversation opens.
- Lists (recent, forums, unread, messages) appear at once from the last saved copy while the fresh one loads (`ListCache.kt`, cleared on sign-in/out).
- Forum member photos as avatars (memory + disk cache, `Avatars.kt`), falling back to a letter badge coloured per member; orange accent for anything new; vector toolbar icons.
- Shows a signed-in unread-content stream and forum-provided mark-as-read actions.
- Puts the thread title first in activity and unread cards and opens the exact linked topic.
- Preserves the unread list as the back destination, including Android system back gestures.
- Marks unread topics in forum lists with a distinct label and card treatment.
- Searches the forum and opens topic pages in a native reader. Queries go out with spaces as `%20`: the forum returns nothing for a multi-word query encoded with `+`.
- Preserves post paragraphs and line breaks, displays images as compact thumbnails, opens them on tap, and styles quoted posts with theme-aware contrast.
- Wraps forum reply, new-conversation and login pages in a native Android toolbar, with a compact, theme-aware login form.
- Native Chatbox: message bubbles, Polish timestamps, emoticons as emoji, a pinned composer, older-message loading and 10-second polling, via the forum chatbox's own AJAX API (`bimchatbox` getMessages/chat, session csrfKey).
- Like button under each post (count, who liked, your own like highlighted); likes use the forum's own reactComment/unreactComment links.
- Hides stream action items (reactions, follows, sign-ups) from Recent/Unread, so a liked post no longer appears several times.
- Native sections: subsections, the forum's sort orders as chips, page-by-page browsing ("Strona X z N", « first / ‹ previous / next › / » last, same pager as topics); pages and sorts replace the screen without adding back steps.
- Photos in posts (topic replies, private messages, new topics, edits): 📷 opens the system picker (up to 10 photos). The forum server has no room for attachments, so photos go to the member's own Google Photos: the app uploads them into its album "f650gs.pl zdjęcia" (Photos Library API, scope `photoslibrary.appendonly`), the member shares that album by link once (the API can no longer share, since 2025-03-31; the app takes the copied link from the clipboard), and each photo's public `lh3.googleusercontent.com` address is read from the shared album page, matched by the file's SHA-1 (the fingerprint Google shows per photo). Photos sit inside the text as thumbnails (an `ImageSpan` on one character) at the cursor, so they move and delete like letters, and the post is built in that order; editing a post shows its existing photos and embeds the same way. Google sign-in needs the OAuth client in Google Cloud project `f650gs-photos` (package `pl.f650gs.forum` + the debug key's SHA-1; consent screen in Testing, so only listed test users). Reading the album page is unofficial and may break if Google changes it.
- Native new topics: the floating "+" in a section (shown when the forum lets you post there) opens a title + first-post form, sent through the section's own `?do=add` form (`topic_title`, `topic_content`); the new topic opens afterwards.
- Links to forum topics and sections inside posts open in the native reader (including `?do=findComment` jumps); other forum pages still open in the WebView.
- Narrows the reply composer, removes unused media insertion controls, and styles answer notifications as a theme-aware Android switch.
- Opens full image previews in a native viewer: pinch or double-tap to zoom, one-finger drag to pan.
- Launcher icon in the home-screen sticker style (flat colors, thick black outlines, no tile): two chat bubbles with an enduro bike, as a vector in `res/drawable/ic_launcher_foreground.xml`.
- Provides tabs for the forum Chatbox and private-message inbox.
- Saves topic links locally on the device.
- Offers persistent System, Light, and Dark appearance choices.
- Uses BMW blue for the light-theme accent and carries dark colors through forum forms and rich-text editor frames.
- Preserves YouTube embeds in posts as a clearly styled link that opens the video externally, without loading third-party iframe content in the reader.
- Signs in through the forum's own login page in a focused in-app WebView.
- Opens the forum's own reply editor when a topic offers no native reply form (e.g. locked).
- Shares the WebView's forum session with the native read-only screens.
- Removes social-platform share links from the in-app forum pages.
- Keeps cleartext network access scoped to `f650gs.pl` and its subdomains.
- Includes no Tapatalk SDK, ad SDK, analytics SDK, or app-added tracking library.

Reading, search, saved topics, and the unread stream are native. Reading the Chatbox and liking posts are native too. Pages the generic native renderer cannot draw use the forum's own pages inside a focused WebView, so the forum continues to handle its session, CSRF checks, permissions, and editor. Chatbox access depends on the member's forum permissions. The WebView blocks third-party hosts and third-party cookies. Forum HTML parsing can need updates if the site changes its page structure.

## Privacy and transport

The forum currently uses HTTP. Page content, searches, credentials, and session cookies are therefore not protected by TLS in transit; the login screen calls this out before the user enters credentials. The native reader does not execute forum-page JavaScript or load its third-party resources. The account-action WebView enables JavaScript for the forum's editor but blocks third-party hosts and cookies. The forum server still receives ordinary requests and IP address information. Credentials are not stored by the app; session cookies are managed by Android WebView.

## Build in Android Studio

1. Install Android Studio and the Android SDK Platform 37. Use JDK 17 or newer for Gradle.
2. Open this folder as a project in Android Studio.
3. The project uses Android Gradle Plugin 9.4.1 with its built-in Kotlin support, and Gradle 9.8.1. This source archive intentionally does not include the Gradle wrapper JAR, so use Android Studio's configured Gradle installation or generate wrapper files locally.
4. Sync the project, connect an Android device with USB debugging enabled (or start an emulator), and choose **Run**.
5. For an APK, choose **Build > Build APK(s)**. From the command line: `ANDROID_HOME=~/Android/Sdk ~/Android/gradle-9.8.1/bin/gradle assembleDebug`.

Publishing: this repository (private, `f650gs-dev`) keeps the full history; `tools/publish_public.sh` puts the current `main` on the public repository `github.com/ed7fanboy/f650gs` as one commit per release, together with the landing page (`gh-pages`, GitHub Pages).

Debug builds are signed with `~/.android/debug.keystore`. Versions up to 0.6.6 were signed with a different (lost) key, so 0.7.0 had to be installed after an uninstall; later builds update in place.

The app requires Android 7.0 (API 24) or newer. It does not store forum credentials. Signing in sends the login form directly; only the session cookies are kept.

## Project structure

Code in `app/src/main/java/pl/f650gs/forum/`:

- `MainActivity.kt` — app shell: toolbar, tab bar, navigation stack, screen switch, loading (`fetch`, `fetchCached`), counters
- `Navigation.kt` — `Screen` and `Tab`
- Screens, as extension functions of `MainActivity`: `ListScreens.kt` (recent, unread, forums, forum, saved, search), `TopicScreen.kt` (posts WebView, likes, quotes, replies, edits), `ChatScreens.kt` (Chatbox, messages, conversations, pinned composer), `ProfileScreens.kt` (profile, new message), `AccountScreens.kt` (login, Konto screen, settings), `NotificationScreens.kt` (notifications, notification settings, sign-up), `FormPages.kt` (any other forum page from its forms and lists)
- `UiKit.kt` — shared views (cards, pills, badges, labels), post text and footer filters, links out
- `ForumRepository.kt` — HTTP with the session cookies; `ForumParser.kt` — reads forum pages (no network, no Android classes); `ForumModels.kt` — data classes
- `ListCache.kt`, `Avatars.kt` — saved lists and member photos
- `ForumWebActivity.kt` — forum pages the native renderer cannot draw
- `ForumCheckJob.kt` — background check for system notifications; `ImagePreviewActivity.kt` — photo viewer

Test copy: `gradle assembleDebug -Pdev` builds "f650gs.pl dev" (`pl.f650gs.forum.dev`), which installs next to the regular app with its own login and cache.

The launcher icon is generated by `tools/make_photo_icon.py` from the forum banner (the stencil biker on the left, "f650gs.pl" strip) cut to the Samsung squircle measured on the phone (superellipse n=2.6, 72 dp) with a 4 px black border along its inside edge.

## Tests

`ANDROID_HOME=~/Android/Sdk ~/Android/gradle-9.8.1/bin/gradle testDebugUnitTest` runs the parser tests on saved forum pages (`app/src/test/resources/pages`) and the list cache tests. When the forum changes its pages, save fresh copies and run `tools/sanitize_fixtures.py <raw dir> app/src/test/resources/pages`: it zeroes session tokens, anonymises private-message pages, and on pages with members' content replaces names ("Członek N", the signed-in member "Ja"), topic titles, post/profile text, tags, member ids and uploaded files, keeping the forum's own structure. Saved pages often include content visible only to signed-in members, so never commit one without running it.
- `app/src/main/AndroidManifest.xml` — minimal network permission and app declaration
- `app/src/main/res/xml/network_security_config.xml` — forum-only cleartext exception
