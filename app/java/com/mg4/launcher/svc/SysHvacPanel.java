package com.mg4.launcher.svc;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * Pannello clima del sistema (SWI68 / SWI133, 0.9.6 / 0.2.6): quando il clima si accende il SystemUI apre da solo la
 * sua finestra "hvac page" sopra tutto. Se in primo piano c'è MG4 Clima la si chiude subito.
 * <ul>
 *   <li>riconoscimento: la finestra si chiama "hvac page" (idea di NoHvacPopup), vista dal servizio di accessibilità;</li>
 *   <li>chiusura: il comando del SystemUI stesso, dal servizio StartActivityService: IPageService.closeHvac() sulla
 *       SWI68 (lo usa anche MG4-HVAC-Overlay-Launcher), IStartActivityService.stopHVAC() sulla SWI133; niente tasto
 *       Indietro che potrebbe chiudere l'app sotto.</li>
 * </ul>
 */
public final class SysHvacPanel {
    private static final String SYSUI = "com.android.systemui";
    private static final String SERVICE = "com.android.systemui.StartActivityService";
    private static final String ACTION = "com.android.systemui.saicmotor.action.StartActivity";
    private static final String DESCRIPTOR = "com.saicmotor.sdk.external.IPageService";
    private static final int OPEN_HVAC = 1, CLOSE_HVAC = 2;
    private static final String TITLE = "hvac page";
    private static final String CLIMA_PKG = "com.mg4.clima";

    private static volatile IBinder page;
    private static boolean binding;
    private static String lastApp = "";
    private static long lastClose;

    private SysHvacPanel() {}

    /** Solo i firmware con il pannello clima nel SystemUI. */
    public static boolean supported(Context c) {
        try {
            c.getPackageManager().getServiceInfo(new ComponentName(SYSUI, SERVICE), 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            page = service;
            binding = false;
            Diag.put("clima.pannelloSistema", "servizio del pannello collegato");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            page = null;
            binding = false;
        }
    };

    public static void bind(Context ctx) {
        if (page != null || binding) return;
        Context c = ctx.getApplicationContext();
        binding = true;
        try {
            boolean ok = c.bindService(new Intent(ACTION).setComponent(new ComponentName(SYSUI, SERVICE)), conn, Context.BIND_AUTO_CREATE);
            if (!ok) {
                binding = false;
                Diag.put("clima.pannelloSistema", "servizio del pannello non disponibile");
            }
        } catch (Throwable e) {
            binding = false;
            Diag.put("clima.pannelloSistema", "collegamento rifiutato: " + e);
        }
    }

    /** closeHvac(): true se il comando è partito. */
    public static boolean close(Context c) {
        IBinder b = page;
        if (b == null) {
            bind(c);
            return false;
        }
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            // SWI68: IPageService.closeHvac(); SWI133: IStartActivityService.stopHVAC(). Stesso codice 2, nome letto dal servizio
            String desc = b.getInterfaceDescriptor();
            d.writeInterfaceToken(desc == null || desc.isEmpty() ? DESCRIPTOR : desc);
            b.transact(CLOSE_HVAC, d, r, 0);
            r.readException();
            return true;
        } catch (Throwable e) {
            page = null;
            Diag.put("clima.pannelloSistema", "chiusura non riuscita: " + e);
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    /** Dal servizio di accessibilità: ricorda l'app in primo piano e, se compare il pannello, decide se chiuderlo. */
    public static void onEvent(AccessibilityService s, AccessibilityEvent e) {
        if (e == null) return;
        CharSequence pkg = e.getPackageName();
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null && !SYSUI.contentEquals(pkg)) {
            lastApp = pkg.toString();
        }
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOWS_CHANGED
                && !(e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null && SYSUI.contentEquals(pkg))) {
            return;
        }
        if (!CLIMA_PKG.equals(lastApp)) return;   // il pannello si chiude solo sopra MG4 Clima
        boolean shown = false;
        try {
            List<AccessibilityWindowInfo> ws = s.getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    CharSequence t = w == null ? null : w.getTitle();
                    if (t != null && t.toString().toLowerCase(java.util.Locale.ROOT).contains(TITLE)) {
                        shown = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (!shown) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastClose < 400) return;
        lastClose = now;
        boolean ok = close(s);
        Diag.put("clima.pannelloSistema", "finestra \"hvac page\" vista sopra MG4 Clima → " + (ok ? "chiusa con il comando del sistema" : "comando non pronto"));
    }
}
