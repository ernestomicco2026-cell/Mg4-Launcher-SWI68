package com.mg4.launcher.ui;

import com.mg4.launcher.R;

/** Only codes observed in the MG logs; 0 confirmed by the driver's observation. */
public final class ManeuverIcons {
    private ManeuverIcons() {}

    public static int drawable(int code) {
        switch (code) {
            case 0: return R.drawable.ic_maneuver_straight;
            case 1: return R.drawable.ic_maneuver_left;
            case 2: return R.drawable.ic_maneuver_right;
            case 3: return R.drawable.ic_maneuver_bear_left;
            case 5: return R.drawable.ic_maneuver_uturn;
            case 9: return R.drawable.ic_maneuver_roundabout_straight;
            case 10: return R.drawable.ic_maneuver_roundabout_left;
            case 11: return R.drawable.ic_maneuver_roundabout_right;
            case 12: return R.drawable.ic_maneuver_roundabout_uturn;
            case 13: return R.drawable.ic_maneuver_roundabout_one;
            case 17: return R.drawable.ic_maneuver_keep_left;
            case 18: return R.drawable.ic_maneuver_keep_right;
            case 21: return R.drawable.ic_maneuver_destination;
            case 37: return R.drawable.ic_maneuver_exit_right;
            default: return R.drawable.ic_maneuver_unknown;
        }
    }
}
