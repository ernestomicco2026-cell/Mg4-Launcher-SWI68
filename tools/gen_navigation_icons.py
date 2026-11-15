"""Generate launcher vectors for MG codes 0-39 verified in the original navigation APK.

Run from the repository root: python3 tools/gen_navigation_icons.py
Artwork follows the launcher's 24x24, 1.8px rounded stroke style.
These are new drawings, not recovered MG artwork.
"""
from pathlib import Path
from html import escape

ROOT = Path(__file__).resolve().parents[1]
# EN: Follow the APK's code meanings, not enum ordinals or instruction text. Code 0 always stays straight.
# IT: Segui i significati dei codici nell'APK, non gli indici enum o il testo. Il codice 0 resta sempre dritto.
MERGE_LEFT = "M17 22V3M13 7l4-4 4 4M3 20l7-5q7-5 7-12"
MERGE_RIGHT = "M7 22V3M3 7l4-4 4 4M21 20l-7-5Q7 10 7 3"
ICONS = [
    (0, "straight", "Straight ahead", "M12 21V3M7 8l5-5 5 5"),
    (1, "left", "Turn left", "M17 21V11a3 3 0 0 0-3-3H4M8 4L4 8l4 4"),
    (2, "right", "Turn right", "M7 21V11a3 3 0 0 1 3-3h10M16 4l4 4-4 4"),
    (3, "bear_left", "Direction to left", "M16 21v-7a4 4 0 0 0-1.2-2.8L6 3M6 9V3h6"),
    (4, "bear_right", "Direction Right", "M8 21v-7a4 4 0 0 1 1.2-2.8L18 3M12 3h6v6"),
    (5, "uturn", "U-turn", "M18 21V8a6 6 0 0 0-12 0v10M2 14l4 4 4-4"),
    (6, "uturn_right", "Right U-turn", "M6 21V8a6 6 0 0 1 12 0v10M14 14l4 4 4-4"),
    (7, "sharp_right", "Sharp right (4 o'clock)", "M7 22V7a3 3 0 0 1 5-2l8 13M14 18h6v-6"),
    (8, "sharp_left", "Sharp left (8 o'clock)", "M17 22V7a3 3 0 0 0-5-2L4 18M4 12v6h6"),
    (9, "roundabout_straight", "Roundabout: straight", "M12 22v-5a5 5 0 0 0 0-10V2M8 6l4-4 4 4"),
    (10, "roundabout_left", "Roundabout: 9 o'clock", "M12 22v-5a5 5 0 1 0-5-5H2M6 8l-4 4 4 4"),
    (11, "roundabout_right", "Roundabout: 3 o'clock", "M12 22v-5a5 5 0 0 0 5-5h5M18 8l4 4-4 4"),
    (12, "roundabout_uturn", "Roundabout: U-turn", "M13 22v-5a5 5 0 1 0-5-5v9M4 17l4 4 4-4"),
    (13, "roundabout_one", "Roundabout: 1 o'clock", "M12 22v-5a5 5 0 0 0 3.54-8.54L21 3M15 3h6v6"),
    (14, "roundabout_four", "Roundabout: 4 o'clock", "M12 22v-5a5 5 0 1 1 4-2l5 5M16 20h5v-5"),
    (15, "roundabout_eight", "Roundabout: 8 o'clock", "M12 22v-5a5 5 0 1 0-4-2l-5 5M3 15v5h5"),
    (16, "roundabout_eleven", "Roundabout: 11 o'clock", "M12 22v-5a5 5 0 0 1-3.54-8.54L3 3M3 9V3h6"),
    (17, "keep_left", "Keep left", "M12 21v-9l-7-7M5 11V5h6M12 15l7-7"),
    (18, "keep_right", "Keep right", "M12 21v-9l7-7M13 5h6v6M12 15L5 8"),
    (19, "ferry", "Ferry", "M4 17l3 4h10l3-4-8-3-8 3M7 15V8h10v7M10 8V4h4v4M3 22l3-1 3 1 3-1 3 1 3-1 3 1"),
    (20, "destination_ahead", "Destination ahead", "M7 22V4M3 8l4-4 4 4M15 22V3h7l-2 3 2 3h-7"),
    (21, "destination", "Destination", "M6 22V3h13l-3 4 3 4H6M10 3v8M14 3v8M6 7h11"),
    (22, "destination_right", "Destination on right", "M3 22v-7h11M10 11l4 4-4 4M18 22V3h4l-2 3 2 3h-4"),
    # EN: The native app shares artwork for EnterRight/MergeLeft and EnterLeft/MergeRight.
    # IT: L'app originale condivide le icone per EnterRight/MergeLeft e EnterLeft/MergeRight.
    (32, "merge_left", "Enter right", MERGE_LEFT),
    (33, "merge_left", "Merge left", MERGE_LEFT),
    (34, "merge_right", "Enter left", MERGE_RIGHT),
    (35, "merge_right", "Merge right", MERGE_RIGHT),
    (36, "exit_left", "Exit left", "M17 21V3M17 16L4 3M4 9V3h6"),
    (37, "exit_right", "Exit right", "M7 21V3M7 16l13-13M14 3h6v6"),
    (38, "toll_booth", "Pass toll booth", "M3 9h18M5 9v13M19 9v13M8 9V3h8v6M9 21v-8h6v8"),
    (39, "hook_right", "Hook turn right", "M12 22v-7L5 9V4h16M17 1l4 3-4 3"),
    (None, "unknown", "Unknown code", "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20M9 8a3 3 0 1 1 4 2.83c-1 .35-1 1.17-1 2.17M12 17h0.01"),
]

