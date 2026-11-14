# Navigation icon analysis

Analyzed 243 uploaded snapshots from 2026-11-14, filenames 16-53-24 through
18-20-39, installation afe480df. Times come from the car.
Repeated history appears in multiple snapshots; counts are not counts of maneuvers.
Verification means a numeric code and text were received together. Original MG
artwork was not captured. Observed 14 distinct codes.

## Verified associations

| Code | Exact received text |
|---|---|
| 1 | Turn Left |
| 2 | Turn Right |
| 3 | Direction to Left |
| 5 | U-Turn |
| 9 | Go straight (At a rotary) |
| 10 | To left 9 o’clock (At a rotary) |
| 11 | To right 3 o’clock (At a rotary) |
| 12 | U-Turn (At a rotary) |
| 13 | To right 1 o’clock (At a rotary) |
| 17 | Keep Left |
| 18 | Keep Right |
| 21 | Reach destination |
| 37 | Exit Right |

Evidence filenames, line numbers, distance ranges and snapshot counts are in
navigation-icon-evidence.csv.

## Code 0: straight-ahead / continue guidance (user observation)

Code 0 occurs with empty text at 0 m, Exit Right at 7100–14000 m, and Go straight
(At a rotary) at 3200–14000 m. Both push and polling contain these associations.
The driver recalls the car showing a straight-ahead arrow for code 0, including
long stretches before the next exit, then switching to the maneuver arrow on
approach. This is consistent with the recorded distances and changing codes.
Working mapping: 0 = straight-ahead / continue guidance. Its artwork meaning is
supported by the driver's recollection, not established by the log text alone;
the distance threshold and any other uses remain unknown.
The accompanying text may name the upcoming action while the arrow means
continue for now. Do not replace the code-0 arrow based solely on that text.

## Unobserved candidates: inference only

| Possible maneuver | Tentative code | Basis / confidence |
|---|---|---|
| Direction to Right | 4 | Moderate: counterpart to 3, analogous to observed left/right pairs 1/2 and 17/18. |
| Exit Left | 36 or another code | Low: possible neighbor to Exit Right=37, but numbering order is unknown. |
| Other roundabout angles, e.g. 5, 7 or 11 o’clock | Possibly 14–16 or other codes | Low: 9–13 include roundabout instructions; exact assignments cannot be inferred. |
| Sharp left/right, ordinary straight-ahead, merge, waypoint arrival | Unknown | Possible maneuver families absent from observed text; existence and code numbers unverified. |

Missing numbers do not prove supported icons. No complete enumeration is available.

## Other findings

- Native navigation reports com.telenav.app.arp, road names, remaining distance/time and guidance status.
- No carplay.navigazione, IS_TBT_ACTIVE or ROAD_AFTER_JUNCTION metadata was found.
  Android Auto media activity does not prove phone turn-by-turn metadata availability.
- nav.push.altro numbers identify service callbacks, not arrows.
- Raw snapshots are in the separate, parent-ignored repository at
  `../../temp-mg4-debug/`. Evidence CSV paths are relative to that repository.
  This report and the evidence table live in the launcher's documentation so
  the mapping can be maintained with the implementation.

## Generated vector assets

Preview: [maneuver-icons.svg](maneuver-icons.svg). Regenerate with
`python tools/gen_navigation_icons.py` from the repository root.
These drawings match the launcher style; they are not copies of MG artwork.
Only observed codes and the unknown fallback have assets. Code 0 uses the
driver's straight-ahead observation. HomeView uses ManeuverIcons.drawable(code)
to select these assets. All other codes use the unknown drawable and expose
a manual diagnostic report action. Phone metadata has no parsed maneuver code,
so phone navigation uses the unknown icon without claiming a known MG code.

| Code | Drawable in app/res/drawable |
|---|---|
| 0 | ic_maneuver_straight.xml |
| 1 | ic_maneuver_left.xml |
| 2 | ic_maneuver_right.xml |
| 3 | ic_maneuver_bear_left.xml |
| 5 | ic_maneuver_uturn.xml |
| 9 | ic_maneuver_roundabout_straight.xml |
| 10 | ic_maneuver_roundabout_left.xml |
| 11 | ic_maneuver_roundabout_right.xml |
| 12 | ic_maneuver_roundabout_uturn.xml |
| 13 | ic_maneuver_roundabout_one.xml |
| 17 | ic_maneuver_keep_left.xml |
| 18 | ic_maneuver_keep_right.xml |
| 21 | ic_maneuver_destination.xml |
| 37 | ic_maneuver_exit_right.xml |
| Unknown | ic_maneuver_unknown.xml |
