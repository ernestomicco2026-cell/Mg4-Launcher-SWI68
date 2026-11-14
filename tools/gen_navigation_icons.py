"""Generate vectors only for observed MG maneuver codes, plus an unknown fallback.

Run from the repository root: python3 tools/gen_navigation_icons.py
Artwork follows the launcher's 24x24, 1.8px rounded stroke style.
These are new drawings, not recovered MG artwork.
"""
from pathlib import Path
from html import escape

ROOT = Path(__file__).resolve().parents[1]
ICONS = [
    (0, "straight", "Straight ahead", "M12 21V3M7 8l5-5 5 5"),
    (1, "left", "Turn left", "M17 21V11a3 3 0 0 0-3-3H4M8 4L4 8l4 4"),
    (2, "right", "Turn right", "M7 21V11a3 3 0 0 1 3-3h10M16 4l4 4-4 4"),
    (3, "bear_left", "Direction to left", "M16 21v-7a4 4 0 0 0-1.2-2.8L6 3M6 9V3h6"),
    (5, "uturn", "U-turn", "M18 21V8a6 6 0 0 0-12 0v10M2 14l4 4 4-4"),
    (9, "roundabout_straight", "Roundabout: straight", "M12 22v-5a5 5 0 0 0 0-10V2M8 6l4-4 4 4"),
    (10, "roundabout_left", "Roundabout: 9 o'clock", "M12 22v-5a5 5 0 1 0-5-5H2M6 8l-4 4 4 4"),
    (11, "roundabout_right", "Roundabout: 3 o'clock", "M12 22v-5a5 5 0 0 0 5-5h5M18 8l4 4-4 4"),
    (12, "roundabout_uturn", "Roundabout: U-turn", "M13 22v-5a5 5 0 1 0-5-5v9M4 17l4 4 4-4"),
    (13, "roundabout_one", "Roundabout: 1 o'clock", "M12 22v-5a5 5 0 0 0 3.54-8.54L21 3M15 3h6v6"),
    (17, "keep_left", "Keep left", "M12 21v-9l-7-7M5 11V5h6M12 15l7-7"),
    (18, "keep_right", "Keep right", "M12 21v-9l7-7M13 5h6v6M12 15L5 8"),
    (21, "destination", "Destination", "M6 22V3h13l-3 4 3 4H6M10 3v8M14 3v8M6 7h11"),
    (37, "exit_right", "Exit right", "M7 21V3M7 16l13-13M14 3h6v6"),
    (None, "unknown", "Unknown code", "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20M9 8a3 3 0 1 1 4 2.83c-1 .35-1 1.17-1 2.17M12 17h0.01"),
]

paint = ('android:fillColor="#00000000" android:strokeColor="#FFFFFFFF" '
         'android:strokeWidth="1.8" android:strokeLineCap="round" '
         'android:strokeLineJoin="round"')
out = ROOT / "app/res/drawable"
docs = ROOT / "docs/navigation"
docs.mkdir(parents=True, exist_ok=True)
preview = ['<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="540" viewBox="0 0 1000 540">',
           '<rect width="1000" height="540" fill="#17212b"/>']
for i, (code, name, label, path) in enumerate(ICONS):
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="24dp" android:height="24dp"\n'
           '    android:viewportWidth="24" android:viewportHeight="24">\n'
           f'    <path android:pathData="{path}" {paint}/>\n</vector>\n')
    (out / f"ic_maneuver_{name}.xml").write_text(xml, encoding="utf-8")
    x, y = (i % 5) * 200, (i // 5) * 180
    preview += [f'<g transform="translate({x+64} {y+18}) scale(3)">',
                f'<path d="{path}" fill="none" stroke="white" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>', '</g>',
                f'<text x="{x+100}" y="{y+116}" text-anchor="middle" fill="#a9c8e5" font-family="sans-serif" font-size="16">Code {code if code is not None else "?"}</text>',
                f'<text x="{x+100}" y="{y+142}" text-anchor="middle" fill="white" font-family="sans-serif" font-size="13">{escape(label)}</text>']
preview.append('</svg>')
(docs / "maneuver-icons.svg").write_text('\n'.join(preview) + '\n', encoding="utf-8")
print(f"Generated {len(ICONS)} navigation vectors and docs/navigation/maneuver-icons.svg")
