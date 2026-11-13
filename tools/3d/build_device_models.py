"""Models the charging case and recorder bean from the product photos and renders the app's art.

Run: /Applications/Blender.app/Contents/MacOS/Blender -b -P tools/3d/build_device_models.py -- <job> [out_dir]
Jobs: preview, stills, spin, assets (both stills and the complete turntables).
Output defaults to $TMPDIR/device_renders; tools/3d/encode_device_art.sh turns it into app resources.
Units are millimetres, Blender Z up. Nothing here runs in the app: the app only ships the rendered images.
"""
import bpy, bmesh, json, math, os, sys
from mathutils import Vector, Matrix

SCRATCH = os.environ.get("TMPDIR", "/tmp")
args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
JOB = args[0] if args else "preview"
OUT = args[1] if len(args) > 1 else os.path.join(SCRATCH, "device_renders")

# Nominal case datums from anker-recorder-dock-shell/parametric-v30/parameters.json.
# That project's 13.6mm recorder radius is a protective-shell clearance, not the bean radius.
# Remaining small details are photo estimates, not a dimensional reconstruction.
CASE_W, CASE_H, CASE_R = 60.0, 8.4, 12.5
POCKET = Vector((17.5, 17.5))
POCKET_R, POCKET_D = 12.2, 4.0
BEAN_R, BEAN_BACK, BEAN_SIDE_TOP, BEAN_FRONT = 11.8, -5.5, 2.0, 5.5
CLIP_BACK, CLIP_FRONT = -7.9, -6.1

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene


def material(name, color, rough, metal=0.0, emission=None, alpha=None, image=None):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    bsdf = m.node_tree.nodes["Principled BSDF"]
    bsdf.inputs["Base Color"].default_value = (*color, 1)
    bsdf.inputs["Roughness"].default_value = rough
    bsdf.inputs["Metallic"].default_value = metal
    if emission:
        bsdf.inputs["Emission Color"].default_value = (*emission, 1)
        bsdf.inputs["Emission Strength"].default_value = 1.0
    if image:
        tex = m.node_tree.nodes.new("ShaderNodeTexImage")
        tex.image = image
        m.node_tree.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
        m.node_tree.links.new(tex.outputs["Alpha"], bsdf.inputs["Alpha"])
    if alpha is not None:
        bsdf.inputs["Alpha"].default_value = alpha
    if image or alpha is not None:
        m.surface_render_method = "BLENDED"
    return m


M = {
    "Body": material("Body", (0.80, 0.80, 0.79), 0.42),
    "Side": material("Side", (0.55, 0.56, 0.57), 0.32, 0.55),
    "Grille": material("Grille", (0.025, 0.025, 0.028), 0.55),
    "Led": material("Led", (0.02, 0.01, 0.0), 0.3, emission=(1.0, 0.42, 0.05)),
    "Button": material("Button", (0.55, 0.56, 0.57), 0.32, 0.55, emission=(0, 0, 0)),
    "Contacts": material("Contacts", (0.86, 0.8, 0.66), 0.22, 1.0),
    "Strap": material("Strap", (0.78, 0.78, 0.77), 0.7),
    "Gap": material("Gap", (0.01, 0.01, 0.012), 0.6),
    "BeanFace": material("BeanFace", (0.07, 0.075, 0.085), 0.42, 0.5),
    "BeanSide": material("BeanSide", (0.05, 0.055, 0.065), 0.42, 0.5),
    "Clip": material("Clip", (0.025, 0.028, 0.032), 0.52),
    "Well": material("Well", (0.045, 0.05, 0.06), 0.48, 0.35),
    "Magnet": material("Magnet", (0.035, 0.038, 0.045), 0.52, 0.25),
    "MeshMetal": material("MeshMetal", (0.28, 0.30, 0.33), 0.3, 0.9),
    "Trim": material("Trim", (0.42, 0.44, 0.47), 0.28, 0.95),
    "Logo": material("Logo", (0.20, 0.21, 0.23), 0.55, 0.3),
}


def obj_from_bm(name, bm, mats, parent=None, smooth_angle=35):
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    o = bpy.data.objects.new(name, me)
    scene.collection.objects.link(o)
    for m in mats:
        me.materials.append(M[m] if isinstance(m, str) else m)
    if smooth_angle:
        me.shade_smooth()
        bpy.context.view_layer.objects.active = o
        o.select_set(True)
        bpy.ops.object.shade_auto_smooth(angle=math.radians(smooth_angle))
        o.select_set(False)
        apply_modifiers(o)
    o.parent = parent
    return o


