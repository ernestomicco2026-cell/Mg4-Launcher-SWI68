package com.mg4.launcher.svc;

/** EN: SAIC airflow modes, as in MG4 Clima. IT: Direzioni SAIC, come in MG4 Clima. */
public final class AirflowModes {
    public static final int WINDSCREEN = 1, FACE = 2, FEET = 4;
    private static final int[] MASKS = {FACE, FACE | FEET, FEET, WINDSCREEN | FEET,
            WINDSCREEN, WINDSCREEN | FACE | FEET, WINDSCREEN | FACE};

    private AirflowModes() {}

    public static int mask(int mode) {
        return mode >= 0 && mode < MASKS.length ? MASKS[mode] : 0;
    }

    /** EN: Keep at least one outlet open. IT: Lascia aperta almeno una direzione. */
    public static int toggle(int mode, int direction) {
        if (mask(mode) == 0 || (direction != WINDSCREEN && direction != FACE && direction != FEET)) return mode;
        int next = mask(mode) ^ direction;
        for (int i = 0; i < MASKS.length; i++) if (MASKS[i] == next) return i;
        return mode;
    }
}
