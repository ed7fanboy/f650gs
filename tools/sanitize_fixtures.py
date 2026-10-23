"""Turns saved forum pages into test fixtures safe for a public repo.
All pages: session tokens (32-hex) zeroed, inline styles/scripts trimmed except the ipsSettings
member line. Private-message pages: every visible text in the main area and every member name
replaced, profile slugs rewritten. Pages with members' content (MEMBER): names become consistent
pseudonyms across all pages ("Członek N", the signed-in member "Ja"), topic titles "Temat N",
post/snippet/signature/profile-field text "Tekst N", member ids, topic slugs and uploaded files
rewritten; the forum's own structure (sections, labels, pagers, dates, counts) is kept."""
import re, sys, pathlib
from lxml import html as LH

src, dst = map(pathlib.Path, sys.argv[1:3]); dst.mkdir(exist_ok=True)
PRIVATE = {"messenger.html", "conv.html"}
MEMBER = {"discover_in.html", "topic_in.html", "mytopic.html", "profile_in.html", "compose.html",
          "index.html", "forum.html", "forum_parent.html", "search.html"}
# Shared by all MEMBER pages, so one member or topic gets the same pseudonym everywhere.
NAMES, IDS, TOPICS, TITLES, FILES = {}, {}, {}, {}, {}
SELF = set()


def strip_scripts(doc):
    for el in doc.xpath("//style"):
        el.getparent().remove(el)
    for el in doc.xpath("//script"):
        body = el.text or ""
        m = re.search(r"memberID:\s*\d+", body)
        if el.get("src"):
            el.getparent().remove(el)
        elif m:
            el.text = "ipsSettings = { " + m.group(0) + " };"
        else:
            el.getparent().remove(el)


def member_names(doc):
    """Real member names on a page: profile links, photos, quotes, the signed-in header."""
    found = set()
    for a in doc.xpath("//a[contains(@href, '/profile/')]"):
        if re.search(r"/profile/\d+-[^/?#]+/?$", a.get("href", "")):
            t = a.text_content().strip()
            if t and len(t) <= 40:
                found.add(t)
        m = re.match(r"Przejdź do profilu (.+)", a.get("title", ""))
        if m:
            found.add(m.group(1).strip())
    for img in doc.xpath("//*[contains(@class, 'ipsUserPhoto')]//img[@alt]"):
        found.add(img.get("alt").strip())
    for el in doc.xpath("//*[@data-ipsquote-username] | //*[@data-username]"):
        found.add((el.get("data-ipsquote-username") or el.get("data-username")).strip())
    for el in doc.xpath("//*[@data-quotedata]"):
        found |= set(re.findall(r'"username":"([^"]+)"', el.get("data-quotedata")))
    for el in doc.xpath("//*[@data-role='ignoreRowName'] | //h1[contains(@class, 'ipsType_reset')]"):
        if el.text_content().strip():
            found.add(el.text_content().strip())
    me = doc.xpath('//*[@id="elUserLink"]')
    if me:
        SELF.add(me[0].text_content().strip())
    return {n for n in found if len(n) >= 2}


def pseudonym(name):
    if name not in NAMES:
        NAMES[name] = "Ja" if name in SELF else f"Członek {sum(1 for v in NAMES.values() if v != 'Ja') + 1}"
    return NAMES[name]


def rewrite_ids(text):
    def profile(m):
        if m.group(1) not in IDS:
            IDS[m.group(1)] = "900" if m.group(2) and m.group(2) in SELF_SLUGS else str(901 + len(IDS))
        return f"/profile/{IDS[m.group(1)]}-czlonek-{IDS[m.group(1)]}"
    text = re.sub(r"/profile/(\d+)-([^/\"'?#\s]+)", profile, text)
    text = re.sub(r"memberID:\s*(\d+)", lambda m: "memberID: " + ("0" if m.group(1) == "0" else "900"), text)
    def topic(m):
        TOPICS.setdefault(m.group(1), len(TOPICS) + 1)
        return f"/topic/{m.group(1)}-temat-{TOPICS[m.group(1)]}"
    text = re.sub(r"/topic/(\d+)-[^/\"'?#\s]+", topic, text)
    def upload(m):
        FILES.setdefault(m.group(1), len(FILES) + 1)
        ext = (m.group(1).rsplit(".", 1)[-1] if "." in m.group(1) else "jpg").lower()[:4]
        return f"uploads/file-{FILES[m.group(1)]}.{ext}"
    text = re.sub(r"uploads/([^\"'\s)&;]+)", upload, text)
    tags = {}
    def tag(m):
        tags.setdefault(m.group(1), len(tags) + 1)
        return f"/tags/tag-{tags[m.group(1)]}/"
    return re.sub(r"/tags/([^/\"'?#\s]+)/", tag, text)