def apply_modifiers(o):
    bpy.context.view_layer.objects.active = o
    for mod in list(o.modifiers):
        bpy.ops.object.modifier_apply(modifier=mod.name)


def rounded_rect(w, r, seg=14):
    pts = []
    h = w / 2 - r
    for cx, cy, a0 in ((h, h, 0), (-h, h, 90), (-h, -h, 180), (h, -h, 270)):
        for i in range(seg + 1):
            a = math.radians(a0 + 90 * i / seg)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def prism(name, outline, z0, z1):
    bm = bmesh.new()
    bottom = [bm.verts.new((x, y, z0)) for x, y in outline]
    top = [bm.verts.new((x, y, z1)) for x, y in outline]
    bm.faces.new(list(reversed(bottom)))
    bm.faces.new(top)
    n = len(outline)
    for i in range(n):
        j = (i + 1) % n
        bm.faces.new((bottom[i], bottom[j], top[j], top[i]))
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    o = bpy.data.objects.new(name, me)
    scene.collection.objects.link(o)
    return o


def cutter(o, target):
    mod = target.modifiers.new("cut-" + o.name, "BOOLEAN")
    mod.operation, mod.object, mod.solver = "DIFFERENCE", o, "EXACT"
    apply_modifiers(target)
    bpy.data.objects.remove(o)


def assign_side(o, side_index, threshold=0.55):
    for p in o.data.polygons:
        if abs(p.normal.z) < threshold:
            p.material_index = side_index


def cylinder(name, radius, z0, z1, mat=None, centre=(0, 0), bevel=0):
    bpy.ops.mesh.primitive_cylinder_add(vertices=96, radius=radius, depth=z1-z0,
        location=(centre[0], centre[1], (z0+z1)/2))
    obj = bpy.context.object
    obj.name = name
    if mat:
        obj.data.materials.append(M[mat])
    if bevel:
        mod = obj.modifiers.new("Edge roll", "BEVEL")
        mod.width, mod.segments = bevel, 4
        apply_modifiers(obj)
        obj.data.shade_smooth()
        mod = obj.modifiers.new("Flat caps", "WEIGHTED_NORMAL")
        mod.keep_sharp = True
        apply_modifiers(obj)
    return obj


# ---- Charging case -------------------------------------------------------------------------
case = prism("Case", rounded_rect(CASE_W, CASE_R), -CASE_H / 2, CASE_H / 2)
floor_z = CASE_H / 2 - POCKET_D
circle = [(POCKET.x + POCKET_R * math.cos(a), POCKET.y + POCKET_R * math.sin(a))
          for a in (2 * math.pi * i / 96 for i in range(96))]
cutter(prism("pocket", circle, floor_z, CASE_H), case)
# User photos 3/4 show a COMPLETE round seating floor, with the raised deck relieved
# over roughly 120 degrees towards the north-east. Never cut through the floor.
axis = Vector((1.0, 1.0)).normalized()
across = Vector((-axis.y, axis.x))
gap = [tuple(POCKET)] + [tuple(POCKET + Vector((math.cos(math.radians(a)), math.sin(math.radians(a)))) * CASE_W * 2) for a in (-15, 105)]
cutter(prism("open-corner", gap, floor_z, CASE_H), case)
bev = case.modifiers.new("bevel", "BEVEL")
bev.width, bev.segments, bev.limit_method, bev.angle_limit = 0.7, 5, "ANGLE", math.radians(40)
bev.harden_normals = False
apply_modifiers(case)
normals = case.modifiers.new("Planar weighted normals", "WEIGHTED_NORMAL")
normals.keep_sharp = True
apply_modifiers(case)
case.data.materials.append(M["Body"])
case.data.materials.append(M["Side"])
case.data.materials.append(M["Well"])
assign_side(case, 1)
# The pocket walls read as part of the top surface in the photos.
for p in case.data.polygons:
    c = p.center
    if (Vector((c.x, c.y)) - POCKET).length < POCKET_R + .1 and abs(c.z-floor_z) < .1:
        p.material_index = 2
    if p.material_index == 1 and (Vector((c.x, c.y)) - POCKET).length < POCKET_R + 0.5 and c.z > floor_z - 0.1:
        p.material_index = 0
    delta = Vector((c.x, c.y)) - POCKET
    if p.material_index == 1 and delta.dot(axis) >= 0 and abs(delta.dot(across)) <= POCKET_R + 0.5 and c.z > floor_z - 0.1:
        p.material_index = 0
