package com.mg4.launcher.svc;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/**
 * Solo verifica per la mappa in finestra (PIP), nessuna funzione: scrive nella diagnostica se le schermate del
 * navigatore e di CarPlay/Android Auto si possono incorporare (ridimensionabili, allowEmbedded) su questo firmware.
 */
public final class EmbedCheck {
    private static final String[] PACKAGES = {"com.telenav.app.arp", "com.allgo.carplay.service", "com.allgo.app.androidauto", "com.allgo.rui"};
    /** ActivityInfo.FLAG_ALLOW_EMBEDDED (nascosto). */
    private static final int FLAG_ALLOW_EMBEDDED = 0x80000000;

    private EmbedCheck() {}

    public static void log(Context c) {
        new Thread(() -> {
            PackageManager pm = c.getPackageManager();
            Diag.put("pip.displaySecondari", pm.hasSystemFeature("android.software.activities_on_secondary_displays") ? "sì" : "no");
            Diag.put("pip.permessoIncorpora", pm.checkPermission("android.permission.ACTIVITY_EMBEDDING", c.getPackageName())
                    == PackageManager.PERMISSION_GRANTED ? "concesso" : "non concesso");
            for (String p : PACKAGES) {
                StringBuilder sb = new StringBuilder();
                try {
                    PackageInfo pi = pm.getPackageInfo(p, PackageManager.GET_ACTIVITIES);
                    if (pi.activities != null) for (ActivityInfo a : pi.activities) {
                        if (!a.exported && !p.equals("com.telenav.app.arp")) continue;
                        int resize = -1;
                        try {
                            resize = ActivityInfo.class.getField("resizeMode").getInt(a);
                        } catch (Throwable ignored) {
                        }
                        sb.append(sb.length() == 0 ? "" : " | ").append(a.name.substring(a.name.lastIndexOf('.') + 1))
                                .append(" resize=").append(resize)
                                .append((a.flags & FLAG_ALLOW_EMBEDDED) != 0 ? " embedded" : "")
                                .append(a.exported ? "" : " privata");
                        if (sb.length() > 700) break;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    sb.append("non installata");
                } catch (Throwable e) {
                    sb.append(e.getClass().getSimpleName());
                }
                Diag.put("pip." + p, sb.length() == 0 ? "nessuna attività esportata" : sb.toString());
            }
        }, "mg4-pip-check").start();
    }
}
