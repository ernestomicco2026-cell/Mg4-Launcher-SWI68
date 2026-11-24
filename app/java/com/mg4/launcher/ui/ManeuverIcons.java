package com.mg4.launcher.ui;

import com.mg4.launcher.R;

/**
 * EN: Codes 0-39 are verified in the original MG navigation APK; see docs/navigation/native-maneuver-codes.csv.
 * Code 0 always means straight/continue, even when the text describes a distant next maneuver.
 * IT: I codici 0-39 sono verificati nell'APK originale; vedi docs/navigation/native-maneuver-codes.csv.
 * Il codice 0 indica sempre dritto/continua, anche se il testo descrive una manovra successiva lontana.
 */
public final class ManeuverIcons {
    private ManeuverIcons() {}

    /**
     * 1.0.6: nome fisso della manovra dal CODICE (es. 18 → keep_right), preso dal nome dell'icona. Il vecchio nome del
     * log veniva dal testo dell'istruzione, che è nella lingua del navigatore: per questo 16, 18 e 21 risultavano
     * "unknown" anche con l'icona giusta.
     */
    public static String name(android.content.res.Resources r, int code) {
        int id = drawable(code);
        if (id == R.drawable.ic_maneuver_unknown) return "unknown";
        try {
            return r.getResourceEntryName(id).replace("ic_maneuver_", "");
        } catch (Exception e) {
            return "unknown";
        }
    }

    public static int drawable(int code) {
        switch (code) {
            case 0: return R.drawable.ic_maneuver_straight;
            case 1: return R.drawable.ic_maneuver_left;
            case 2: return R.drawable.ic_maneuver_right;
            case 3: return R.drawable.ic_maneuver_bear_left;
            case 4: return R.drawable.ic_maneuver_bear_right;
            case 5: return R.drawable.ic_maneuver_uturn;
            case 6: return R.drawable.ic_maneuver_uturn_right;
            case 7: return R.drawable.ic_maneuver_sharp_right;
            case 8: return R.drawable.ic_maneuver_sharp_left;
            case 9: return R.drawable.ic_maneuver_roundabout_straight;
            case 10: return R.drawable.ic_maneuver_roundabout_left;
            case 11: return R.drawable.ic_maneuver_roundabout_right;
            case 12: return R.drawable.ic_maneuver_roundabout_uturn;
            case 13: return R.drawable.ic_maneuver_roundabout_one;
            case 14: return R.drawable.ic_maneuver_roundabout_four;
            case 15: return R.drawable.ic_maneuver_roundabout_eight;
            case 16: return R.drawable.ic_maneuver_roundabout_eleven;
            case 17: return R.drawable.ic_maneuver_keep_left;
            case 18: return R.drawable.ic_maneuver_keep_right;
            case 19: return R.drawable.ic_maneuver_ferry;
            case 20: return R.drawable.ic_maneuver_destination_ahead;
            case 21: return R.drawable.ic_maneuver_destination;
            case 22: return R.drawable.ic_maneuver_destination_right;
            case 23: return R.drawable.ic_maneuver_waypoint_1_ahead;
            case 24: return R.drawable.ic_maneuver_waypoint_1_left;
            case 25: return R.drawable.ic_maneuver_waypoint_1_right;
            case 26: return R.drawable.ic_maneuver_waypoint_2_ahead;
            case 27: return R.drawable.ic_maneuver_waypoint_2_left;
            case 28: return R.drawable.ic_maneuver_waypoint_2_right;
            case 29: return R.drawable.ic_maneuver_waypoint_3_ahead;
            case 30: return R.drawable.ic_maneuver_waypoint_3_left;
            case 31: return R.drawable.ic_maneuver_waypoint_3_right;
            case 32: return R.drawable.ic_maneuver_merge_left;
            case 33: return R.drawable.ic_maneuver_merge_left;
            case 34: return R.drawable.ic_maneuver_merge_right;
            case 35: return R.drawable.ic_maneuver_merge_right;
            case 36: return R.drawable.ic_maneuver_exit_left;
            case 37: return R.drawable.ic_maneuver_exit_right;
            case 38: return R.drawable.ic_maneuver_toll_booth;
            case 39: return R.drawable.ic_maneuver_hook_right;
            default: return R.drawable.ic_maneuver_unknown;
        }
    }
}