case.data.shade_smooth()
bpy.context.view_layer.objects.active = case
case.select_set(True)
bpy.ops.object.shade_auto_smooth(angle=math.radians(35))
case.select_set(False)
apply_modifiers(case)
for face in case.data.polygons:
    if abs(face.normal.z) > .999:
        face.use_smooth = False  # Keep the deck and full circular floor planar after the cuts.
normals = case.modifiers.new("Final corner normals", "WEIGHTED_NORMAL")
normals.keep_sharp = True
apply_modifiers(case)
case_parts = [case]
# Explicit planar annular insert avoids interpolated normals on the boolean-cut floor.
# It is a complete circle, as shown in owner photos 3/4, not a cut-through opening.
case_parts.append(cylinder("SeatingRing", 12.15, floor_z, floor_z+.015, "Well", POCKET))
# Central circular pad and the thin surrounding seam visible in the empty dock.
case_parts.append(cylinder("PadSeam", 9.55, floor_z, floor_z+.045, "Gap", POCKET))
case_parts.append(cylinder("ChargingPad", 9.40, floor_z+.025, floor_z+.09, "Magnet", POCKET, .025))
for index, angle in enumerate((230, 305)):
    a = math.radians(angle)
    pos = POCKET + Vector((math.cos(a), math.sin(a))) * 10.85
    case_parts.append(cylinder(f"ContactSocket{index}", .46, floor_z, floor_z+.08, "Gap", pos))
    case_parts.append(cylinder(f"ChargingPin{index}", .28, floor_z+.06, floor_z+.32, "Contacts", pos, .08))
# The case's mark is drawn as geometry from the user's own front photo, not an App asset.
logo = cylinder("CaseMarkRing", 2.65, CASE_H/2+.012, CASE_H/2+.045, "Logo", (-.8, -5.0))
cutter(cylinder("mark-hole", 1.18, CASE_H/2-1, CASE_H/2+1, centre=(-.8,-5.0)), logo)
case_parts.append(logo)
for name, poly in [("CaseMarkStem", [(0,-4.2),(1.25,-1.8),(2.85,-1.8),(1.8,-5.25)]),
                   ("CaseMarkDash", [(1.15,-.8),(3.1,-.45),(3.35,.85),(1.4,.5)])]:
    mark = prism(name, poly, CASE_H/2+.012, CASE_H/2+.045)
    mark.data.materials.append(M["Logo"])
    case_parts.append(mark)

# ---- Bean ----------------------------------------------------------------------------------
bean = bpy.data.objects.new("Bean", None)
scene.collection.objects.link(bean)
bean.location = (POCKET.x, POCKET.y, floor_z + .34 - CLIP_BACK)


def bean_profile():
    """(radius, z) from the back centre to the front centre; revolved around Z."""
    pts = [(0, BEAN_BACK)]
    c = 1.6
    pts += [(BEAN_R - c + c * math.sin(math.radians(a)), BEAN_BACK + c - c * math.cos(math.radians(a))) for a in range(0, 91, 15)]
    s = 2.4                                    # shoulder radius into the dome
    sz = BEAN_SIDE_TOP
    pts += [(BEAN_R - s + s * math.cos(math.radians(a)), sz + s * math.sin(math.radians(a))) for a in range(0, 76, 15)]
    r0, z0 = pts[-1]
    for i in range(1, 13):                     # shallow dome to the front centre
        t = i / 12
        r = r0 * (1 - t)
        pts.append((r, z0 + (BEAN_FRONT - z0) * (1 - (r / r0) ** 2)))
    return pts


bm = bmesh.new()
prof = bean_profile()
seg = 72
rings = []
for k in range(seg):
    a = 2 * math.pi * k / seg
    rings.append([bm.verts.new((r * math.cos(a), r * math.sin(a), z)) if r > 0 else None for r, z in prof])
back, front = bm.verts.new((0, 0, prof[0][1])), bm.verts.new((0, 0, prof[-1][1]))
for k in range(seg):
    A, B = rings[k], rings[(k + 1) % seg]
    n = len(prof)
    bm.faces.new((back, B[1], A[1]))
    for i in range(1, n - 2):
        bm.faces.new((A[i], B[i], B[i + 1], A[i + 1]))
    bm.faces.new((A[n - 2], B[n - 2], front))
