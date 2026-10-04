#!/usr/bin/env python3
"""Converts a Blockbench model built from box-shaped meshes into a Java item model, and a .glb.

    python3 scripts/bbmodel-to-item-model.py <model.bbmodel> <name> [--glb-dir DIR]

A Java model is built only of boxes, so this takes a Blockbench "free" model whose every mesh is an
axis-aligned box — eight vertices, six rectangular faces, no rotation — and refuses anything else
rather than approximating it. It writes, under src/main/resources/assets/serversidetrains/:

- models/item/<name>.json, the boxes as elements, Blockbench's origin placed at the model's centre
  (8, 8, 8), which is the point an item display draws a model from;
- textures/item/<name>.png, the model's embedded texture;
- items/<name>.json, the item definition an ITEM_MODEL component names.

With --glb-dir it also writes <name>.glb for Blender, baked from the Java model by the workspace's
mc_models_to_gltf.py (so it is what the game will draw), with its origin at Blockbench's origin, in
blocks, Y up — and checks that every corner's position and UV in it matches the source meshes.
"""

import argparse
import base64
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path.home() / 'projects/minecraft-dev/scripts'))
import mc_models_to_gltf as gltf  # noqa: E402

ASSETS = (Path(__file__).resolve().parent.parent
          / 'src/main/resources/assets/serversidetrains')
NAMESPACE = 'serversidetrains'

# Where Blockbench's origin goes in the Java model, in model pixels: the model's centre.
CENTRE = (8.0, 8.0, 8.0)

# Which axis a face is flat on, and whether it is that axis's far side.
FACE_AXIS = {'west': (0, False), 'east': (0, True), 'down': (1, False), 'up': (1, True),
             'north': (2, False), 'south': (2, True)}


def box_of(mesh):
    """The mesh's corners in model pixels, as (from, to), or an error if it is not a box."""
    if any(mesh.get('rotation', [0, 0, 0])):
        raise ValueError('rotated mesh')
    points = [[c + o for c, o in zip(p, mesh['origin'])] for p in mesh['vertices'].values()]
    lo = [min(p[a] for p in points) for a in range(3)]
    hi = [max(p[a] for p in points) for a in range(3)]
    corners = {tuple((hi if (i >> a) & 1 else lo)[a] for a in range(3)) for i in range(8)}
    if len(points) != 8 or {tuple(p) for p in points} != corners or len(mesh['faces']) != 6:
        raise ValueError('not an axis-aligned box')
    return lo, hi


def face_of(mesh, face, lo, hi):
    """Which Java face a mesh face is, from the axis its four corners share."""
    points = [[c + o for c, o in zip(mesh['vertices'][v], mesh['origin'])]
              for v in face['vertices']]
    for name, (axis, far) in FACE_AXIS.items():
        side = hi[axis] if far else lo[axis]
        if all(p[axis] == side for p in points):
            return name, points
    raise ValueError('face is not on a side of its box')


def solve_uv(name, points, uvs, f, t, size):
    """The Java uv rectangle and rotation that put the mesh's UVs on the same corners."""
    by_corner = {tuple(p): uv for p, uv in zip(points, uvs)}
    for shift in range(4):
        u = {}, {}
        ok = True
        for i, pick in enumerate(gltf.FACE_CORNERS[name]):
            corner = tuple((t if pick[a] else f)[a] for a in range(3))
            if corner not in by_corner:
                raise ValueError(f'{name} face has no vertex at {corner}')
            mu, mv = by_corner[corner]
            j = (i + shift) % 4
            for slot, value, key in ((0, mu, 0 if j in (0, 1) else 2),
                                     (1, mv, 1 if j in (0, 3) else 3)):
                if u[slot].setdefault(key, value) != value:
                    ok = False
        if ok:
            scale = 16 / size[0], 16 / size[1]
            return [u[0][0] * scale[0], u[1][1] * scale[1],
                    u[0][2] * scale[0], u[1][3] * scale[1]], shift * 90
    raise ValueError(f'{name} face UVs fit no rotation of a Java face')


