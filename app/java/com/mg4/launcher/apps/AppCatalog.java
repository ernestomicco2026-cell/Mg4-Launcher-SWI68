package com.mg4.launcher.apps;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import com.mg4.launcher.R;
import com.mg4.launcher.svc.Diag;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Elenco delle app: quelle dell'auto (componenti SAIC noti, con icone disegnate) e quelle
 * installate dall'utente (lette dal sistema, aggiornate a ogni installazione/disinstallazione).
 */
public final class AppCatalog {

    public static final class App {
        public final String label;
        public final ComponentName component;   // null = usa launchIntent
        public final Intent launchIntent;
        public final int iconRes;               // 0 = icona ridisegnata dall'originale
        public final boolean highlight;
        public final String packageName;

        App(String label, ComponentName cn, Intent li, int iconRes, boolean highlight, String pkg) {
            this.label = label;
            this.component = cn;
            this.launchIntent = li;
            this.iconRes = iconRes;
            this.highlight = highlight;
            this.packageName = pkg;
        }

        public Intent intent() {
            Intent i;
            if (component != null) {
                i = new Intent(Intent.ACTION_MAIN).setComponent(component);
            } else {
                i = new Intent(launchIntent);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            return i;
        }
    }

    /** App dell'auto, nell'ordine del launcher MG (componenti dal file app_pool_list dell'originale). */
    private static final String[][] CAR_APPS = {
            {"@app_vehicle", "com.saicmotor.hmi.vehiclesettings", "com.saicmotor.hmi.vehiclesettings.vehicleconfig.ui.VehicleSettingsActivity", "car"},
            {"@climate", "com.saicmotor.hmi.hvac", "com.saicmotor.hmi.hvac.HvacActivity", "fan"},
            {"@app_energy", "com.saicmotor.hmi.vehiclesettings", "com.saicmotor.hmi.vehiclesettings.chargemanagement.ui.ChargeManagementActivity", "bolt"},
            {"@app_consumption", "com.saicmotor.hmi.vehiclesettings", "com.saicmotor.hmi.vehiclesettings.energyconsumption.ui.EnergyConsumptionActivity", "chart"},
            {"@app_phone", "com.saicmotor.hmi.btcall", "com.saicmotor.hmi.btcall.BtCallActivity", "phone"},
            {"@src_radio", "com.saicmotor.hmi.radio", "com.saicmotor.hmi.radio.app.RadioHomeActivity", "radio"},
            {"@app_music", "com.saicmotor.hmi.music", "com.saicmotor.hmi.music.ui.activity.MusicActivity", "music"},
            {"@app_usb_video", "com.saicmotor.hmi.video", "com.saicmotor.hmi.video.ui.activity.UsbVideoActivity", "video"},
            {"@app_camera", "com.saicmotor.hmi.aroundview", "com.saicmotor.hmi.aroundview.AVMActivity", "cam360"},
            {"@app_settings", "com.saicmotor.hmi.systemsettings", "com.saicmotor.hmi.systemsettings.SettingsActivity", "gear"},
            {"@app_navigator", "@nav", "", "nav"},
            {"EV Route", "com.saicmotor.evrouting", "com.saicmotor.evrouting.view.MainActivity", "route"},
            {"Spotify", "com.saicmotor.mediacenter", "com.saicmotor.mediacenter.spotify.MainActivity", "wave"},
            {"Amazon Music", "com.saicmotor.onlinemedia.amazon.v2", "", "wave"},
            {"@app_weather", "com.saicmotor.weathers", "com.saicmotor.weathers.activity.MainActivity", "cloud"},
            {"@app_manual", "com.saicmotor.hmi.pdfreader", "com.saicmotor.hmi.pdfreader.QRcodeActivity", "book"},
            {"MG Touchpoint", "com.saic.saicmaintenance", "com.saic.saicmaintenance.module.maintaince.MaintaiceEp21Activity", "info"},
            {"@app_account", "com.saic.avnlogin", "com.saic.avnlogin.mvp.login.LoginActivity", "user"},
            {"@app_sos", "com.saicmotor.rescuecall", "com.saicmotor.rescuecall.module.ICallCenterAct", "sos"},
            // scorciatoie di Android (solo se l'attività esiste davvero sull'auto)
            {"@app_android_settings", "@android_settings", "", "android_settings"},
            {"@app_android_files", "@android_files", "", "folder"},
    };

    /** Gestori file possibili, nell'ordine in cui si cercano. */
    private static final String[] FILE_PACKAGES = {"com.android.documentsui", "com.mediatek.filemanager", "com.android.fileexplorer"};

    /** Navigatori possibili (dall'originale: Telenav, iGo, SAIC). */
    private static final String[] NAV_PACKAGES = {"com.telenav.app.arp", "com.nng.igo.primong", "com.saicmotor.navigation"};

