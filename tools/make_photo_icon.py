"""Launcher icon from the forum banner: the stencil biker on its sand-and-sky background in a
cut to the Samsung squircle with a 4px black border inside its edge, "f650gs.pl" on a yellow strip at the bottom.
Output: 432x432 adaptive-icon foreground (108dp at xxxhdpi), sized to the launcher mask.

Banner: http://f650gs.pl/uploads/monthly_2018_05/3_forum_header_08.jpg.9df88d49936bbbfab99c0605ff5e929b.jpg.aed1fc28620e8ca6acc9717fa681903a.jpg
Usage:  python3 tools/make_photo_icon.py banner.jpg app/src/main/res/drawable-nodpi/ic_launcher_foreground.png
"""
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

banner, out = sys.argv[1], sys.argv[2]
SIZE, UP = 432, 4
src = Image.open(banner).convert("RGB")
crop = src.crop((6, 4, 206, 200))                      # left part, without the BMW roundel
big = crop.resize((crop.width * UP, crop.height * UP), Image.LANCZOS)

# Crisp stencil: near-black and near-white, low-saturation pixels become pure black / white.
a = np.asarray(big).astype(float)
mx, mn = a.max(axis=2), a.min(axis=2)
sat = (mx - mn) / np.maximum(mx, 1)
lum = a @ [0.299, 0.587, 0.114]
dark = lum < 95
white = (lum > 200) & (sat < 0.12)
res = a.copy()
res[dark] = 17
res[white] = 255
img = Image.fromarray(res.clip(0, 255).astype("uint8")).filter(ImageFilter.SMOOTH)

# Rounded-square badge around the biker; the right edge stops before the BMW roundel.
VISIBLE = 238
art = img.crop((6 * UP, 10 * UP, 197 * UP, 194 * UP))
pic = art.resize((VISIBLE, VISIBLE), Image.LANCZOS)
# Around the picture: a sky-to-sand gradient taken from the picture's own top and bottom rows, and the
# picture's edges fade into it, so the space up to the squircle border looks like more of the same scene.
p = np.asarray(pic).astype(float)
top_half, bottom = p[: VISIBLE // 2].reshape(-1, 3), p[-40:].reshape(-1, 3)
sky = top_half[(top_half[:, 2] > top_half[:, 0] + 25) & (top_half[:, 2] > 150)].mean(axis=0)            # blue pixels
sand = bottom[(bottom[:, 0] > bottom[:, 2] + 20) & (bottom[:, 0] > 150)].mean(axis=0)                   # sand pixels
t = np.linspace(0, 1, SIZE)[:, None, None]
grad = (sky * (1 - t) + sand * t).repeat(SIZE, axis=1)
canvas = Image.fromarray(grad.round().astype("uint8"), "RGB").convert("RGBA")
c = SIZE // 2
left = top = c - VISIBLE // 2
fade = Image.new("L", (VISIBLE, VISIBLE), 0)
ImageDraw.Draw(fade).rounded_rectangle((10, 10, VISIBLE - 11, VISIBLE - 11), radius=30, fill=255)
canvas.paste(pic, (left, top), fade.filter(ImageFilter.GaussianBlur(8)))
draw = ImageDraw.Draw(canvas)

# "f650gs.pl" on a black-outlined yellow strip at the bottom of the visible area.
font = ImageFont.truetype("/usr/share/fonts/TTF/FiraSansCondensed-ExtraBold.ttf", 38)
text = "f650gs.pl"
box = draw.textbbox((0, 0), text, font=font)
tw, th = box[2] - box[0], box[3] - box[1]
pad_x, pad_y = 12, 8
strip_h = th + 2 * pad_y + 4
strip_top = top + VISIBLE - strip_h - 6
strip = (c - tw // 2 - pad_x, strip_top, c + tw // 2 + pad_x, strip_top + strip_h)
draw.rounded_rectangle(strip, radius=14, fill=(255, 212, 59, 255), outline=(17, 17, 17, 255), width=7)
draw.text((c - tw // 2 - box[0], strip_top + pad_y + 2 - box[1]), text, font=font, fill=(17, 17, 17, 255))
# Samsung squircle with a black border inside its edge. One UI's launcher mask, measured on the S25 Ultra with
# a test pattern, is a superellipse |x|^2.6 + |y|^2.6 = 1 filling the middle 72dp (288px; measured 144 +-3px from the centre); the icon's own
# squircle is 2px smaller so the launcher never trims the border. Border: 4 screen px = 7px here.
SQ_N, SQ_HALF, SQ_BORDER = 2.6, 142, 7
yy, xx = np.mgrid[0:SIZE, 0:SIZE] + 0.5
r = (np.abs((xx - c) / SQ_HALF) ** SQ_N + np.abs((yy - c) / SQ_HALF) ** SQ_N) ** (1 / SQ_N)
inner = (np.abs((xx - c) / (SQ_HALF - SQ_BORDER)) ** SQ_N + np.abs((yy - c) / (SQ_HALF - SQ_BORDER)) ** SQ_N) ** (1 / SQ_N)
# Edge softening over about one pixel, for both the outer edge and the border's inner edge.
outer_alpha = np.clip((1 - r) * SQ_HALF + 0.5, 0, 1)
border_mix = np.clip((inner - 1) * (SQ_HALF - SQ_BORDER) + 0.5, 0, 1)
px = np.asarray(canvas.convert("RGB")).astype(float)
px = px * (1 - border_mix[..., None]) + np.array([17, 17, 17]) * border_mix[..., None]
rgba = np.dstack([px, outer_alpha * 255]).round().astype("uint8")
Image.fromarray(rgba, "RGBA").save(out)
print("picture", (left, top, left + VISIBLE, top + VISIBLE), "strip", strip)
