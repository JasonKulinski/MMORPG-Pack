"""
Builds the RPGCore resource pack: the stats book art, the fonts that write on it, and stat icons.
Run:  python make_pack.py      (needs Pillow: pip install pillow)
Output: ../resourcepack/ (folder) and ../RPGCore-pack.zip

To restyle: edit the colors / pixel maps below, or replace the PNGs in resourcepack/ with your own art
(keep the same sizes: book 320x200, icons 16x16). If you move text lines, keep StatsMenu.java in sync.
"""
import json, os, shutil, zipfile
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "resourcepack")
ZIP = os.path.join(HERE, "..", "RPGCore-pack.zip")
NS = os.path.join(OUT, "assets", "rpgcore")

# Minecraft 26.3 resource pack format (from the game's version.json: resource 97.1)
PACK_FORMAT = [97, 1]

# ---------------------------------------------------------------- stats book (open spellbook)
# Shown in a dialog screen (no inventory). Coordinates below are pixels from the book's top-left;
# StatsMenu.java uses the same numbers to place text.
BOOK_W, BOOK_H = 320, 200
LINE_Y0, LINE_STEP, LINES = 24, 10, 16   # text line n has its top at y = 24 + 10n (n = 0..15)
LEFT_PAGE = (22, 148)                    # text area x range
RIGHT_PAGE = (172, 298)

LEATHER, LEATHER_D, LEATHER_L = (92, 52, 30), (58, 30, 16), (140, 90, 50)
PARCH = (236, 220, 178)
INK = (70, 48, 28)
RIBBON, RIBBON_D = (150, 30, 40), (100, 15, 25)