    /** Pacchetti di sistema da non mostrare tra le app installate. */
    private static final String[] HIDDEN_PREFIX = {"com.saicmotor.", "com.saic.", "com.allgo.", "com.android.", "android",
            "com.yfve.", "com.mediatek.", "com.mtk.", "com.google.android.car", "com.telenav.", "com.nng."};

    private final Context ctx;
    private final PackageManager pm;

    public AppCatalog(Context c) {
        ctx = c.getApplicationContext();
        pm = ctx.getPackageManager();
    }

    public static int iconFor(Context c, String name) {
        return c.getResources().getIdentifier("ic_" + name, "drawable", c.getPackageName());
    }

    /** Etichetta: "@chiave" = testo tradotto (res/values-xx/strings.xml), altrimenti nome fisso (EV Route, Spotify...). */
    private String label(String l) {
        if (!l.startsWith("@")) return l;
        int id = ctx.getResources().getIdentifier(l.substring(1), "string", ctx.getPackageName());
        return id == 0 ? l.substring(1) : com.mg4.launcher.L.s(id);
    }

    /** Lingua cambiata: le etichette si rileggono alla prossima chiamata di carApps(). */
    public void refreshLabels() {
    }

    public List<App> carApps(String navPackageHint) {
        List<App> out = new ArrayList<>();
        for (String[] row : CAR_APPS) {
            String[] a = {label(row[0]), row[1], row[2], row[3]};
            int icon = iconFor(ctx, a[3]);
            if ("@nav".equals(a[1])) {
                Intent nav = navIntent(navPackageHint);
                if (nav != null) {
                    String pkg = nav.getComponent() != null ? nav.getComponent().getPackageName() : String.valueOf(nav.getPackage());
                    out.add(new App(a[0], null, nav, icon, false, pkg));
                }
                continue;
            }
            if ("@android_settings".equals(a[1]) || "@android_files".equals(a[1])) {
                Intent si = "@android_settings".equals(a[1]) ? androidSettingsIntent() : androidFilesIntent();
                if (si != null) out.add(new App(a[0], null, si, icon, false, targetPackage(si)));
                continue;
            }
            ComponentName cn = new ComponentName(a[1], a[2]);
            if (exists(cn)) {
                out.add(new App(a[0], cn, null, icon, false, a[1]));
            } else {
                Intent li = pm.getLaunchIntentForPackage(a[1]);
                if (li != null && !"bolt".equals(a[3]) && !"chart".equals(a[3])) {
                    out.add(new App(a[0], null, li, icon, false, a[1]));
                }
            }
        }
        return out;
    }

    private String sysDiag;

    /** Impostazioni di Android (com.android.settings), se l'attività esiste. */
    private Intent androidSettingsIntent() {
        Intent i = new Intent(android.provider.Settings.ACTION_SETTINGS).setPackage("com.android.settings");
        if (pm.resolveActivity(i, 0) == null) i = new Intent(android.provider.Settings.ACTION_SETTINGS);
        boolean ok = pm.resolveActivity(i, 0) != null;
        sysDiag = "impostazioni " + (ok ? "ok" : "assente");
        return ok ? i : null;
    }

    /** Gestore file dell'auto: DocumentsUI, MediaTek, File Explorer, oppure chi apre documenti. */
    private Intent androidFilesIntent() {
        Intent found = null;
        for (String p : FILE_PACKAGES) {
            Intent li = pm.getLaunchIntentForPackage(p);
            if (li != null) {
                found = li;
                break;
            }
            if ("com.android.documentsui".equals(p)) {
                ComponentName fa = new ComponentName(p, "com.android.documentsui.files.FilesActivity");
                if (exists(fa)) {
                    found = new Intent(Intent.ACTION_MAIN).setComponent(fa);
                    break;
                }
            }
        }
        if (found == null) {
            Intent od = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            Intent gc = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            if (pm.resolveActivity(od, 0) != null) found = od;
            else if (pm.resolveActivity(gc, 0) != null) found = gc;
        }
        String head = sysDiag == null ? "impostazioni ?" : sysDiag;
        Diag.put("app.sistema", head + ", file " + (found != null ? targetPackage(found) : "assente"));
        return found;
    }

    private String targetPackage(Intent i) {
        if (i.getComponent() != null) return i.getComponent().getPackageName();
        if (i.getPackage() != null) return i.getPackage();
        android.content.pm.ResolveInfo ri = pm.resolveActivity(i, 0);
        return ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : "?";
    }

