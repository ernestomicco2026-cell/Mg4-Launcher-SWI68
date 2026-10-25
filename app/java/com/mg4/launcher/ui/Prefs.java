package com.mg4.launcher.ui;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Impostazioni del launcher. */
public final class Prefs {
    public static final String[] CAR_COLORS = {
            "Arctic White", "Black Pearl", "Camden Grey", "Cosmic Silver",
            "Ealing Green", "Piccadilly Blue", "Dynamic Red", "Volcano Orange"};
    public static final int[] CAR_COLOR_VALUES = {
            0xFFE8E9E6, 0xFF202227, 0xFF5D6166, 0xFFA9ADB2,
            0xFF4C6A5A, 0xFF2E5597, 0xFFA5161E, 0xFFE1621F};
    /** Cerchi: nomi, immagini e grandezza rispetto ai 17" della foto. */
    public static final int[] WHEEL_NAMES = {com.mg4.launcher.R.string.wheel_16, com.mg4.launcher.R.string.wheel_17,
            com.mg4.launcher.R.string.wheel_18_lux, com.mg4.launcher.R.string.wheel_18_xpower};
    public static final int[] WHEEL_RES = {com.mg4.launcher.R.drawable.rim_nero16, com.mg4.launcher.R.drawable.rim_aero17,
            com.mg4.launcher.R.drawable.rim_luxury18, com.mg4.launcher.R.drawable.rim_xpower18};
    public static final float[] WHEEL_SCALE = {0.95f, 1f, 1.06f, 1.06f};
    /** I cerchi della foto dell'auto (di serie): non si ridisegnano. */
    public static final int WHEEL_PHOTO = 1;
    public static final String THEME_AUTO = "auto", THEME_NIGHT = "notte", THEME_DAY = "giorno";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
    }

    public int carColorIndex() {
        return Math.max(0, Math.min(CAR_COLORS.length - 1, sp.getInt("carColor", 5)));
    }

    public void setCarColorIndex(int i) {
        sp.edit().putInt("carColor", i).apply();
    }

    public int wheelIndex() {
        return Math.max(0, Math.min(WHEEL_RES.length - 1, sp.getInt("cerchi", WHEEL_PHOTO)));
    }

    public void setWheelIndex(int i) {
        sp.edit().putInt("cerchi", i).apply();
    }

    public boolean blackRoof() {
        return sp.getBoolean("blackRoof", true);
    }

    public void setBlackRoof(boolean b) {
        sp.edit().putBoolean("blackRoof", b).apply();
    }

    public String themeMode() {
        return sp.getString("theme", THEME_AUTO);
    }

    public void setThemeMode(String m) {
        sp.edit().putString("theme", m).apply();
    }

    // --- app installate nascoste dalla griglia ---

    public Set<String> hiddenApps() {
        return new HashSet<>(sp.getStringSet("hiddenApps", new HashSet<>()));
    }

    public void setAppHidden(String key, boolean hide) {
        Set<String> s = hiddenApps();
        if (hide) s.add(key);
        else s.remove(key);
        sp.edit().putStringSet("hiddenApps", s).apply();
    }

    // --- aggiornamenti da GitHub ---
    public static final String DEFAULT_REPO = "ernestomicco2026-cell/Mg4-Launcher-SWI68";
    /** Nomi precedenti del repository (rinominato): si passa da soli al nuovo. */
    private static final String[] OLD_REPOS = {"ernestomicco2026-cell/mg4-launcher", "ernestomicco2026-cell/Mg4-Launcher-SW168"};

    public String updateRepo() {
        String r = sp.getString("ghRepo", DEFAULT_REPO);
        for (String old : OLD_REPOS) {
            if (old.equalsIgnoreCase(r)) {
                sp.edit().putString("ghRepo", DEFAULT_REPO).apply();
                return DEFAULT_REPO;
            }
        }
        return r;
    }

    public void setUpdateRepo(String r) {
        sp.edit().putString("ghRepo", r == null || r.trim().isEmpty() ? DEFAULT_REPO : r.trim()).apply();
    }

    /** Token GitHub di sola lettura: serve perché il repository è privato. */
    public String githubToken() {
        return sp.getString("ghToken", "");
    }

    public void setGithubToken(String tok) {
        sp.edit().putString("ghToken", tok == null ? "" : tok.trim()).apply();
    }

    // --- navigatore predefinito: "auto" (telefono se collegato, altrimenti MG), "mg", "phone" o nome pacchetto ---
    public static final String NAV_AUTO = "auto", NAV_MG = "mg", NAV_PHONE = "phone";

    public String navApp() {
        return sp.getString("navApp", NAV_AUTO);
    }

    public void setNavApp(String v) {
        sp.edit().putString("navApp", v).apply();
    }

    // --- lingua: "auto" (lingua di sistema) o codice (it, en, es, de, pt, fr, tr) ---
    public String language() {
        return sp.getString("language", com.mg4.launcher.L.AUTO);
    }

    public void setLanguage(String code) {
        sp.edit().putString("language", code).apply();
    }

    // --- visualizzazione ---
    public boolean socDecimals() {
        return sp.getBoolean("socDecimals", false);
    }

    public void setSocDecimals(boolean b) {
        sp.edit().putBoolean("socDecimals", b).apply();
    }

    /** 0.9: percentuale della batteria in grande e autonomia accanto alla barra (km e % invertiti). */
    public boolean socBig() {
        return sp.getBoolean("socBig", false);
    }

    public void setSocBig(boolean b) {
        sp.edit().putBoolean("socBig", b).apply();
    }

    public boolean showTpms() {
        return sp.getBoolean("showTpms", true);
    }

    public void setShowTpms(boolean b) {
        sp.edit().putBoolean("showTpms", b).apply();
    }

    /** Popup discreto quando su GitHub c'è una versione nuova. */
    public boolean updatePopup() {
        return sp.getBoolean("updatePopup", true);
    }

    /** Ultima versione per cui è stato premuto "Più tardi": per quella il popup non ricompare. */
    public String dismissedUpdate() {
        return sp.getString("dismissedUpdate", "");
    }

    public void setDismissedUpdate(String tag) {
        sp.edit().putString("dismissedUpdate", tag == null ? "" : tag).apply();
    }

    /** Dimensione dei comandi: 0 Normale, 1 Grande, 2 Molto grande. */
    public static final float[] UI_SCALES = {1f, 1.15f, 1.3f};

    public int uiScale() {
        return Math.max(0, Math.min(2, sp.getInt("dimensioneComandi", 0)));
    }

    public void setUiScale(int i) {
        sp.edit().putInt("dimensioneComandi", i).apply();
    }

    public float uiScaleFactor() {
        return UI_SCALES[uiScale()];
    }

    /** Posizione di guida: "auto" (come l'auto), "left", "right". */
    public static final String DRIVE_AUTO = "auto", DRIVE_LEFT = "left", DRIVE_RIGHT = "right";

    public String driveSide() {
        String v = sp.getString("posizioneGuida", DRIVE_AUTO);
        return DRIVE_LEFT.equals(v) || DRIVE_RIGHT.equals(v) ? v : DRIVE_AUTO;
    }

    public void setDriveSide(String v) {
        sp.edit().putString("posizioneGuida", v).apply();
    }

    /** Temperatura interna sotto quella esterna (spenta di default: molte auto non la forniscono). */
    public boolean showInsideTemp() {
        return sp.getBoolean("showInsideTemp", false);
    }

    public void setShowInsideTemp(boolean b) {
        sp.edit().putBoolean("showInsideTemp", b).apply();
    }

    /** True se l'auto non fornisce la temperatura interna (l'opzione allora si nasconde). */
    public boolean insideTempMissing() {
        return sp.getBoolean("tempInternaAssente", false);
    }

    // --- novità 0.8.0 ---

    /** Data tra l'autonomia e l'ora (spenta di default). */
    public boolean showDate() {
        return sp.getBoolean("mostraData", false);
    }

    public void setShowDate(boolean b) {
        sp.edit().putBoolean("mostraData", b).apply();
    }

    /** Unità della pressione pneumatici: "auto" (come il quadro), "bar", "kpa", "psi". */
    public static final String TYRE_AUTO = "auto", TYRE_BAR = "bar", TYRE_KPA = "kpa", TYRE_PSI = "psi";

    public String tyreUnit() {
        String v = sp.getString("unitaPressione", TYRE_AUTO);
        return TYRE_BAR.equals(v) || TYRE_KPA.equals(v) || TYRE_PSI.equals(v) ? v : TYRE_AUTO;
    }

    public void setTyreUnit(String v) {
        sp.edit().putString("unitaPressione", v).apply();
    }

    /** Pulsanti di ricarica sotto l'auto (spenti di default: lo spazio va all'auto). */
    public boolean chargeButtons() {
        return sp.getBoolean("pulsantiRicarica", false);
    }

    public void setChargeButtons(boolean b) {
        sp.edit().putBoolean("pulsantiRicarica", b).apply();
    }

    /** Pulsante retronebbia nella barra (acceso di default; si spegne se l'auto ha già il tasto fisico). */
    public boolean showRearFog() {
        return sp.getBoolean("mostraRetronebbia", true);
    }

    public void setShowRearFog(boolean b) {
        sp.edit().putBoolean("mostraRetronebbia", b).apply();
    }

    /** Barra in basso grande (150 px, pulsanti 120 px); senza mini player. */
    public boolean bigBar() {
        return sp.getBoolean("barraGrande", false);
    }

    public void setBigBar(boolean b) {
        sp.edit().putBoolean("barraGrande", b).apply();
    }

    /** 0.8.2: A/C, AUTO e ricircolo in "Clima", sedili e volante in "Comfort" (un pulsante per gruppo). */
    public boolean groupedButtons() {
        return sp.getBoolean("pulsantiRaggruppati", true);
    }

    public void setGroupedButtons(boolean b) {
        sp.edit().putBoolean("pulsantiRaggruppati", b).apply();
    }

    /** 0.8.2: pulsanti LKA e OPD nella barra (se l'auto li ha). */
    public boolean showAssist() {
        return sp.getBoolean("mostraAssistenza", true);
    }

    public void setShowAssist(boolean b) {
        sp.edit().putBoolean("mostraAssistenza", b).apply();
    }

    /** Secondi dopo l'accensione per premere Home da soli: 0 = spento, 5/10/15/20/30. */
    public static final int[] AUTO_HOME_SECONDS = {0, 5, 10, 15, 20, 30};

    public int autoHomeSeconds() {
        return autoHomeSeconds(sp);
    }

    public static int autoHomeSeconds(SharedPreferences sp) {
        int v = sp.getInt("premiHomeSecondi", 0);
        for (int s : AUTO_HOME_SECONDS) if (s == v) return v;
        return 0;
    }

    public void setAutoHomeSeconds(int s) {
        sp.edit().putInt("premiHomeSecondi", s).apply();
    }

    /** Ultima versione per cui è stato mostrato il popup "Novità". */
    public String newsShownFor() {
        return sp.getString("novitaVersione", "");
    }

    public void setNewsShownFor(String v) {
        sp.edit().putString("novitaVersione", v).apply();
    }
}
