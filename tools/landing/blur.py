"""Blurs member content in an app screenshot: any text that is not one of the app's own labels
(string literals in the Kotlin source), plus photos and avatars. Paints a plain status bar.
usage: blur.py <source dir> <screen.png> <screen.xml> <out.png>"""
import os, re, sys, pathlib, xml.etree.ElementTree as ET
from PIL import Image, ImageFilter, ImageDraw, ImageFont

src, png, xmlf, out = sys.argv[1:5]
STATUS = 96
# App labels: Kotlin string literals; templates ($x, ${...}) match anything.
patterns = []
for f in pathlib.Path(src).rglob("*.kt"):
    for lit in re.findall(r'"((?:[^"\\\n]|\\.)*)"', f.read_text(encoding="utf-8")):
        if len(lit.strip()) < 2 or not re.search(r"[A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ]", lit):
            continue
        parts = re.split(r"\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_.]*", lit.replace('\\"', '"'))
        # A label must have words of its own; one made only of placeholders would match anything.
        if len(re.sub(r"[^A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ]", "", "".join(parts))) < 3:
            continue
        patterns.append(re.compile(".*?".join(re.escape(p) for p in parts), re.S))
# Labels of the forum's own menus, shown by the app as they come.
FORUM_UI = {"Moje załączniki", "Obserwowana zawartość", "Ustawienia konta", "Ignorowani użytkownicy"}
# Labels of forum forms the app draws natively, from the anonymised test pages (no member posts in them).
from html.parser import HTMLParser
class _Texts(HTMLParser):
    def __init__(self):
        super().__init__(); self.skip = 0
    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style"): self.skip += 1
        for name, value in attrs:
            if name == "placeholder" and value: FORUM_UI.add(value.strip())
    def handle_endtag(self, tag):
        if tag in ("script", "style"): self.skip -= 1
    def handle_data(self, data):
        if not self.skip and data.strip(): FORUM_UI.add(re.sub(r"\s+", " ", data).strip().rstrip(":").strip())
pages = pathlib.Path(src).parent.parent / "test/resources/pages"
for name in ("ignore.html", "settings.html", "settings_email.html", "settings_password.html", "notification_options.html", "register.html"):
    if (pages / name).exists(): _Texts().feed((pages / name).read_text(encoding="utf-8"))
FORUM_UI -= {"user@example.com", "member"}
FORUM_LOWER = {t.lower() for t in FORUM_UI}
def own(text):
    t = text.strip()
    bare = re.sub(r"\s*\*$", "", t).rstrip(":").strip()
    if not re.search(r"[A-Za-z0-9ąćęłńóśźżĄĆĘŁŃÓŚŹŻ]", t) or bare in FORUM_UI or (t.isupper() and bare.lower() in FORUM_LOWER):
        return True
    # Section headings are shown in capitals.
    return any(p.fullmatch(t) or (t.isupper() and p.fullmatch(t.capitalize())) for p in patterns) or \
        (t.isupper() and any(p.pattern.lower() == re.escape(t).lower() for p in patterns))

img = Image.open(png).convert("RGB")
W, H = img.size
boxes = []
nodes = list(ET.parse(xmlf).iter("node"))
# The bottom tab bar (its labels are app text): icons there stay sharp.
TABS = {"Ostatnie", "Fora", "Nieprzecz.", "Chatbox", "Wiadomości", "Zapisane"}
tab_tops = [int(re.findall(r"\d+", n.get("bounds"))[1]) for n in nodes if (n.get("text") or "") in TABS]
nav_top = (min(tab_tops) - 130) if tab_tops else H
for node in nodes:
    if node.get("package") != "pl.f650gs.forum":
        continue
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    if x2 - x1 < 4 or y2 - y1 < 4 or y2 <= STATUS:
        continue
    text = node.get("text") or ""
    cls = node.get("class") or ""
    if text and not own(text):
        boxes.append((x1, y1, x2, y2, "text"))
    elif cls in ("android.widget.ImageView", "android.widget.Image") and x2 - x1 >= 100 and y2 - y1 >= 40 and \
            (not node.get("content-desc") or node.get("content-desc") == "Konto i wylogowanie") and y1 < nav_top:
        boxes.append((x1, y1, x2, y2, "image"))
if os.environ.get("NOBLUR"):  # demo content: nothing of members on screen
    boxes = []
for x1, y1, x2, y2, kind in boxes:
    pad = 6
    box = (max(0, x1 - pad), max(STATUS, y1 - pad), min(W, x2 + pad), min(H, y2 + pad))
    region = img.crop(box)
    radius = 28 if kind == "image" else max(14, (y2 - y1) // 3)
    img.paste(region.filter(ImageFilter.GaussianBlur(radius)), box)
# The account button shows the member's photo on every screen.
ACCOUNT = (946, 106, 1066, 226)
if not os.environ.get("NOACCOUNT"):  # screens without the top bar's account photo (photo preview)
    img.paste(img.crop(ACCOUNT).filter(ImageFilter.GaussianBlur(28)), ACCOUNT)
# A plain status bar: the bar's own colour and 12:30.
color = img.getpixel((W // 2, STATUS + 4))
draw = ImageDraw.Draw(img)
draw.rectangle((0, 0, W, STATUS), fill=color)
font = ImageFont.truetype("/usr/share/fonts/TTF/DejaVuSans-Bold.ttf", 40) if pathlib.Path("/usr/share/fonts/TTF/DejaVuSans-Bold.ttf").exists() else ImageFont.load_default()
light = sum(color) > 380
draw.text((44, 26), "12:30", fill=(30, 30, 30) if light else (235, 235, 235), font=font)
img.save(out, optimize=True)
print(out, len(boxes), "blurred")