    /** Navigatori di terze parti installati sull'auto (Waze, Google Maps, OsmAnd, ...). */
    public List<App> otherNavApps(String mgNavPackage) {
        Set<String> pkgs = new java.util.LinkedHashSet<>();
        Collections.addAll(pkgs, "com.waze", "com.google.android.apps.maps", "com.google.android.apps.maps.auto",
                "net.osmand", "net.osmand.plus", "com.sygic.aura", "com.here.app.maps", "com.tomtom.gplay.navapp",
                "com.mapfactor.navigator", "cz.seznam.mapy");
        try {
            Intent geo = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=roma"));
            for (ResolveInfo ri : pm.queryIntentActivities(geo, 0)) pkgs.add(ri.activityInfo.packageName);
        } catch (Exception ignored) {
        }
        List<App> out = new ArrayList<>();
        for (String p : pkgs) {
            if (p.equals(mgNavPackage) || p.startsWith("com.saicmotor") || p.startsWith("com.telenav")
                    || p.startsWith("com.nng") || p.startsWith("com.tomtom.navigation")) continue;
            Intent li = pm.getLaunchIntentForPackage(p);
            if (li == null) continue;
            String label = p;
            try {
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0)));
            } catch (Exception ignored) {
            }
            out.add(new App(label, null, li, 0, false, p));
        }
        return out;
    }

    public Intent navIntent(String hint) {
        List<String> candidates = new ArrayList<>();
        if (hint != null && !hint.isEmpty()) candidates.add(hint);
        Collections.addAll(candidates, NAV_PACKAGES);
        for (String p : candidates) {
            Intent i = pm.getLaunchIntentForPackage(p);
            if (i != null) return i;
        }
        return null;
    }

    private boolean exists(ComponentName cn) {
        try {
            ActivityInfo ai = pm.getActivityInfo(cn, 0);
            return ai != null && ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** App installate dall'utente (DiPlay, Task Manager, ...), in ordine alfabetico. */
    public List<App> userApps() {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
        List<App> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden(pkg)) continue;
            String key = pkg + "/" + ri.activityInfo.name;
            if (!seen.add(key)) continue;
            String label = String.valueOf(ri.loadLabel(pm));
            boolean mine = pkg.toLowerCase(Locale.ROOT).contains("diplay") || label.toLowerCase(Locale.ROOT).contains("diplay")
                    || label.toLowerCase(Locale.ROOT).contains("task manager") || pkg.toLowerCase(Locale.ROOT).contains("taskmanager");
            out.add(new App(label, new ComponentName(pkg, ri.activityInfo.name), null, 0, mine, pkg));
        }
        final Collator col = Collator.getInstance(Locale.ITALY);
        Collections.sort(out, (x, y) -> {
            return col.compare(x.label, y.label);
        });
        Diag.put("app.installate", out.size());
        return out;
    }

    private static boolean hidden(String pkg) {
        for (String p : HIDDEN_PREFIX) {
            if (pkg.startsWith(p)) return true;
        }
        return false;
    }

    public Drawable originalIcon(App a) {
        try {
            if (a.component != null) return pm.getActivityIcon(a.component);
            return pm.getApplicationIcon(a.packageName);
        } catch (Exception e) {
            return ctx.getDrawable(R.drawable.ic_app);
        }
    }

    public App findByPackageHint(String... needles) {
        for (App a : userApps()) {
            String p = a.packageName.toLowerCase(Locale.ROOT), l = a.label.toLowerCase(Locale.ROOT);
            for (String n : needles) {
                if (p.contains(n) || l.contains(n)) return a;
            }
        }
        return null;
    }

    public boolean launch(App a) {
        try {
            ctx.startActivity(a.intent());
            return true;
        } catch (Exception e) {
            Diag.error("avvio " + a.label, e.toString());
            return false;
        }
    }

    private final java.util.Set<String> missingLogged = new java.util.HashSet<>();

    /**
     * Intent per aprire un'app originale: l'attività esatta se esiste, altrimenti l'attività principale del pacchetto
     * (su alcuni firmware SWI68 HvacActivity e ChargeManagementActivity non ci sono). Null se manca anche quella:
     * scritto una sola volta nella diagnostica.
     */
    public Intent resolveLaunch(String pkg, String cls) {
        Intent exact = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(pkg, cls));
        try {
            if (cls != null && pm.resolveActivity(exact, 0) != null) return exact;
        } catch (Exception ignored) {
        }
        Intent li = null;
        try {
            li = pm.getLaunchIntentForPackage(pkg);
        } catch (Exception ignored) {
        }
        String key = pkg + "/" + cls;
        if (missingLogged.add(key)) {
            Diag.put("app.mancante." + pkg.substring(pkg.lastIndexOf('.') + 1),
                    (cls == null ? "" : cls.substring(cls.lastIndexOf('.') + 1) + " assente · ") + (li != null ? "si apre l'app principale" : "app non presente: pulsante disattivato"));
        }
        return li;
    }

    public boolean canLaunch(String pkg, String cls) {
        return resolveLaunch(pkg, cls) != null;
    }

    public boolean launchComponent(String pkg, String cls) {
        Intent i = resolveLaunch(pkg, cls);
        if (i == null) return false;
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            Diag.error("avvio " + pkg, e.toString());
            return false;
        }
    }
}