def blank(el, label, counter):
    """Every visible text inside [el] becomes "<label> N"; elements (images, quotes, links) stay."""
    for node in el.iter():
        if not isinstance(node.tag, str):
            continue
        if node.text and node.text.strip():
            counter[0] += 1; node.text = f"{label} {counter[0]}"
        if node is not el and node.tail and node.tail.strip():
            counter[0] += 1; node.tail = f" {label.lower()} {counter[0]} "


def replace_everywhere(doc, mapping):
    """Replaces whole-word occurrences of the keys in all texts and attributes, longest first."""
    if not mapping:
        return
    pattern = re.compile(r"(?<![\w])(" + "|".join(re.escape(k) for k in sorted(mapping, key=len, reverse=True)) + r")(?![\w])")
    swap = lambda s: pattern.sub(lambda m: mapping[m.group(1)], s)
    for el in doc.iter():
        if not isinstance(el.tag, str):
            continue
        if el.text: el.text = swap(el.text)
        if el.tail: el.tail = swap(el.tail)
        for k, v in list(el.attrib.items()):
            if k not in ("href", "src", "class", "id") and v:
                el.set(k, swap(v))


def anonymise(doc, counter):
    # Topic titles, from links to topics and a topic page's own heading.
    for a in doc.xpath("//a[contains(@href, '/topic/')]"):
        m = re.search(r"/topic/(\d+)-", a.get("href", ""))
        t = a.text_content().strip()
        if m and t and len(t) > 2 and not re.fullmatch(r"[\d\s«»‹›….]+|Strona.*|Następna.*|Poprzednia.*|Dalej|Pierwsza.*|Ostatnia.*", t):
            TITLES.setdefault(t, f"Temat {TOPICS.get(m.group(1), len(TITLES) + 1)}")
    page_title = doc.xpath("//h1[contains(@class, 'ipsType_pageTitle')]")
    if page_title and doc.xpath("//*[@data-role='commentContent']") and page_title[0].text_content().strip():
        TITLES.setdefault(page_title[0].text_content().strip(), f"Temat {len(TITLES) + 1}")
    # Members' own words.
    for el in doc.xpath("//*[@data-role='commentContent'] | //*[contains(@class, 'ipsStreamItem_snippet')]"
                        " | //*[@data-role='memberSignature'] | //*[contains(@class, 'cProfileFields')]//*[contains(@class, 'ipsContained')]"
                        " | //*[contains(@class, 'ipsType_richText')] | //*[contains(@class, 'cProfileHeader')]//*[contains(@class, 'ipsType_light')]"
                        # The author box (custom title, location, motorcycle, post count) and members' topic tags.
                        " | //*[contains(@class, 'cAuthorPane_info')] | //*[contains(@class, 'ipsTags')]"
                        # Profile values (title, website, custom fields) next to their bold labels.
                        " | //*[contains(@class, 'ipsDataItem_generic') and not(contains(@class, 'ipsDataItem_size3'))]"):
        blank(el, "Tekst", counter)
        for img in el.xpath(".//img"):
            if not (img.get("data-emoticon") is not None or "/emoticons/" in img.get("src", "")):
                img.set("src", "http://f650gs.pl/uploads/file-0.jpg")
                for k in ("srcset", "data-src", "data-fileid"):
                    img.attrib.pop(k, None)
        for frame in el.xpath(".//iframe"):
            frame.set("src", "https://www.youtube.com/embed/abc_DEF-1"); frame.set("title", "Film")
        for a in el.xpath(".//a[@href]"):
            if not re.match(r"https?://(www\.)?f650gs\.pl/", a.get("href")):
                a.set("href", "https://example.com/")
    for el in doc.xpath("//*[@data-quotedata]"):
        el.set("data-quotedata", "{}")
    # "Who reacted" lists are JSON with escaped names; the app does not read them.
    for el in doc.xpath("//*[@data-ipstooltip-label]"):
        el.set("data-ipstooltip-label", "[]")
    for a in doc.xpath("//a[contains(@class, 'ipsTag')]"):
        a.set("title", "Tag")
    for a in doc.xpath("//a[contains(@href, '/topic/')][@title]"):
        m = re.search(r"/topic/(\d+)-", a.get("href"))
        if m: a.set("title", f"Temat {TOPICS.get(m.group(1), 0)}")
    for el in doc.xpath("//*[@data-username]"):
        el.set("data-username", pseudonym(el.get("data-username").strip()))
    replace_everywhere(doc, TITLES)
    replace_everywhere(doc, {n: pseudonym(n) for n in NAMES})