def make_book():
    import random
    rnd = random.Random(7)
    img = Image.new("RGBA", (BOOK_W, BOOK_H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    mid = BOOK_W // 2
    # leather cover with tooled border
    d.rounded_rectangle([0, 2, BOOK_W - 1, BOOK_H - 1], radius=7, fill=LEATHER_D)
    d.rounded_rectangle([1, 3, BOOK_W - 2, BOOK_H - 3], radius=6, fill=LEATHER)
    d.rounded_rectangle([3, 5, BOOK_W - 4, BOOK_H - 5], radius=5, outline=LEATHER_L)

    # page stack edges, then parchment with grain, edge darkening and spine shadow
    for i, shade in enumerate([(200, 182, 140), (214, 198, 156), (224, 208, 166)]):
        d.rectangle([7 + i, 6 + i, mid - 3, BOOK_H - 6 - i], fill=shade)
        d.rectangle([mid + 2, 6 + i, BOOK_W - 8 - i, BOOK_H - 6 - i], fill=shade)
    px = img.load()
    for x in range(10, BOOK_W - 10):
        if mid - 3 < x < mid + 3:
            continue
        for y in range(9, BOOK_H - 8):
            n = rnd.randint(-7, 7)
            edge = min(x - 10, BOOK_W - 11 - x, y - 9, BOOK_H - 9 - y)
            spine = abs(x - mid)
            shade = max(0, 10 - edge) * 3 + max(0, 16 - spine) * 4
            px[x, y] = tuple(max(0, min(255, c + n - shade)) for c in PARCH) + (255,)
    d.line([mid - 1, 7, mid - 1, BOOK_H - 6], fill=LEATHER_D)
    d.line([mid, 7, mid, BOOK_H - 6], fill=(60, 40, 24))

    # ribbon bookmark tab at the top of the right page
    d.rectangle([BOOK_W - 40, 0, BOOK_W - 35, 14], fill=RIBBON)
    d.line([BOOK_W - 40, 0, BOOK_W - 40, 14], fill=RIBBON_D)
    d.polygon([(BOOK_W - 40, 14), (BOOK_W - 38, 11), (BOOK_W - 35, 14)], fill=PARCH)

    def line_center_y(n):
        return LINE_Y0 + LINE_STEP * n + 4

    def flourish(x0, x1, y):
        cx = (x0 + x1) // 2
        d.line([x0 + 8, y, cx - 5, y], fill=INK)
        d.line([cx + 5, y, x1 - 8, y], fill=INK)
        d.polygon([(cx, y - 3), (cx + 3, y), (cx, y + 3), (cx - 3, y)], fill=INK)
        d.point((x0 + 5, y), fill=INK)
        d.point((x1 - 5, y), fill=INK)

    flourish(*LEFT_PAGE, 15)                   # top of each page
    flourish(*RIGHT_PAGE, 15)
    flourish(*LEFT_PAGE, line_center_y(3))     # under the name block
    flourish(*LEFT_PAGE, line_center_y(9))     # above the class lore
    flourish(*RIGHT_PAGE, line_center_y(2))    # under "Stats"
    return img


# ---------------------------------------------------------------- 16x16 icons from pixel maps
def icon(rows, palette):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, line in enumerate(rows):
        for x, ch in enumerate(line.ljust(16, ".")[:16]):
            if ch != ".":
                img.putpixel((x, y), palette[ch] + (255,))
    return img


O = {"o": (20, 12, 12)}  # dark outline shared by most icons
ICONS = {
    "stat_health": ([
        "................",
        "...ooo....ooo...",
        "..oRRRo..oRRRo..",
        ".oRWRRRooRRRRRo.",
        ".oRWRRRRRRRRRRo.",
        ".oRRRRRRRRRRRRo.",
        ".oRRRRRRRRRRRdo.",
        "..oRRRRRRRRRdo..",
        "...oRRRRRRRdo...",
        "....oRRRRRdo....",
        ".....oRRRdo.....",
        "......oRdo......",
        ".......oo.......",
    ], {**O, "R": (220, 40, 50), "W": (255, 180, 180), "d": (140, 20, 30)}),
    "stat_attack": ([
        "............ooo.",
        "...........oWWo.",
        "..........oWSo..",
        ".........oWSo...",
        "........oWSo....",
        ".......oWSo.....",
        "......oWSo......",
        "..o..oWSo.......",
        "..oooWSo........",
        "...ogSo.........",
        "...oggo.........",
        "..obooggo.......",
        ".obbo..oo.......",
        ".obo............",
        "..o.............",
    ], {**O, "W": (235, 235, 245), "S": (160, 165, 180), "g": (201, 162, 39), "b": (110, 70, 40)}),
    "stat_defense": ([
        "..oooooooooooo..",
        ".oBBBBBBBBBBBBo.",
        ".oBLLLLggLLLLBo.",
        ".oBLLLLggLLLLBo.",
        ".oBLLLLggLLLLBo.",
        ".oBggggggggggBo.",
        ".oBLLLLggLLLLBo.",
        ".oBLLLLggLLLLBo.",
        "..oBLLLggLLLBo..",
        "..oBLLLggLLLBo..",
        "...oBLLggLLBo...",
        "....oBLggLBo....",
        ".....oBggBo.....",
        "......oBBo......",
        ".......oo.......",
    ], {**O, "B": (90, 95, 110), "L": (60, 100, 170), "g": (201, 162, 39)}),
    "stat_damage_reduction": ([
        "..ooo......ooo..",
        ".oIIIoooooooIIIo",
        ".oIIIIIIIIIIIIIo",
        "..oIIIIIIIIIIIo.",
        "...oIIIWIIIIIo..",
        "...oIIWIIIIIIo..",
        "...oIIIIIIIIIo..",
        "...oIIIIIIIIIo..",
        "...oIIIIIIIIIo..",
        "...oIIIIIIIIIo..",
        "...oIIIIIIIIIo..",
        "...oDDDDDDDDDo..",
        "...ooooooooooo..",
    ], {**O, "I": (175, 180, 195), "W": (240, 240, 250), "D": (110, 115, 130)}),
    "stat_pierce": ([
        "...........oooo.",
        "...........oWWo.",
        "............oWo.",
        "...........oSoo.",
        "..........oSo...",
        ".........oSo....",
        "........oSo.....",
        ".......oSo......",
        "......oSo.......",
        ".....obo........",
        ".oo.obo.........",
        ".oFobo..........",
        "..oFo...........",
        ".oFoFo..........",
        ".oo.oo..........",
    ], {**O, "W": (235, 235, 245), "S": (150, 110, 70), "b": (150, 110, 70), "F": (230, 230, 230)}),
    "stat_crit_chance": ([
        ".......oo.......",
        ".......oYo......",
        "......oYYo......",
        "......oYYo......",
        ".ooooooYYoooooo.",
        ".oYYYYYWYYYYYYo.",
        "..ooYYYWWYYYoo..",
        "....oYYYYYYo....",
        "....oYYYYYYo....",
        "...oYYYooYYYo...",
        "...oYYo..oYYo...",
        "..oYoo....ooYo..",
        "..oo........oo..",
    ], {**O, "Y": (250, 210, 50), "W": (255, 250, 200)}),
    "stat_crit_damage": ([
        "......o..o......",
        ".o...oRo.oo...o.",
        ".oo.oROoRRo..oo.",
        "..oRROOOOORooRo.",
        "..oROOYYYOORRo..",
        ".oRROYYWYYOORo..",
        "oRROOYWWWYYOORo.",
        ".oRROYYWYYOORRo.",
        "..oROOYYYOOROo..",
        "..oRROOOOORRo...",
        ".oo.oRROORRoo...",
        ".o...oRooRo..o..",
        "......o..o......",
    ], {**O, "R": (200, 50, 30), "O": (240, 130, 30), "Y": (250, 210, 60), "W": (255, 250, 220)}),
    "stat_life_steal": ([
        ".......oo.......",
        "......oRRo......",
        "......oRRo......",
        ".....oRRRRo.....",
        ".....oRRRRo.....",
        "....oRRRRRRo....",
        "...oRWRRRRRRo...",
        "...oRWRRRRRRo...",
        "..oRRWRRRRRRRo..",
        "..oRRRRRRRRRdo..",
        "..oRRRRRRRRRdo..",
        "...oRRRRRRRdo...",
        "....oddRRddo....",
        ".....oooooo.....",
    ], {**O, "R": (150, 15, 30), "W": (230, 90, 100), "d": (90, 5, 15)}),
    "stat_slowness": ([
        ".......oo.......",
        "....o..oCo..o...",
        "....oCooCooCo...",
        ".....oCoCoCo....",
        ".oo...oCCCo...oo",
        ".oCCooCCWCCooCCo",
        ".oCCooCCWCCooCCo",
        ".oo...oCCCo...oo",
        ".....oCoCoCo....",
        "....oCooCooCo...",
        "....o..oCo..o...",
        ".......oo.......",
    ], {**O, "C": (150, 220, 255), "W": (240, 250, 255)}),
    "stat_poison": ([
        "......oooo......",
        "......oCCo......",
        "......oGGo......",
        ".....oGGGGo.....",
        "....oGGGGGGo....",
        "...oGGWGGGGGo...",
        "...oGWGGGGGGo...",
        "...oGGGLGGGGo...",
        "...oGGLLLGGGo...",
        "...oGGGLGGGdo...",
        "....oGGGGGdo....",
        ".....oooooo.....",
    ], {**O, "C": (130, 90, 50), "G": (90, 190, 50), "W": (200, 255, 170), "L": (40, 110, 30), "d": (50, 120, 30)}),
    "stat_speed": ([
        "............oo..",
        "..........ooWo..",
        "........ooWWWo..",
        ".......oWWWWWo..",
        "......oWWWWWo...",
        ".....oWWWWWo....",
        "....oWWWWWo.....",
        "...oWWWWoo......",
        "...oWWWo........",
        "..oWoo..........",
        "..oo............",
        ".o..............",
    ], {**O, "W": (220, 240, 255)}),
    "cat_offense": ([
        ".oo..........oo.",
        ".oWo........oWo.",
        "..oWo......oWo..",
        "...oWo....oWo...",
        "....oWo..oWo....",
        ".....oWooWo.....",
        "......oWWo......",
        ".....oWooWo.....",
        "..o.oWo..oWo.o..",
        "..oogo....ogoo..",
        "...ogo....ogo...",
        "..obo......obo..",
        ".obo........obo.",
        ".oo..........oo.",
    ], {**O, "W": (235, 120, 120), "g": (201, 162, 39), "b": (110, 70, 40)}),
    "cat_defense": None,   # reuses stat_defense art
    "cat_utility": ([
        "......oooo......",
        "...oo.oGGo.oo...",
        "..oGGooGGooGGo..",
        "..oGGGGGGGGGGo..",
        "...oGGGooGGGo...",
        ".ooGGGo..oGGGoo.",
        ".oGGGo....oGGGo.",
        ".oGGGo....oGGGo.",
        ".ooGGGo..oGGGoo.",
        "...oGGGooGGGo...",
        "..oGGGGGGGGGGo..",
        "..oGGooGGooGGo..",
        "...oo.oGGo.oo...",
        "......oooo......",
    ], {**O, "G": (120, 200, 120)}),
    "xp_full": ([
        "................",
        "................",
        "................",
        "................",
        "................",
        "oooooooooooooooo",
        "WWWWWWWWWWWWWWWW",
        "GGGGGGGGGGGGGGGG",
        "GGGGGGGGGGGGGGGG",
        "DDDDDDDDDDDDDDDD",
        "oooooooooooooooo",
    ], {**O, "W": (190, 255, 120), "G": (110, 210, 50), "D": (60, 140, 30)}),
    "xp_empty": ([
        "................",
        "................",
        "................",
        "................",
        "................",
        "oooooooooooooooo",
        "EEEEEEEEEEEEEEEE",
        "DDDDDDDDDDDDDDDD",
        "DDDDDDDDDDDDDDDD",
        "DDDDDDDDDDDDDDDD",
        "oooooooooooooooo",
    ], {**O, "E": (60, 60, 70), "D": (36, 36, 44)}),
}


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(data, f, indent=2)


def main():
    shutil.rmtree(OUT, ignore_errors=True)
    write_json(os.path.join(OUT, "pack.mcmeta"), {"pack": {
        "description": "RPGCore UI",
        "min_format": PACK_FORMAT,
        "max_format": PACK_FORMAT,
    }})

    # Book image + fonts.
    #  rpgcore:gui  -> \uE000 + \uE001 = the book image (left + right half), \uF8xx = spacing characters (negative = move left)
    #  rpgcore:l0..l15 -> the normal Minecraft letters, moved down to text line n of the book
    #  rpgcore:big0 -> letters at 2x size on line 0 (class name, page headings)
    # The dialog draws text with its baseline 7px below the line top, so ascent = 7 - (top y in the book).
    os.makedirs(os.path.join(NS, "textures", "gui"), exist_ok=True)
    make_book().save(os.path.join(NS, "textures", "gui", "stats_book.png"))
    spaces = {}
    for i, n in enumerate([1, 2, 4, 8, 16, 32, 64, 128]):
        spaces[chr(0xF801 + i)] = -n   # \uF801..\uF808  = -1 .. -128
        spaces[chr(0xF821 + i)] = n    # \uF821..\uF828  = +1 .. +128
    write_json(os.path.join(NS, "font", "gui.json"), {"providers": [
        {"type": "space", "advances": spaces},
        {"type": "bitmap", "file": "rpgcore:gui/stats_book.png", "ascent": 7, "height": BOOK_H, "chars": ["\ue000\ue001"]},  # two 160px halves: font glyphs can't be wider than 256px
    ]})
    # Same layout as the game's own ascii.png: rows 2-7 are printable ASCII (space handled separately).
    blank = "\u0000" * 16
    grid = [blank, blank, "\u0000!\"#$%&'()*+,-./", "0123456789:;<=>?", "@ABCDEFGHIJKLMNO",
            "PQRSTUVWXYZ[\\]^_", "`abcdefghijklmno", "pqrstuvwxyz{|}~\u0000"] + [blank] * 8
    for n in range(LINES):
        top = LINE_Y0 + LINE_STEP * n
        write_json(os.path.join(NS, "font", "l%d.json" % n), {"providers": [
            {"type": "space", "advances": {" ": 4}},
            {"type": "bitmap", "file": "minecraft:font/ascii.png", "ascent": 7 - top, "height": 8, "chars": grid},
        ]})
    write_json(os.path.join(NS, "font", "big0.json"), {"providers": [
        {"type": "space", "advances": {" ": 7}},
        {"type": "bitmap", "file": "minecraft:font/ascii.png", "ascent": 7 - LINE_Y0, "height": 16, "chars": grid},
    ]})

    # Icons: texture + item model + item definition (what the item_model component points at)
    os.makedirs(os.path.join(NS, "textures", "item"), exist_ok=True)
    for name, spec in ICONS.items():
        rows, pal = spec if spec else ICONS["stat_defense"]
        icon(rows, pal).save(os.path.join(NS, "textures", "item", name + ".png"))
        write_json(os.path.join(NS, "models", "item", name + ".json"),
                   {"parent": "minecraft:item/generated", "textures": {"layer0": "rpgcore:item/" + name}})
        write_json(os.path.join(NS, "items", name + ".json"),
                   {"model": {"type": "minecraft:model", "model": "rpgcore:item/" + name}})

    if os.path.exists(ZIP):
        os.remove(ZIP)
    with zipfile.ZipFile(ZIP, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(OUT):
            for f in files:
                full = os.path.join(root, f)
                z.write(full, os.path.relpath(full, OUT))
    print("Wrote", os.path.abspath(ZIP))


if __name__ == "__main__":
    main()
