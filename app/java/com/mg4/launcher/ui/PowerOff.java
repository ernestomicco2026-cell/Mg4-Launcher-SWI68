package com.mg4.launcher.ui;

import android.widget.FrameLayout;

import com.mg4.launcher.L;
import com.mg4.launcher.R;
import com.mg4.launcher.svc.Diag;
import com.mg4.launcher.svc.VehicleClient;

/**
 * 1.0.5: "Spegni la vettura" (Impostazioni → Auto e, se scelto, pulsante nella barra). Solo in P e ferma: la marcia
 * si guarda prima di mostrare la conferma e VehicleClient.powerOff la rilegge subito prima del comando. Conferma in un
 * riquadro in basso; se non si tocca niente, dopo 8 secondi vale "Annulla".
 */
public final class PowerOff {
    private static final long CONFIRM_MS = 8_000, NOTICE_MS = 4_000;

    private PowerOff() {}

    public static void ask(FrameLayout root, Theme t, VehicleClient v) {
        if (v == null) return;
        VehicleClient.State s = v.last();
        boolean parked = s.gearRaw == VehicleClient.GEAR_P && !Float.isNaN(s.speedKmh) && Math.abs(s.speedKmh) < 1f;
        if (!parked) {
            Diag.put("auto.spegnimento", "richiesto · marcia " + (s.gearRaw == Integer.MIN_VALUE ? "?" : s.gearRaw)
                    + " · non in P: conferma non mostrata");
            Confirm.bottom(root, t, L.s(R.string.power_off_not_p), "", null, null, NOTICE_MS, null);
            return;
        }
        Confirm.bottom(root, t, L.s(R.string.power_off_q), L.s(R.string.power_off_msg), L.s(R.string.btn_cancel),
                L.s(R.string.power_off_btn), CONFIRM_MS, () -> v.powerOff(res -> {
                    if ("inviato".equals(res)) return;
                    int msg = "non in P".equals(res) ? R.string.power_off_not_p
                            : "non disponibile".equals(res) ? R.string.power_off_unavailable : R.string.power_off_failed;
                    if (root.isAttachedToWindow()) Confirm.bottom(root, t, L.s(msg), "", null, null, NOTICE_MS, null);
                }));
    }
}