class LocalAssets:
    """Stands in for mc_models_to_gltf's resource-pack lookup, reading this mod's own assets."""

    def read(self, path):
        prefix = f'assets/{NAMESPACE}/'
        if not path.startswith(prefix):
            raise KeyError(path)
        return (ASSETS / path[len(prefix):]).read_bytes()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('bbmodel')
    ap.add_argument('name')
    ap.add_argument('--glb-dir')
    args = ap.parse_args()

    bb = json.loads(Path(args.bbmodel).read_text())
    if len(bb['textures']) != 1:
        raise SystemExit('expected exactly one texture')
    texture = bb['textures'][0]
    size = (texture.get('uv_width', bb['resolution']['width']),
            texture.get('uv_height', bb['resolution']['height']))

    elements, source = [], []
    for mesh in bb['elements']:
        if mesh.get('type') != 'mesh':
            raise SystemExit(f'element {mesh.get("name")!r} is not a mesh')
        lo, hi = box_of(mesh)
        f = [c + o for c, o in zip(lo, CENTRE)]
        t = [c + o for c, o in zip(hi, CENTRE)]
        faces = {}
        for face in mesh['faces'].values():
            name, points = face_of(mesh, face, lo, hi)
            uvs = [face['uv'][v] for v in face['vertices']]
            shifted = [[c + o for c, o in zip(p, CENTRE)] for p in points]
            uv, rotation = solve_uv(name, shifted, uvs, f, t, size)
            faces[name] = {'uv': uv, 'texture': '#0'}
            if rotation:
                faces[name]['rotation'] = rotation
            source.append((name, [[c / 16 for c in p] for p in points],
                           [[uv_[0] / size[0], uv_[1] / size[1]] for uv_ in uvs]))
        elements.append({'name': mesh.get('name', 'box'), 'from': f, 'to': t, 'faces': faces})

    texture_id = f'{NAMESPACE}:item/{args.name}'
    model = {'textures': {'0': texture_id, 'particle': texture_id}, 'elements': elements}
    data = texture['source'].split(',', 1)[1]

    (ASSETS / 'models/item').mkdir(parents=True, exist_ok=True)
    (ASSETS / 'textures/item').mkdir(parents=True, exist_ok=True)
    (ASSETS / 'items').mkdir(parents=True, exist_ok=True)
    (ASSETS / f'models/item/{args.name}.json').write_text(json.dumps(model, indent=2) + '\n')
    (ASSETS / f'textures/item/{args.name}.png').write_bytes(base64.b64decode(data))
    (ASSETS / f'items/{args.name}.json').write_text(json.dumps(
        {'model': {'type': 'minecraft:model', 'model': f'{NAMESPACE}:item/{args.name}'}},
        indent=2) + '\n')
    print(f'wrote models/item/{args.name}.json ({len(elements)} elements), '
          f'textures/item/{args.name}.png, items/{args.name}.json')

    if not args.glb_dir:
        return 0

    # Baked from the Java model, then moved so the origin is Blockbench's: the baker sits a block
    # model on y = 0 centred on x and z, which leaves the model's centre half a block up.
    quads = []
    for tex, pos, uvs in gltf.block_model_quads(model):
        quads.append((tex, [[p[0], p[1] - 0.5, p[2]] for p in pos], uvs))

    # Every face the Java model draws must put the same UV on the same corner as the mesh did.
    baked = {frozenset((tuple(round(c, 6) for c in p), tuple(round(c, 6) for c in uv))
                       for p, uv in zip(pos, uvs)) for _, pos, uvs in quads}
    wanted = {frozenset((tuple(round(c, 6) for c in p), tuple(round(c, 6) for c in uv))
                        for p, uv in zip(pos, uvs)) for _, pos, uvs in source}
    if baked != wanted:
        raise SystemExit(f'Java model does not reproduce the mesh: {len(baked ^ wanted)} faces '
                         'differ')
    print(f'checked: all {len(baked)} faces match the source meshes corner for corner')

    out = Path(args.glb_dir) / f'{args.name}.glb'
    out.parent.mkdir(parents=True, exist_ok=True)
    faces = gltf.write_glb(str(out), [(args.name, quads)], [{'name': args.name, 'mesh': 0}],
                           LocalAssets())
    print(f'wrote {out} ({faces} faces)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