# EN: Three waypoint numbers, each with ahead/left/right arrival, match native codes 23-31.
# IT: Tre numeri di tappa, ciascuno con arrivo davanti/sinistra/destra, corrispondono ai codici 23-31.
WAYPOINT_ARROWS = {
    "ahead": "M9 22V12M5 16l4-4 4 4",
    "left": "M12 22v-7H2M6 11l-4 4 4 4",
    "right": "M3 22v-7h11M10 11l4 4-4 4",
}
WAYPOINT_DIGITS = {1: "M17 5l2-1v4", 2: "M17 4h3v2h-3v2h3", 3: "M17 4h3v4h-3M18 6h2"}
WAYPOINT_GLYPHS = {}
for waypoint in range(1, 4):
    for side, direction in enumerate(("ahead", "left", "right")):
        code = 23 + (waypoint - 1) * 3 + side
        name = f"waypoint_{waypoint}_{direction}"
        path = WAYPOINT_ARROWS[direction] + "M15 12V2h7v8h-7"
        WAYPOINT_GLYPHS[name] = WAYPOINT_DIGITS[waypoint]
        ICONS.append((code, name, f"Waypoint {waypoint}: {direction}", path))
ICONS.sort(key=lambda item: 40 if item[0] is None else item[0])

paint = ('android:fillColor="#00000000" android:strokeColor="#FFFFFFFF" '
         'android:strokeWidth="1.8" android:strokeLineCap="round" '
         'android:strokeLineJoin="round"')
out = ROOT / "app/res/drawable"
docs = ROOT / "docs/navigation"
docs.mkdir(parents=True, exist_ok=True)
height = ((len(ICONS) + 4) // 5) * 180
preview = [f'<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="{height}" viewBox="0 0 1000 {height}">',
           f'<rect width="1000" height="{height}" fill="#17212b"/>']
for i, (code, name, label, path) in enumerate(ICONS):
    # EN: Thinner numeral strokes keep small waypoint numbers readable inside their flags.
    # IT: Tratti piu sottili rendono leggibili i piccoli numeri delle tappe nelle bandiere.
    glyph = WAYPOINT_GLYPHS.get(name)
    glyph_paint = paint.replace('strokeWidth="1.8"', 'strokeWidth="1.2"')
    glyph_xml = f'    <path android:pathData="{glyph}" {glyph_paint}/>\n' if glyph else ''
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="24dp" android:height="24dp"\n'
           '    android:viewportWidth="24" android:viewportHeight="24">\n'
           f'    <path android:pathData="{path}" {paint}/>\n' + glyph_xml + '</vector>\n')
    (out / f"ic_maneuver_{name}.xml").write_text(xml, encoding="utf-8")
    x, y = (i % 5) * 200, (i // 5) * 180
    preview += [f'<g transform="translate({x+64} {y+18}) scale(3)">',
                f'<path d="{path}" fill="none" stroke="white" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>',
                (f'<path d="{glyph}" fill="none" stroke="white" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/>' if glyph else ''), '</g>',
                f'<text x="{x+100}" y="{y+116}" text-anchor="middle" fill="#a9c8e5" font-family="sans-serif" font-size="16">Code {code if code is not None else "?"}</text>',
                f'<text x="{x+100}" y="{y+142}" text-anchor="middle" fill="white" font-family="sans-serif" font-size="13">{escape(label)}</text>']
preview.append('</svg>')
(docs / "maneuver-icons.svg").write_text('\n'.join(preview) + '\n', encoding="utf-8")
print(f"Generated {len(set(item[1] for item in ICONS))} navigation vectors covering codes 0-39 and the unknown fallback")