body = obj_from_bm("BeanBody", bm, ["BeanFace", "BeanSide"], bean)
for p in body.data.polygons:
    if abs(p.normal.z) < 0.6 or p.center.z < 0:
        p.material_index = 1
backplate = cylinder("RearClipPlate", 11.6, CLIP_BACK, CLIP_FRONT, "Clip", bevel=.28)
backplate.parent = bean
for index, angle in enumerate((230,305)):
    a = math.radians(angle)
    pos = Vector((math.cos(a),math.sin(a)))*10.85
    pad = cylinder(f"RearContact{index}", .42, CLIP_BACK-.02, CLIP_BACK+.025, "Contacts", pos)
    pad.parent = bean


def surface(obj, origin, direction):
    """World hit and normal on obj (in its local space, no transforms on BeanBody)."""
    ok, loc, nor, _ = obj.ray_cast(origin, direction)
    assert ok, (obj.name, origin)
    return loc, nor


def place(o, loc, nor, up=Vector((0, 1, 0)), sink=0.0):
    z = nor.normalized()
    x = up.cross(z).normalized()
    y = z.cross(x)
    o.matrix_world = Matrix.Translation(loc - z * sink) @ Matrix((x, y, z)).transposed().to_4x4()


def capsule(name, length, width, height, mat, seg=10, rim=None):
    bm = bmesh.new()
    r = width / 2
    h = length / 2 - r
    outline = [(h + r * math.cos(math.radians(a)), r * math.sin(math.radians(a))) for a in range(-90, 91, 180 // seg)]
    outline += [(-h + r * math.cos(math.radians(a)), r * math.sin(math.radians(a))) for a in range(90, 271, 180 // seg)]
    top = [bm.verts.new((x, y, height)) for x, y in outline]
    bot = [bm.verts.new((x * 1.05, y * 1.05, -0.6)) for x, y in outline]
    bm.faces.new(top)
    bm.faces.new(list(reversed(bot)))
    for i in range(len(outline)):
        j = (i + 1) % len(outline)
        bm.faces.new((bot[i], bot[j], top[j], top[i]))
    o = obj_from_bm(name, bm, [mat] + ([rim] if rim else []), smooth_angle=50)
    if rim:
        for p in o.data.polygons:
            if p.normal.z < 0.9:
                p.material_index = 1
    return o


for name, y in (("GrilleTop", 9.55), ("GrilleBottom", -9.55)):
    loc, nor = surface(body, Vector((0, y, 20)), Vector((0, 0, -1)))
    # Silver rolled frame, dark recess and an actual perforated plate (14 holes).
    layers = [capsule(name+"Frame", 4.8, 1.85, .10, "Trim"),
              capsule(name+"Recess", 4.5, 1.58, .12, "Gap")]
    plate = capsule(name+"Plate", 4.35, 1.35, .15, "MeshMetal")
    for x in (-1.65,-1.10,-.55,0,.55,1.10,1.65):
        for yy in (-.29,.29):
            cutter(cylinder("mic-hole", .145, -1, 1, centre=(x,yy)), plate)
    layers.append(plate)
    for g in layers:
        place(g, loc, nor)
        g.parent = bean

led_bm = bmesh.new()
bmesh.ops.create_circle(led_bm, cap_ends=True, radius=0.40, segments=32)
led = obj_from_bm("Led", led_bm, ["Led"], smooth_angle=0)
loc, nor = surface(body, Vector((0, 7.45, 20)), Vector((0, 0, -1)))
place(led, loc + nor * 0.03, nor)
led.parent = bean


def on_side(name, angle_deg, z, obj):
    a = math.radians(angle_deg)
    n = Vector((math.cos(a), math.sin(a), 0))
    place(obj, n * BEAN_R + Vector((0, 0, z)), n, up=Vector((0, 0, 1)), sink=0.25)
    obj.parent = bean
    obj.name = name


# Record key: due right, a two-stage push-lock key (press = locked in, recording; press again = pops out).
# Rendered in the released position, standing proud of a dark shadow gap.
on_side("ButtonGap", 0, -1.6, capsule("ButtonGapMesh", 7.6, 3.6, 0.3, "Gap"))
on_side("Button", 0, -1.6, capsule("ButtonMesh", 6.8, 2.8, 1.1, "Button"))
pin = capsule("PinMesh", 0.7, 0.7, 0.05, "Grille", seg=6)
on_side("Pinhole", -100, -1.6, pin)

# Photo1/5: solid ribbed tab from the front. Photo2: its opening is in the SIDE
# elevation, between the recorder body and its own rear clip plate. No front-facing handle.
strap_top, strap_bottom, tip_y = -3.8, -7.2, 20.0
turn_r = (strap_top-strap_bottom)/2
cz = (strap_top+strap_bottom)/2
path = [Vector((0, y, strap_top)) for y in (8.8,12,16,tip_y)]
path += [Vector((0, tip_y+turn_r*math.sin(math.radians(a)), cz+turn_r*math.cos(math.radians(a)))) for a in range(15,181,15)]
path += [Vector((0, y, strap_bottom)) for y in (16,12,8.8)]
bm = bmesh.new()
W, T = 7.3, 1.4
section = [(W / 2 * math.copysign(abs(math.cos(math.radians(a))) ** 0.25, math.cos(math.radians(a))), T / 2 * math.sin(math.radians(a))) for a in range(0, 360, 30)]
rings = []
for i, p in enumerate(path):
    t = (path[min(i + 1, len(path) - 1)] - path[max(i - 1, 0)]).normalized()
    xa = Vector((1, 0, 0))
    na = t.cross(xa).normalized()
    rings.append([bm.verts.new(p + xa * sx + na * sy) for sx, sy in section])
for a, b in zip(rings, rings[1:]):
    for i in range(len(section)):
        j = (i + 1) % len(section)
        bm.faces.new((a[i], a[j], b[j], b[i]))
bm.faces.new(list(reversed(rings[0])))
bm.faces.new(rings[-1])
strap = obj_from_bm("Strap", bm, ["Strap"], bean, smooth_angle=60)


# ---- Surface detail ------------------------------------------------------------------------
def nodes(m):
    return m.node_tree.nodes, m.node_tree.links, m.node_tree.nodes["Principled BSDF"]


def grain(m, scale, strength):
    """Fine noise bump: bead-blasted plastic or brushed metal (stretched noise)."""
    n, l, b = nodes(m)
    tc = n.new("ShaderNodeTexCoord")
    tex = n.new("ShaderNodeTexNoise")
    tex.inputs["Scale"].default_value = scale
    tex.inputs["Detail"].default_value = 8
    l.new(tc.outputs["Object"], tex.inputs["Vector"])
    bump = n.new("ShaderNodeBump")
    bump.inputs["Strength"].default_value = strength
    bump.inputs["Distance"].default_value = 0.02
    l.new(tex.outputs["Fac"], bump.inputs["Height"])
    l.new(bump.outputs["Normal"], b.inputs["Normal"])


grain(M["Body"], 25.0, 0.05)      # bead-blasted / soft-touch
grain(M["Side"], 25.0, 0.04)
grain(M["Button"], 25.0, 0.04)
grain(M["BeanFace"], 28.0, 0.10)
grain(M["BeanSide"], 28.0, 0.07)
for k in ("Body", "Strap"):
    M[k].node_tree.nodes["Principled BSDF"].inputs["Sheen Weight"].default_value = 0.06

# Strap: silicone with fine diagonal ribs.
n, l, b = nodes(M["Strap"])
tc = n.new("ShaderNodeTexCoord")
wave = n.new("ShaderNodeTexWave")
wave.bands_direction = "DIAGONAL"
wave.inputs["Scale"].default_value = 0.9
wave.inputs["Distortion"].default_value = 0.0
l.new(tc.outputs["Object"], wave.inputs["Vector"])
bump = n.new("ShaderNodeBump")
bump.inputs["Strength"].default_value = 0.35
bump.inputs["Distance"].default_value = 0.06
l.new(wave.outputs["Fac"], bump.inputs["Height"])
l.new(bump.outputs["Normal"], b.inputs["Normal"])

# Mic grille: a regular field of holes (2D Voronoi with no jitter is a square grid of points).
n, l, b = nodes(M["Grille"])
tc = n.new("ShaderNodeTexCoord")
vor = n.new("ShaderNodeTexVoronoi")
vor.voronoi_dimensions = "2D"
vor.inputs["Scale"].default_value = 2.3
vor.inputs["Randomness"].default_value = 0.0
l.new(tc.outputs["Object"], vor.inputs["Vector"])
ramp = n.new("ShaderNodeValToRGB")
ramp.color_ramp.interpolation = "EASE"
ramp.color_ramp.elements[0].color = (0.002, 0.002, 0.002, 1)
ramp.color_ramp.elements[0].position = 0.26
ramp.color_ramp.elements[1].position = 0.34
l.new(vor.outputs["Distance"], ramp.inputs["Fac"])
mix = n.new("ShaderNodeMix")
mix.data_type = "RGBA"
l.new(ramp.outputs["Color"], mix.inputs["Factor"])
mix.inputs[6].default_value = (0.002, 0.002, 0.002, 1)   # hole
mix.inputs[7].default_value = (0.10, 0.10, 0.11, 1)      # mesh
l.new(mix.outputs["Result"], b.inputs["Base Color"])
l.new(ramp.outputs["Color"], b.inputs["Metallic"])
bump = n.new("ShaderNodeBump")
bump.inputs["Strength"].default_value = 0.6
bump.inputs["Distance"].default_value = 0.05
l.new(ramp.outputs["Color"], bump.inputs["Height"])
l.new(bump.outputs["Normal"], b.inputs["Normal"])
b.inputs["Roughness"].default_value = 0.35

FINISHES = {   # name: material -> (base colour, roughness, metallic); everything matte, as on the real unit
    "white": {"Body": ((0.70, 0.70, 0.69), 0.62, 0.0), "Side": ((0.62, 0.63, 0.64), 0.46, 1.0),
              "BeanFace": ((.68,.69,.70), .43, .35), "BeanSide": ((.56,.58,.61), .4, .7),
              "Clip": ((.57,.58,.60), .5, .3), "Well": ((.55,.56,.58), .48, .35), "Magnet": ((.62,.63,.64), .55, .3),
              "Button": ((0.62, 0.63, 0.64), 0.46, 1.0), "Strap": ((0.66, 0.66, 0.65), 0.72, 0.0)},
    "black": {"Body": ((0.022, 0.023, 0.026), 0.66, 0.0), "Side": ((0.03, 0.031, 0.035), 0.52, 0.0),
              "BeanFace": ((.065,.07,.085), .42, .5), "BeanSide": ((.045,.05,.06), .42, .5),
              "Clip": ((.025,.028,.032), .52, .15), "Well": ((.055,.058,.065), .62, .1), "Magnet": ((.04,.042,.048), .62, 0),
              "Button": ((0.034, 0.035, 0.04), 0.48, 0.0), "Strap": ((0.02, 0.02, 0.022), 0.74, 0.0)},
}


def finish(name, led=(1.0, 0.22, 0.01), led_strength=0.0):
    for k, (col, rough, metal) in FINISHES[name].items():
        p = M[k].node_tree.nodes["Principled BSDF"]
        if not p.inputs["Base Color"].is_linked:
            p.inputs["Base Color"].default_value = (*col, 1)
        p.inputs["Roughness"].default_value = rough
        p.inputs["Metallic"].default_value = metal
    p = M["Led"].node_tree.nodes["Principled BSDF"]
    p.inputs["Emission Color"].default_value = (*led, 1)
    p.inputs["Emission Strength"].default_value = led_strength


# ---- Staging: stood upright like the product shots, on a shadow-only floor -------------------
spin = bpy.data.objects.new("Spin", None)       # turntable axis (world Z through the case centre)
scene.collection.objects.link(spin)
rig = bpy.data.objects.new("Rig", None)
scene.collection.objects.link(rig)
rig.parent = spin
rig.rotation_euler = (math.radians(90), 0, 0)  # case top face -> camera (-Y), strap edge -> up
for part in case_parts:
    part.parent = rig
bean.parent = rig
FLOOR = -CASE_W / 2

bpy.ops.mesh.primitive_plane_add(size=2000, location=(0, 0, FLOOR))
floor = bpy.context.active_object
floor.is_shadow_catcher = True


def studio_hdr(path, w=1024, h=512):
    """Soft studio for reflections: warm-grey sweep, big key box upper left, rim strip, top fill."""
    img = bpy.data.images.new("studio", w, h, float_buffer=True)
    lights = [(math.radians(-125), math.radians(35), math.radians(26), 5.0),
              (math.radians(-20), math.radians(25), math.radians(14), 3.0),
              (math.radians(90), math.radians(80), math.radians(35), 1.6)]
    dirs = [(Vector((math.cos(e) * math.cos(a), math.cos(e) * math.sin(a), math.sin(e))), r, k) for a, e, r, k in lights]
    px = []
    for j in range(h):
        el = (j + 0.5) / h * math.pi - math.pi / 2
        ce, se = math.cos(el), math.sin(el)
        for i in range(w):
            az = (i + 0.5) / w * 2 * math.pi - math.pi
            t = (se + 1) / 2
            v = [0.06 + 0.32 * t] * 3          # neutral grey sweep
            d = Vector((ce * math.cos(az), ce * math.sin(az), se))
            for ld, rad, k in dirs:
                ang = d.angle(ld)
                if ang < rad:
                    f = min(1.0, (rad - ang) / (rad * 0.4)) * k
                    v = [c + f for c in v]
            px += [*v, 1.0]
    img.pixels = px
    img.filepath_raw = path
    img.file_format = "HDR"
    img.save()
    return bpy.data.images.load(path)


world = bpy.data.worlds.new("studio")
scene.world = world
world.use_nodes = True
env = world.node_tree.nodes.new("ShaderNodeTexEnvironment")
env.image = studio_hdr(os.path.join(SCRATCH, "device_studio.hdr"))
world.node_tree.links.new(env.outputs["Color"], world.node_tree.nodes["Background"].inputs["Color"])
world.node_tree.nodes["Background"].inputs["Strength"].default_value = 0.45


def area(name, loc, size, energy, color=(1, 1, 1)):
    lamp = bpy.data.lights.new(name, "AREA")
    lamp.shape, lamp.size, lamp.energy, lamp.color = "DISK", size, energy, color
    o = bpy.data.objects.new(name, lamp)
    scene.collection.objects.link(o)
    o.location = loc
    o.rotation_euler = (Vector((0, 0, 0)) - Vector(loc)).to_track_quat("-Z", "Y").to_euler()


# Scene units are mm, so light power scales with distance squared (~1e6 W at 300 mm).
# Product-page lighting: big overhead softbox (short soft shadow), soft front fill, and two side strips
# that draw the edges of a dark matte object.
area("Top", (0, -70, 330), 420, 1.7e6)
area("Front", (-140, -330, 90), 320, 0.6e6)
area("StripL", (-320, 20, 70), 160, 0.8e6)
area("StripR", (320, 60, 70), 160, 0.8e6)

cam = bpy.data.objects.new("Camera", bpy.data.cameras.new("Camera"))
scene.collection.objects.link(cam)
scene.camera = cam
cam.data.sensor_width = 36


def aim(yaw, pitch, dist, target, lens):
    """Camera on a sphere round target: yaw 0 = straight at the case face, positive = from the right."""
    y, p = math.radians(yaw), math.radians(pitch)
    cam.location = Vector(target) + dist * Vector((math.sin(y) * math.cos(p), -math.cos(y) * math.cos(p), math.sin(p)))
    cam.rotation_euler = (Vector(target) - cam.location).to_track_quat("-Z", "Y").to_euler()
    cam.data.lens = lens


r = scene.render
r.engine = "CYCLES"
r.film_transparent = True
r.image_settings.file_format = "PNG"
r.image_settings.color_mode = "RGBA"
r.image_settings.color_depth = "8"
scene.view_settings.view_transform = "AgX"
scene.view_settings.look = "AgX - Medium High Contrast"
cyc = scene.cycles
prefs = bpy.context.preferences.addons["cycles"].preferences
prefs.compute_device_type = "METAL"
prefs.get_devices()
for d in prefs.devices:
    d.use = d.type == "METAL"
cyc.device = "GPU"
cyc.use_denoising = True
cyc.denoiser = "OPENIMAGEDENOISE"
cyc.max_bounces = 8
cyc.glossy_bounces = 6
cyc.transparent_max_bounces = 8
os.makedirs(OUT, exist_ok=True)

# Checks directly cover the two corrected structures, before accepting any renders.
strap_low = min(v.co.z for v in strap.data.vertices)
assert strap_low >= CLIP_BACK-.001, "Clip band must stay within its own rear-plate stack"
front_band = strap.ray_cast(Vector((0,15,20)), Vector((0,0,-1)))[0]
side_hole = strap.ray_cast(Vector((12,16,cz)), Vector((-1,0,0)))[0]
assert front_band and not side_hole, "Tab must be solid from front and open from side"
outside = POCKET + axis * 6
inside = POCKET - axis * 6
outer_hit = case.ray_cast(Vector((outside.x, outside.y, CASE_H * 2)), Vector((0, 0, -1)))[0]
inner_hit = case.ray_cast(Vector((inside.x, inside.y, CASE_H * 2)), Vector((0, 0, -1)))[0]
assert outer_hit and inner_hit, "The complete circular seating floor must remain"
entry = POCKET + axis*30
wall = case.ray_cast(Vector((entry.x,entry.y,floor_z+2)), Vector((-axis.x,-axis.y,0)))
assert wall[0] and (Vector((wall[1].x,wall[1].y))-POCKET).dot(axis)<0, "No outer raised wall across the bay entry"
manifold = {}
for obj in (case, body, strap, backplate):
    check = bmesh.new()
    check.from_mesh(obj.data)
    manifold[obj.name] = all(e.is_manifold for e in check.edges)
    check.free()
assert all(manifold.values()), manifold
with open(os.path.join(OUT, "geometry-validation.json"), "w") as f:
    json.dump(dict(case_width_mm=CASE_W, case_depth_mm=CASE_H, case_radius_mm=CASE_R,
                   pocket_center_mm=list(POCKET), strap_min_bean_z_mm=strap_low,
                   bean_back_z_mm=BEAN_BACK, clip_back_z_mm=CLIP_BACK, outer_seat_floor_hit=outer_hit,
                   inner_seat_ray_hit=inner_hit, front_solid_tab_hit=front_band,
                   side_loop_hit=side_hole, charging_pins=2, manifold=manifold), f, indent=2)


def shoot(path, size, samples):
    r.resolution_x = r.resolution_y = size
    cyc.samples = samples
    r.filepath = path
    bpy.ops.render.render(write_still=True)


HERO = dict(yaw=22, pitch=8, dist=250, target=(1, 0, 2), lens=85)        # case + docked bean
BEAN = dict(yaw=8, pitch=4, dist=125, target=(17.5, -7, 22), lens=95)


def bean_only(on):
    for part in case_parts:
        part.hide_render = on
    strap.hide_render = False  # The loop belongs to the bean, including bean-only images.
    floor.hide_render = on


if JOB == "preview":
    finish("white")
    aim(**HERO)
    shoot(os.path.join(OUT, "preview-hero.png"), 600, 48)
    finish("black")
    shoot(os.path.join(OUT, "preview-hero-black.png"), 600, 48)
    for yaw in (65, 135, 225, 300):
        aim(yaw=yaw, pitch=18, dist=250, target=(1, 0, 4), lens=85)
        shoot(os.path.join(OUT, f"preview-corner-{yaw}.png"), 600, 48)
    bean_only(True)
    aim(**BEAN)
    shoot(os.path.join(OUT, "preview-bean-black.png"), 600, 48)
    aim(yaw=88,pitch=0,dist=125,target=(17.5,-7,22),lens=95)
    shoot(os.path.join(OUT, "preview-bean-side.png"), 800, 64)
    aim(yaw=0,pitch=0,dist=125,target=(17.5,-7,22),lens=95)
    shoot(os.path.join(OUT, "preview-bean-front.png"), 800, 64)
    bean_only(False)
    for obj in bean.children:
        obj.hide_render = True
    aim(**HERO)
    shoot(os.path.join(OUT, "preview-empty-dock.png"), 800, 64)
    for obj in bean.children:
        obj.hide_render = False
    aim(**HERO)
    bpy.ops.file.pack_all()
    bpy.ops.wm.save_as_mainfile(filepath=os.path.join(OUT, "device-model.blend"))
if JOB in ("stills", "assets"):
    for f in ("white", "black"):
        finish(f)
        bean_only(False)
        aim(**HERO)
        shoot(os.path.join(OUT, f"device_{f}_front.png"), 1200, 384)
        bean_only(True)
        aim(**BEAN)
        shoot(os.path.join(OUT, f"bean_{f}_front.png"), 900, 384)
if JOB in ("spin", "assets"):
    # One seamless revolution: frame N would equal frame 0, so it is left out.
    bean_only(False)  # Stills end with bean-only; the pairing loop must include the case again.
    frames = int(os.environ.get("SPIN_FRAMES", "96"))
    aim(yaw=0, pitch=10, dist=215, target=(0, 0, 3), lens=85)
    for f in os.environ.get("SPIN_FINISHES", "white black").split():
        finish(f)
        for i in range(frames):
            spin.rotation_euler.z = 2 * math.pi * i / frames
            shoot(os.path.join(OUT, "spin", f, f"{i:03d}.png"), int(os.environ.get("SPIN_SIZE", "520")), int(os.environ.get("SPIN_SAMPLES", "40")))
if JOB == "assets":
    spin.rotation_euler.z = 0
    bean_only(False)
    finish("white")
    aim(**HERO)
    bpy.ops.file.pack_all()
    bpy.ops.wm.save_as_mainfile(filepath=os.path.join(OUT, "device-model.blend"))
print("done", JOB, OUT)
