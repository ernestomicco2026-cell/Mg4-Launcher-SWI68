# Navigation icon analysis

The launcher maps all **40 outgoing codes (0–39)** defined by the original MG4
navigation APK **6.2.60.1.45160**, package `com.telenav.app.arp`. Codes outside
that verified range retain the unknown icon and manual reporting action.

The full numeric code, exact outgoing English instruction, meaning and special
conditions are recorded in [native-maneuver-codes.csv](native-maneuver-codes.csv).
These are definitions verified in this installed APK, not a claim that all 40
were observed while driving or that future navigation versions cannot add codes.

IT: Il launcher gestisce tutti i **40 codici (0–39)** verificati nell'APK originale
**6.2.60.1.45160**. I codici esterni all'intervallo mantengono l'icona sconosciuta
e la segnalazione manuale. La tabella CSV contiene codici, testi originali e
condizioni; non significa che ogni codice sia stato osservato durante la guida.

## APK evidence and implementation

APK SHA-256: `7463d36b6ae6ac81b3b448f9f2fd51066ffd36912c534fd293cdc70bedf88bc0`.
The notification builder `com.telenav.arp.module.notification.h` obtains the
outgoing code through its superclass `a.h()`, which delegates to the configured
turn-icon converter. `com.telenav.arp.app.as` supplies the code and fixed text;
notification performer `e` sends them to `SAICMapServiceWrapper.notifyManeuverInfo`.
The recovered definitions agree with the previously observed associations below.
Original drawable names were also checked in the base turn-icon delegate and
resource table, but the launcher uses its own matching vector artwork.

- `4` says **Direction Right**, without “to”. The diagnostic text allowlist
  recognizes this exact spelling and retains the previous alias.
- `5` and `6` distinguish left/right U-turns, although both texts say “U-Turn”.
- `7` and `8` are sharp right/left turns, described as 4 and 8 o'clock.
- Roundabout codes `9–16` are angular categories, not numbered exits.
- Destination codes `20/21/22` and each waypoint group `23–25`, `26–28`, `29–31`
  represent arrival ahead/left/right. Existing code-21 artwork is preserved.
- The native app shares artwork for `32/33` (Enter Right/Merge Left) and `34/35`
  (Enter Left/Merge Right); the launcher preserves those pairings.
- Diagnostic reports accept only fixed instruction categories; arbitrary
  navigation text, road names and destinations remain excluded.

IT: La catena di chiamate conferma i numeri inviati al servizio MG. Sono distinte
le inversioni destra/sinistra, gli ulteriori angoli delle rotonde e gli arrivi
alle tre tappe. Le coppie ingresso/confluenza condividono le icone come nell'app
originale. I rapporti registrano solo categorie fisse, mai testo libero o strade.

## Earlier driving evidence

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
Confirmed mapping: 0 = straight-ahead / continue guidance. The recovered APK
also explicitly returns code 0 for continue guidance. The precise distance
threshold remains unverified.
The accompanying text may name the upcoming action while the arrow means
continue for now. Do not replace the code-0 arrow based solely on that text.

IT: Il codice 0 resta sempre dritto/continua, anche quando il testo annuncia una
manovra lontana. Non sostituire la freccia in base al solo testo dell'istruzione.

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
All codes verified in this APK and the unknown fallback have assets. Code 0
remains straight/continue. HomeView uses ManeuverIcons.drawable(code)
to select these assets. Codes outside 0–39 use the unknown drawable and expose
a manual diagnostic report action. Phone metadata has no parsed maneuver code,
so phone navigation uses the unknown icon without claiming a known MG code.

| Code | Drawable in app/res/drawable |
|---|---|
| 0 | ic_maneuver_straight.xml |
| 1 | ic_maneuver_left.xml |
| 2 | ic_maneuver_right.xml |
| 3 | ic_maneuver_bear_left.xml |
| 4 | ic_maneuver_bear_right.xml |
| 5 | ic_maneuver_uturn.xml |
| 6 | ic_maneuver_uturn_right.xml |
| 7 | ic_maneuver_sharp_right.xml |
| 8 | ic_maneuver_sharp_left.xml |
| 9 | ic_maneuver_roundabout_straight.xml |
| 10 | ic_maneuver_roundabout_left.xml |
| 11 | ic_maneuver_roundabout_right.xml |
| 12 | ic_maneuver_roundabout_uturn.xml |
| 13 | ic_maneuver_roundabout_one.xml |
| 14 | ic_maneuver_roundabout_four.xml |
| 15 | ic_maneuver_roundabout_eight.xml |
| 16 | ic_maneuver_roundabout_eleven.xml |
| 17 | ic_maneuver_keep_left.xml |
| 18 | ic_maneuver_keep_right.xml |
| 19 | ic_maneuver_ferry.xml |
| 20 | ic_maneuver_destination_ahead.xml |
| 21 | ic_maneuver_destination.xml |
| 22 | ic_maneuver_destination_right.xml |
| 23 | ic_maneuver_waypoint_1_ahead.xml |
| 24 | ic_maneuver_waypoint_1_left.xml |
| 25 | ic_maneuver_waypoint_1_right.xml |
| 26 | ic_maneuver_waypoint_2_ahead.xml |
| 27 | ic_maneuver_waypoint_2_left.xml |
| 28 | ic_maneuver_waypoint_2_right.xml |
| 29 | ic_maneuver_waypoint_3_ahead.xml |
| 30 | ic_maneuver_waypoint_3_left.xml |
| 31 | ic_maneuver_waypoint_3_right.xml |
| 32 | ic_maneuver_merge_left.xml |
| 33 | ic_maneuver_merge_left.xml |
| 34 | ic_maneuver_merge_right.xml |
| 35 | ic_maneuver_merge_right.xml |
| 36 | ic_maneuver_exit_left.xml |
| 37 | ic_maneuver_exit_right.xml |
| 38 | ic_maneuver_toll_booth.xml |
| 39 | ic_maneuver_hook_right.xml |
| Unknown | ic_maneuver_unknown.xml |