files = sorted(src.glob("*.html"))
raw = {f.name: re.sub(r"[0-9a-f]{32}", "0" * 32, f.read_text(encoding="utf-8")) for f in files}
# First pass over all member pages: who is who, so pseudonyms match across pages.
for name in sorted(MEMBER & raw.keys()):
    for n in sorted(member_names(LH.fromstring(raw[name]))):  # sorted: the same pseudonyms on every run
        pseudonym(n)
SELF_SLUGS = {re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-") for s in SELF}
counter = [0]
for f in files:
    text = raw[f.name]
    if f.name in PRIVATE:
        ids = {}
        def remap(m):
            ids.setdefault(m.group(2), str(901 + len(ids)))
            return m.group(1) + ids[m.group(2)]
        # Member ids in profile links, hover cards, memberID settings and photo file names.
        text = re.sub(r"(/profile/|memberID:\s*|[?&;]id=|member_|photo-)(\d+)", remap, text)
    if f.name in MEMBER:
        text = rewrite_ids(text)
    doc = LH.fromstring(text)
    strip_scripts(doc)
    if f.name in MEMBER:
        anonymise(doc, counter)
    if f.name in PRIVATE:
        n = 0
        for main in doc.xpath('//*[@id="ipsLayout_mainArea"]') + doc.xpath("//title"):
            for node in main.iter():
                if not isinstance(node.tag, str) or node.tag in ("time",):
                    continue
                if node.text and node.text.strip():
                    n += 1; node.text = f"Tekst {n}"
                if node.tail and node.tail.strip() and node.getparent() is not None:
                    n += 1; node.tail = f" tekst {n} "
        for img in doc.xpath('//*[@id="ipsLayout_mainArea"]//img'):
            img.set("src", "http://f650gs.pl/uploads/member-photo.png")
        # Quote metadata carries real names and ids; the signed-in name also sits in the header.
        real = set(re.findall(r'"username":"([^"]+)"', text))
        real |= {t.strip() for t in doc.xpath('//*[@id="elUserLink"]//text()') if t.strip()}
        for el in doc.xpath('//*[@id="elUserLink"]'):
            for node in el.iter():
                node.text = "uczestnik" if node.text and node.text.strip() else node.text
                node.tail = " " if node.tail and node.tail.strip() else node.tail
        for el in doc.xpath("//*[@data-quotedata]"):
            el.set("data-quotedata", "{}")
        for el in doc.iter():
            if not isinstance(el.tag, str):
                continue
            for name in real:
                if el.text and name in el.text:
                    el.text = el.text.replace(name, "uczestnik")
                if el.tail and name in el.tail:
                    el.tail = el.tail.replace(name, "uczestnik")
        names = {}
        def anon(m):
            names.setdefault(m.group(1), f"member{len(names) + 1}")
            return f"/profile/{m.group(1)}-{names[m.group(1)]}/"
        for el in doc.iter():
            if not isinstance(el.tag, str):
                continue
            for k, v in list(el.attrib.items()):
                v2 = re.sub(r"/profile/(\d+)-[^/\"']+/", anon, v)
                if k in ("title", "alt", "data-ipsquote-username", "data-username", "aria-label") or k.startswith("data-ipstooltip"):
                    v2 = "Uczestnik"
                if v2 != v:
                    el.set(k, v2)
    out = LH.tostring(doc, encoding="unicode", doctype="<!DOCTYPE html>")
    (dst / f.name).write_text(out, encoding="utf-8")
    print(f.name, len(text), "->", len(out))
print(len(NAMES), "members,", len(TITLES), "topic titles,", len(FILES), "files renamed")
