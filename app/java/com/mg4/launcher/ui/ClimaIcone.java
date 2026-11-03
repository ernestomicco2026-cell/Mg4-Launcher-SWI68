package com.mg4.launcher.ui;   // cambia il pacchetto con quello del launcher

import com.mg4.launcher.R;

/**
 * MG4 Clima 1.4.0 · stato del clima → icona (stessi valori letti da MG4 Clima e dal launcher, SAIC e caradapter).
 * Le icone sono in res/drawable (bianche): il colore viene da res/values(-night)/clima_colori.xml.
 */
public final class ClimaIcone {
    private ClimaIcone() {
    }

    /** Ricircolo: 0 interno, 1 aria esterna, 2 automatico. Interno e automatico = acceso. */
    public static int ricircolo(int loop) {
        switch (loop) {
            case 0: return R.drawable.clima_ricircolo_interno;
            case 2: return R.drawable.clima_ricircolo_auto;
            default: return R.drawable.clima_aria_esterna;
        }
    }

    public static boolean ricircoloAcceso(int loop) {
        return loop == 0 || loop == 2;
    }

    /** Direzione dell'aria 0..6 → quali pulsanti sono accesi: {parabrezza, viso, piedi}. */
    public static boolean[] direzioni(int blowerMode) {
        switch (blowerMode) {
            case 0: return new boolean[]{false, true, false};
            case 1: return new boolean[]{false, true, true};
            case 2: return new boolean[]{false, false, true};
            case 3: return new boolean[]{true, false, true};
            case 4: return new boolean[]{true, false, false};
            case 5: return new boolean[]{true, true, true};
            case 6: return new boolean[]{true, true, false};
            default: return new boolean[]{false, false, false};
        }
    }

    /** Stato del pulsante: 0 spento, 1 acceso, 2 non disponibile (clima non usabile o valore non fornito, -1). */
    public static int stato(boolean usabile, int valore, int valoreAcceso) {
        if (!usabile || valore < 0) return 2;
        return valore == valoreAcceso ? 1 : 0;
    }
}
