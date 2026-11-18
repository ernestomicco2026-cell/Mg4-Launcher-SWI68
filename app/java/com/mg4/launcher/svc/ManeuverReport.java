package com.mg4.launcher.svc;

import java.util.Locale;

/**
 * EN: Convert known instructions to fixed categories; unknown text never enters the report.
 * Reports contain only the maneuver code, category and distance, without road names or destinations.
 * IT: Converti istruzioni note in categorie fisse; il testo sconosciuto non entra nel rapporto.
 * I rapporti contengono solo codice, categoria e distanza, senza nomi di strade o destinazioni.
 */
public final class ManeuverReport {
    private ManeuverReport() {}

    public static String instructionType(String text) {
        if (text == null) return "unknown";
        switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "turn left": return "turn_left";
            case "turn right": return "turn_right";
            case "direction to left": return "bear_left";
            case "direction to right": return "bear_right";
            // EN: Preserve the native APK's exact spelling, alongside the older accepted alias.
            // IT: Riconosci il testo esatto dell'APK originale, oltre alla variante gia accettata.
            case "direction right": return "bear_right";
            case "to right 4 o’clock": return "sharp_right";
            case "to left 8 o’clock": return "sharp_left";
            case "keep left": return "keep_left";
            case "keep right": return "keep_right";
            case "exit left": return "exit_left";
            case "exit right": return "exit_right";
            case "u-turn": return "u_turn";
            case "reach destination": return "destination";
            case "reach way point 1": return "waypoint_one";
            case "reach way point 2": return "waypoint_two";
            case "reach way point 3": return "waypoint_three";
            case "enter right": return "enter_right";
            case "enter left": return "enter_left";
            case "merge left": return "merge_left";
            case "merge right": return "merge_right";
            case "ferry": return "ferry";
            case "passtollbooth": return "toll_booth";
            case "hookturnright": return "hook_right";
            case "go straight": return "straight";
            case "go straight (at a rotary)": return "roundabout_straight";
            case "to left 9 o’clock (at a rotary)": return "roundabout_left";
            case "to right 3 o’clock (at a rotary)": return "roundabout_right";
            case "to right 1 o’clock (at a rotary)": return "roundabout_one";
            case "to right 4 o’clock (at a rotary)": return "roundabout_four";
            case "to left 8 o’clock (at a rotary)": return "roundabout_eight";
            case "to left 11 o’clock (at a rotary)": return "roundabout_eleven";
            case "u-turn (at a rotary)": return "roundabout_u_turn";
            default: return "unknown";
        }
    }

    public static String body(int code, int meters, String text) {
        return "===== MISSING NAVIGATION ICON =====\nIcon code: " + code
                + "\nInstruction type: " + instructionType(text)
                + "\nDistance meters: " + meters + "\n";
    }
}
