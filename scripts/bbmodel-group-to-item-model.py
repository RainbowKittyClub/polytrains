#!/usr/bin/env python3
"""Bakes one group of a Blockbench model's cubes into a Java item model, rotations and all.

    python3 scripts/bbmodel-group-to-item-model.py <model.bbmodel> <group> <name>
        [--turn GROUP AXIS DEGREES]... [--glb-dir DIR]

Where bbmodel-to-item-model.py takes box-shaped meshes with no rotation, this takes Blockbench
cubes under nested, rotated groups. A Java element has no groups and one rotation about one origin,
but since 26.1 that rotation may be any x/y/z Euler angle, applied in the order Blockbench applies
its own (JOML's rotationZYX, three.js's 'ZYX'). So each cube's whole chain — its own rotation, then
every enclosing group's, each about its own origin — folds into one rotation exactly, about the
cube's centre once the chain has moved it. Nothing is approximated; the result is checked.

--turn adds DEGREES to GROUP's rotation about AXIS before baking, as typing it into Blockbench's
rotation field would: on z, the outermost of the three, that is a turn about the parent's z axis.
It writes, under src/main/resources/assets/polytrains/:

- models/item/<name>.json, the group's cubes as elements, Blockbench's origin placed at the model's
  centre (8, 8, 8), which is the point an item display draws a model from;
- textures/item/<texture>.png, the one texture every face of the group uses, named for it;
- items/<name>.json, the item definition an ITEM_MODEL component names.

Every corner of every face is then put through Blockbench's chain and through the game's element
rotation (the workspace's mc_models_to_gltf.py, which follows CuboidRotation), and the two must
agree. With --glb-dir it also writes <name>.glb for Blender, origin at Blockbench's, in blocks.
"""

import argparse
import base64
import json
import math
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path.home() / 'projects/minecraft-dev/scripts'))
import mc_models_to_gltf as gltf  # noqa: E402

ASSETS = (Path(__file__).resolve().parent.parent
          / 'src/main/resources/assets/polytrains')
NAMESPACE = 'polytrains'

# Where Blockbench's origin goes in the Java model, in model pixels: the model's centre.
CENTRE = (8.0, 8.0, 8.0)

# The extent CuboidModelElement accepts for an element's corners, in model pixels.
EXTENT = (-16.0, 32.0)

AXES = {'x': 0, 'y': 1, 'z': 2}


def mat_vec(m, v):
    return [sum(m[r][c] * v[c] for c in range(3)) for r in range(3)]


def transpose(m):
    return [[m[c][r] for c in range(3)] for r in range(3)]


def rotation(degrees):
    """Blockbench's rotation of [x, y, z] degrees: Rz * Ry * Rx, as JOML's rotationZYX builds."""
    return gltf.rot_zyx(*(math.radians(a) for a in degrees))


def euler_zyx(m):
    """The [x, y, z] degrees whose Rz * Ry * Rx is m, the inverse of rotation().

    Every rotation has two such triples, (x, y, z) and (x + 180, 180 - y, z + 180); the one turning
    least in all is kept, so a cube Blockbench turned half round y reads as that, not as half turns
    about x and z that come to the same thing.
    """
    sy = max(-1.0, min(1.0, -m[2][0]))
    y = math.degrees(math.asin(sy))
    if abs(math.cos(math.radians(y))) > 1e-9:
        x = math.degrees(math.atan2(m[2][1], m[2][2]))
        z = math.degrees(math.atan2(m[1][0], m[0][0]))
    else:
        # Gimbal lock: x and z turn about the same axis, so put it all in z.
        x = 0.0
        z = math.degrees(math.atan2(-m[0][1], m[1][1]))
    triples = [(x, y, z), (x + 180.0, 180.0 - y, z + 180.0)]
    return min(([wrap(a) for a in t] for t in triples), key=lambda t: sum(abs(a) for a in t))


def wrap(degrees):
    """degrees as the same angle in (-180, 180]."""
    turned = math.fmod(degrees, 360.0)
    if turned > 180.0:
        turned -= 360.0
    elif turned <= -180.0:
        turned += 360.0
    return turned


