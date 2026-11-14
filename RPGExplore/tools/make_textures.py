"""Draws the 16x16 textures for coins, ambrosia, potions, the ring, the boss token and red quartz.
Usage: make_textures.py <rpgcore-resources-dir> <rpgexplore-resources-dir> [preview.png]"""
import sys, os, math, random
from PIL import Image

def canvas(): return Image.new("RGBA", (16, 16), (0, 0, 0, 0))
def px(img, x, y, c):
    if 0 <= x < 16 and 0 <= y < 16: img.putpixel((x, y), c + (255,) if len(c) == 3 else c)

def coin(c_light, c_mid, c_dark, c_out, mark):
    img = canvas()
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            if d <= 6.6: px(img, x, y, c_out)
            if d <= 5.6:
                shade = c_light if (x + y) < 13 else (c_mid if (x + y) < 17 else c_dark)
                px(img, x, y, shade)
    for x, y in mark: px(img, x, y, c_dark)
    px(img, 5, 4, (255, 255, 230)); px(img, 4, 5, (255, 255, 230))
    return img

def ambrosia():
    img = canvas()
    gold = [(255, 236, 150), (242, 211, 107), (201, 160, 52), (120, 84, 20)]
    shape = ["......2.......",]
    pts = []
    for y in range(16):
        for x in range(16):
            # a glowing droplet / crystal shape
            dx, dy = x - 7.5, y - 9
            if dy < -6: continue
            r = 4.8 if dy >= 0 else 4.8 * (1 + dy / 7.0)
            if abs(dx) <= r and math.hypot(dx, max(dy, 0)) <= 4.9:
                pts.append((x, y))
    for x, y in pts:
        edge = any((x + a, y + b) not in pts for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        c = gold[3] if edge else (gold[0] if x < 7 and y < 9 else gold[1] if x < 9 else gold[2])
        px(img, x, y, c)
    px(img, 6, 6, (255, 255, 240)); px(img, 6, 7, (255, 255, 240))
    return img

def potion(liq_light, liq_dark):
    img = canvas()
    glass, out, cork = (220, 235, 245), (40, 40, 55), (150, 105, 60)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 10)
            if y >= 5 and d <= 5.5: px(img, x, y, out)
            if y >= 5 and d <= 4.5: px(img, x, y, liq_light if (x < 8 and y < 11) else liq_dark)
    for y in range(2, 6):
        for x in range(6, 10): px(img, x, y, out if x in (6, 9) else glass)
    for x in range(6, 10): px(img, x, 1, cork); px(img, x, 2, cork)
    px(img, 5, 8, (255, 255, 255)); px(img, 5, 9, (255, 255, 255))
    return img

def ring():
    img = canvas()
    band = [(250, 230, 150), (215, 180, 80), (150, 110, 40)]
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, (y - 9) * 1.15)
            if 3.2 <= d <= 5.4: px(img, x, y, band[0] if y < 9 and x < 8 else band[1] if y < 11 else band[2])
    for x, y in [(7, 2), (8, 2), (6, 3), (7, 3), (8, 3), (9, 3), (7, 4), (8, 4)]:
        px(img, x, y, (80, 200, 255) if (x + y) % 2 else (40, 120, 220))
    px(img, 7, 3, (220, 250, 255))
    return img

def token():
    img = coin((255, 200, 120), (230, 140, 60), (150, 70, 30), (60, 25, 10), [])
    for x, y in [(6, 5), (6, 6), (6, 7), (6, 8), (6, 9), (6, 10), (9, 5), (9, 6), (9, 7), (9, 8), (9, 9), (9, 10), (7, 7), (8, 7)]:
        px(img, x, y, (90, 30, 10))  # "H"
    return img

def red_quartz():
    random.seed(4)
    img = canvas()
    base = [(40, 18, 22), (58, 26, 32)]
    for y in range(16):
        for x in range(16): px(img, x, y, base[(x * 7 + y * 3 + random.randint(0, 2)) % 2])
    # crystals: bright red shards
    for cx, cy, h in [(4, 12, 7), (9, 13, 9), (13, 11, 5), (6, 6, 4), (12, 5, 4)]:
        for i in range(h):
            y = cy - i
            w = 1 if i > h - 3 else 2
            for dx in range(-w + 1, w):
                c = (255, 120, 135) if dx < 0 or i > h - 2 else (224, 64, 90) if i % 2 else (186, 30, 60)
                px(img, cx + dx, y, c)
        px(img, cx, cy - h + 1, (255, 200, 210))
    return img

core_dir, explore_dir = sys.argv[1], sys.argv[2]
copper = coin((240, 160, 110), (217, 136, 74), (150, 80, 40), (70, 35, 15),
              [(7, 5), (8, 5), (6, 6), (6, 7), (6, 8), (6, 9), (7, 10), (8, 10), (9, 6), (9, 9)])  # "C"
out = {
    os.path.join(core_dir, "textures/item/copper_coin.png"): copper,
    os.path.join(core_dir, "textures/item/ambrosia.png"): ambrosia(),
    os.path.join(core_dir, "textures/block/red_quartz_crystal.png"): red_quartz(),
    os.path.join(explore_dir, "textures/small_health_potion.png"): potion((240, 90, 100), (190, 40, 60)),
    os.path.join(explore_dir, "textures/healing_potion.png"): potion((255, 70, 110), (160, 10, 50)),
    os.path.join(explore_dir, "textures/travelers_ring.png"): ring(),
    os.path.join(explore_dir, "textures/hendors_token.png"): token(),
}
for path, img in out.items():
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
if len(sys.argv) > 3:
    prev = Image.new("RGBA", (len(out) * 170, 160), (190, 190, 190, 255))
    for i, img in enumerate(out.values()):
        big = img.resize((160, 160), Image.NEAREST)
        prev.paste(big, (i * 170, 0), big)
    prev.save(sys.argv[3])
