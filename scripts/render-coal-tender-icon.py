#!/usr/bin/env python3
"""Renders the coal tender's 16x16 inventory icon from vanilla's own textures.

    python3 scripts/render-coal-tender-icon.py [--preview PATH]

Vanilla's chest minecart icon is its minecart icon with a chest drawn over the open top, so the
chest's pixels are exactly the minecart icon's differences from it. Those pixels are recoloured
from vanilla's coal block, keeping the chest's dark outline, which leaves a minecart carrying a
block of coal drawn the way vanilla draws its other loaded carts. The textures are read from the
deobfuscated Minecraft jar Loom caches, so nothing of Mojang's is committed but the result.
"""

import argparse
import io
import sys
import zipfile
from pathlib import Path

from PIL import Image

JAR = Path.home() / ('.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/'
                     'minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar')
OUT = (Path(__file__).resolve().parent.parent
       / 'src/main/resources/assets/serversidetrains/textures/item/coal_tender.png')

# Below this summed RGB a chest pixel is its outline, which stays as it is.
OUTLINE = 160

# The coal block is near black against a near-black cart interior, so its texels are lifted a
# little to read as a load rather than a hole.
LIFT = 1.35


def texture(jar, path):
    return Image.open(io.BytesIO(jar.read(f'assets/minecraft/textures/{path}.png'))).convert('RGBA')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--preview', help='also write an 8x enlarged copy here, for looking at')
    args = ap.parse_args()

    with zipfile.ZipFile(JAR) as jar:
        cart = texture(jar, 'item/minecart')
        chest_cart = texture(jar, 'item/chest_minecart')
        coal = texture(jar, 'block/coal_block')

    icon = chest_cart.copy()
    for y in range(16):
        for x in range(16):
            pixel = chest_cart.getpixel((x, y))
            is_chest = pixel != cart.getpixel((x, y)) and pixel[3] > 0
            if is_chest and sum(pixel[:3]) >= OUTLINE:
                r, g, b, _ = coal.getpixel((x, y))
                icon.putpixel((x, y), (min(255, int(r * LIFT)), min(255, int(g * LIFT)),
                                       min(255, int(b * LIFT)), 255))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    icon.save(OUT)
    print(f'wrote {OUT}')

    if args.preview:
        icon.resize((128, 128), Image.NEAREST).save(args.preview)
        print(f'wrote {args.preview}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