class Affine:
    """p -> m p + t, in Blockbench pixels."""

    def __init__(self, m=None, t=None):
        self.m = m or [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
        self.t = t or [0.0, 0.0, 0.0]

    @staticmethod
    def about(origin, degrees):
        """A rotation about origin: p -> R (p - o) + o."""
        m = rotation(degrees)
        return Affine(m, [o - r for o, r in zip(origin, mat_vec(m, origin))])

    def then(self, outer):
        """This, followed by outer."""
        m = [[sum(outer.m[r][k] * self.m[k][c] for k in range(3)) for c in range(3)]
             for r in range(3)]
        return Affine(m, [a + b for a, b in zip(mat_vec(outer.m, self.t), outer.t)])

    def apply(self, p):
        return [a + b for a, b in zip(mat_vec(self.m, p), self.t)]


def outline(bb):
    """The outliner as (group dict, children) nodes, for both the 5.x and the older layout."""
    groups = {g['uuid']: g for g in bb.get('groups', [])}

    def node(n):
        if isinstance(n, str):
            return n
        return groups.get(n.get('uuid'), n), [node(c) for c in n.get('children', [])]

    return [node(n) for n in bb['outliner']]


def find(nodes, name, chain=()):
    """The group called name, with the groups enclosing it, outermost first."""
    found = []
    for n in nodes:
        if isinstance(n, str):
            continue
        group, children = n
        if group['name'] == name:
            found.append((n, chain))
        found += find(children, name, chain + (group,))
    return found


def cubes(node, chain, turns):
    """(element uuid, its chain of groups' affines composed, innermost to outermost)."""
    group, children = node
    rot = list(group.get('rotation', [0, 0, 0]))
    for axis, degrees in turns.get(group['name'], []):
        rot[AXES[axis]] += degrees
    here = Affine.about(group.get('origin', [0, 0, 0]), rot)
    for child in children:
        if isinstance(child, str):
            yield child, [here] + chain
        else:
            yield from cubes(child, [here] + chain, turns)


def bake(cube, chain, size):
    """The cube as a Java element, and its six faces' corners put through Blockbench's chain."""
    inflate = cube.get('inflate', 0)
    lo = [c - inflate for c in cube['from']]
    hi = [c + inflate for c in cube['to']]
    whole = Affine.about(cube.get('origin', [0, 0, 0]), cube.get('rotation') or [0, 0, 0])
    for outer in chain:
        whole = whole.then(outer)

    # The box keeps its size and is centred on where the chain takes its centre; the rotation is
    # the chain's, about that centre.
    centre = whole.apply([(a + b) / 2 for a, b in zip(lo, hi)])
    half = [(b - a) / 2 for a, b in zip(lo, hi)]
    f = [c - h + o for c, h, o in zip(centre, half, CENTRE)]
    t = [c + h + o for c, h, o in zip(centre, half, CENTRE)]
    if min(f) < EXTENT[0] or max(t) > EXTENT[1]:
        raise SystemExit(f'cube {cube["name"]!r} reaches outside {EXTENT} once placed')

    faces = {}
    for name, face in cube['faces'].items():
        if face.get('texture') is None:
            continue
        uv = [round(c * 16 / s, 6) + 0.0
              for c, s in zip(face['uv'], (size[0], size[1], size[0], size[1]))]
        faces[name] = {'uv': uv, 'texture': '#0'}
        if face.get('rotation'):
            faces[name]['rotation'] = face['rotation']
    element = {'name': cube['name'], 'from': rounded(f), 'to': rounded(t), 'faces': faces}

    identity = all(abs(whole.m[r][c] - (r == c)) < 1e-12 for r in range(3) for c in range(3))
    if not identity:
        element['rotation'] = {'origin': rounded([c + o for c, o in zip(centre, CENTRE)]),
                               **dict(zip('xyz', rounded(euler_zyx(whole.m))))}

    # What Blockbench draws: the cube unturned, as a Java element, then the chain. Same faces in
    # the same order as the baked element, so the two can be compared quad for quad.
    flat = {'from': [c + o for c, o in zip(lo, CENTRE)],
            'to': [c + o for c, o in zip(hi, CENTRE)], 'faces': faces}
    truth = [([whole.apply(pixels(p)) for p in pos], uvs)
             for _, pos, uvs in gltf.block_model_quads({'elements': [flat],
                                                        'textures': {'0': 'x'}})]
    return element, truth


def pixels(p):
    """A baker position (blocks, centred on x and z) back in Blockbench pixels."""
    return [(p[0] + 0.5) * 16 - CENTRE[0], p[1] * 16 - CENTRE[1], (p[2] + 0.5) * 16 - CENTRE[2]]


def rounded(v):
    # Adding 0.0 turns a -0.0 into 0.0, which reads the same to the game but not in a diff.
    return [round(c, 6) + 0.0 for c in v]


# How far apart, in pixels, a baked corner and Blockbench's may be: the JSON's six decimals.
TOLERANCE = 1e-4


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
    ap.add_argument('group')
    ap.add_argument('name')
    ap.add_argument('--turn', nargs=3, action='append', default=[],
                    metavar=('GROUP', 'AXIS', 'DEGREES'))
    ap.add_argument('--glb-dir')
    args = ap.parse_args()

    bb = json.loads(Path(args.bbmodel).read_text())
    turns = {}
    for group, axis, degrees in args.turn:
        if axis not in AXES:
            raise SystemExit(f'--turn axis must be x, y or z, not {axis!r}')
        turns.setdefault(group, []).append((axis, float(degrees)))

    tree = outline(bb)
    found = find(tree, args.group)
    if len(found) != 1:
        raise SystemExit(f'expected one group called {args.group!r}, found {len(found)}')
    node, enclosing = found[0]
    named = {group['name'] for group, _ in walk(node)}
    for group in turns:
        if group not in named:
            raise SystemExit(f'--turn names {group!r}, which is not in {args.group!r}')

    # The enclosing groups' own rotations apply too, innermost last in the list given here.
    outer_chain = [Affine.about(g.get('origin', [0, 0, 0]), g.get('rotation', [0, 0, 0]))
                   for g in reversed(enclosing)]

    elements_by_uuid = {e['uuid']: e for e in bb['elements']}
    used = set()
    pairs = []
    for uuid, chain in cubes(node, outer_chain, turns):
        cube = elements_by_uuid[uuid]
        if cube.get('type', 'cube') != 'cube':
            raise SystemExit(f'element {cube.get("name")!r} is a {cube["type"]}, not a cube')
        used |= {f['texture'] for f in cube['faces'].values() if f.get('texture') is not None}
        pairs.append((cube, chain))
    if len(used) != 1:
        raise SystemExit(f'expected the group to use one texture, it uses {len(used)}')
    texture = bb['textures'][used.pop()]
    size = (texture.get('uv_width', bb['resolution']['width']),
            texture.get('uv_height', bb['resolution']['height']))
    texture_name = re.sub(r'[^a-z0-9_]', '_', Path(texture['name']).stem.lower())

    elements, wanted = [], []
    for cube, chain in pairs:
        element, truth = bake(cube, chain, size)
        elements.append(element)
        wanted += truth

    texture_id = f'{NAMESPACE}:item/{texture_name}'
    model = {'textures': {'0': texture_id, 'particle': texture_id}, 'elements': elements}

    # Every face the game will draw must put the same UV on the same corner as Blockbench does.
    baked = [([pixels(p) for p in pos], uvs) for _, pos, uvs in gltf.block_model_quads(model)]
    if len(baked) != len(wanted):
        raise SystemExit(f'Java model draws {len(baked)} faces, Blockbench {len(wanted)}')
    worst = 0.0
    for (got_pos, got_uv), (want_pos, want_uv) in zip(baked, wanted):
        for got, want in zip(got_pos + got_uv, want_pos + want_uv):
            worst = max(worst, max(abs(g - w) for g, w in zip(got, want)))
    if worst > TOLERANCE:
        raise SystemExit(f'Java model does not reproduce the cubes: off by {worst} px')

    (ASSETS / 'models/item').mkdir(parents=True, exist_ok=True)
    (ASSETS / 'textures/item').mkdir(parents=True, exist_ok=True)
    (ASSETS / 'items').mkdir(parents=True, exist_ok=True)
    (ASSETS / f'models/item/{args.name}.json').write_text(json.dumps(model, indent=2) + '\n')
    (ASSETS / f'textures/item/{texture_name}.png').write_bytes(
        base64.b64decode(texture['source'].split(',', 1)[1]))
    (ASSETS / f'items/{args.name}.json').write_text(json.dumps(
        {'model': {'type': 'minecraft:model', 'model': f'{NAMESPACE}:item/{args.name}'}},
        indent=2) + '\n')
    print(f'wrote models/item/{args.name}.json ({len(elements)} elements), '
          f'textures/item/{texture_name}.png, items/{args.name}.json')
    print(f'checked: all {len(baked)} faces match Blockbench corner for corner')

    if args.glb_dir:
        quads = [(tex, [[p[0], p[1] - 0.5, p[2]] for p in pos], uvs)
                 for tex, pos, uvs in gltf.block_model_quads(model)]
        out = Path(args.glb_dir) / f'{args.name}.glb'
        out.parent.mkdir(parents=True, exist_ok=True)
        faces = gltf.write_glb(str(out), [(args.name, quads)], [{'name': args.name, 'mesh': 0}],
                               LocalAssets())
        print(f'wrote {out} ({faces} faces)')
    return 0


def walk(node):
    """Every group node at and under node."""
    yield node
    for child in node[1]:
        if not isinstance(child, str):
            yield from walk(child)


if __name__ == '__main__':
    sys.exit(main())
