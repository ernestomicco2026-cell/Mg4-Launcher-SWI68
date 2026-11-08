package com.mg4.launcher.svc;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.0.3 (punto 7): telefono Bluetooth per i "Preferiti telefono" del riquadro in basso.
 * <ul>
 *   <li>servizio com.saicmotor.service.btcall (azione com.saicmotor.service.btcall.BtCallService), interfaccia
 *       com.saicmotor.sdk.btcall.IBtCall: 2 placeCall(numero), 11 permesso rubrica, 12 stato Bluetooth (2 = telefono
 *       collegato in HFP), 13 stato sincronizzazione (2 = sincronizzata), 20/21 registra/togli l'ascolto;</li>
 *   <li>callback com.saicmotor.sdk.btcall.callback.IBtCallListener: 1 stato Bluetooth, 2 stato sincronizzazione;</li>
 *   <li>rubrica: la scarica il Bluetooth (PBAP) nei contatti di Android; l'app Telefono originale la legge da lì
 *       (ContactsContract Phone), così come qui.</li>
 * </ul>
 * La chiamata la fa il servizio telefono (come l'app Telefono originale): al launcher non serve CALL_PHONE.
 * Nei log mai nomi né numeri: solo quanti sono.
 */
public final class PhoneClient {
    public interface Listener {
        void onPhoneChanged();
    }

    public static final class Contact {
        public final String name, number;

        public Contact(String name, String number) {
            this.name = name == null ? "" : name;
            this.number = number == null ? "" : number;
        }
    }

    private static final String PKG = "com.saicmotor.service.btcall", ACTION = "com.saicmotor.service.btcall.BtCallService";
    private static final String DESC = "com.saicmotor.sdk.btcall.IBtCall";
    private static final String EVT = "com.saicmotor.sdk.btcall.callback.IBtCallListener";
    private static final int PLACE_CALL = 2, BOOK_PERMISSION = 11, BT_STATE = 12, SYNC_STATE = 13, ADD_LISTENER = 20, REMOVE_LISTENER = 21;

    private static PhoneClient instance;

    private final Context ctx;
    private final ServiceLink link;
    private final Handler work;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile int btState = -1, syncState = -1, bookPerm = -1;
    private volatile boolean registered;
    private int users;

    private PhoneClient(Context c) {
        ctx = c.getApplicationContext();
        HandlerThread t = new HandlerThread("mg4-telefono");
        t.start();
        work = new Handler(t.getLooper());
        link = new ServiceLink(ctx, "telefono", ServiceLink.byAction(PKG, ACTION));
        link.setOnConnected(() -> work.post(this::onConnected));
    }

    public static synchronized PhoneClient get(Context c) {
        if (instance == null) instance = new PhoneClient(c);
        return instance;
    }

    /** Si collega al servizio (contato: si scollega quando l'ultimo utilizzatore chiama stop). */
    public synchronized void start(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
        if (users++ == 0) link.start();
    }

    public synchronized void stop(Listener l) {
        if (l != null) listeners.remove(l);
        if (users > 0 && --users == 0) {
            unregister();
            link.stop();
        }
    }

    public boolean serviceConnected() {
        return link.binder() != null;
    }

    /** Telefono collegato in Bluetooth (HFP). */
    public boolean phoneConnected() {
        return btState == 2;
    }

    public int btState() {
        return btState;
    }

    public int syncState() {
        return syncState;
    }

    private void onConnected() {
        refresh();
        IBinder b = link.binder();
        if (b == null) return;
        try {
            Rpc.call(b, DESC, ADD_LISTENER, p -> p.writeStrongBinder(listener));
            registered = true;
        } catch (Exception e) {
            Diag.error("telefono.ascolto", e.toString());
        }
    }

    private void unregister() {
        if (!registered) return;
        registered = false;
        IBinder b = link.binder();
        if (b == null) return;
        work.post(() -> {
            try {
                Rpc.call(b, DESC, REMOVE_LISTENER, p -> p.writeStrongBinder(listener));
            } catch (Exception ignored) {
            }
        });
    }

    /** Rilegge stato Bluetooth, sincronizzazione e permesso della rubrica (sul thread del telefono). */
    public void refresh() {
        work.post(() -> {
            IBinder b = link.binder();
            int bs = -1, ss = -1, bp = -1;
            if (b != null) {
                try {
                    bs = Rpc.callInt(b, DESC, BT_STATE);
                    ss = Rpc.callInt(b, DESC, SYNC_STATE);
                    bp = Rpc.callInt(b, DESC, BOOK_PERMISSION);
                } catch (Exception e) {
                    Diag.error("telefono.stato", e.toString());
                }
            }
            boolean changed = bs != btState || ss != syncState || bp != bookPerm;
            btState = bs;
            syncState = ss;
            bookPerm = bp;
            Diag.put("telefono.stato", (b == null ? "servizio non collegato · " : "") + "Bluetooth " + btTxt(bs) + " · rubrica " + syncTxt(ss)
                    + " · permesso rubrica " + (bp == 1 ? "sì" : bp < 0 ? "?" : "no"));
            if (changed) notifyListeners();
        });
    }

    private static String btTxt(int s) {
        switch (s) {
            case 0: return "spento";
            case 1: return "acceso, nessun telefono";
            case 2: return "telefono collegato";
            case 3: return "scollegato";
            default: return "?";
        }
    }

    private static String syncTxt(int s) {
        switch (s) {
            case 0: return "mai sincronizzata";
            case 1: return "in sincronizzazione";
            case 2: return "sincronizzata";
            case 3: return "sincronizzazione non riuscita";
            default: return "?";
        }
    }

    private void notifyListeners() {
        main.post(() -> {
            for (Listener l : listeners) l.onPhoneChanged();
        });
    }

    private final Binder listener = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(EVT);
                return true;
            }
            if (code == 1 || code == 2) refresh();   // stato Bluetooth / sincronizzazione cambiati
            if (reply != null) reply.writeNoException();
            return true;
        }
    };

    /** Numero pulito come fa l'app Telefono: niente spazi, trattini, parentesi. */
    public static String clean(String n) {
        if (n == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : n.toCharArray()) if (Character.isDigit(ch) || ch == '+' || ch == '*' || ch == '#') sb.append(ch);
        return sb.toString();
    }

    /** Chiama il numero tramite il servizio telefono. Risultato nel log senza il numero. */
    public void call(String number, java.util.function.Consumer<Boolean> done) {
        String n = clean(number);
        work.post(() -> {
            boolean ok = false;
            String why;
            IBinder b = link.binder();
            if (n.isEmpty()) why = "numero vuoto";
            else if (b == null) why = "servizio telefono non collegato";
            else {
                try {
                    Integer r = Rpc.call(b, DESC, PLACE_CALL, p -> p.writeString(n), Parcel::readInt);
                    ok = r != null && r != 0;
                    why = ok ? "avviata" : "rifiutata dal servizio (telefono non collegato o senza rete)";
                } catch (Exception e) {
                    why = "errore " + e.getClass().getSimpleName();
                }
            }
            Diag.put("telefono.chiamata", why + " · " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ITALY).format(new java.util.Date()));
            if (done != null) {
                boolean res = ok;
                main.post(() -> done.accept(res));
            }
        });
    }

    /**
     * Rubrica del telefono collegato (nome + numero, senza doppioni), in ordine alfabetico. Vuota se il telefono non
     * è collegato o la rubrica non è sincronizzata. Da chiamare fuori dal thread principale.
     */
    public List<Contact> contacts() {
        List<Contact> out = new ArrayList<>();
        ensureContactsPermission(ctx);
        android.database.Cursor c = null;
        try {
            c = ctx.getContentResolver().query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{"display_name", "data1"}, null, null, "display_name COLLATE NOCASE ASC");
            java.util.Set<String> seen = new java.util.HashSet<>();
            while (c != null && c.moveToNext() && out.size() < 2000) {
                String name = c.getString(0), num = clean(c.getString(1));
                if (num.isEmpty() || name == null || name.isEmpty() || "本机号码".equals(name)) continue;
                if (!seen.add(name + "|" + num)) continue;
                out.add(new Contact(name, num));
            }
            Diag.put("telefono.rubrica", out.size() + " numeri letti");
        } catch (Throwable e) {
            Diag.error("telefono.rubrica", e.getClass().getSimpleName());
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** Il launcher è un'app di sistema: il permesso della rubrica se lo concede da solo (come la posizione). */
    static void ensureContactsPermission(Context c) {
        String perm = "android.permission.READ_CONTACTS";
        if (c.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            pm.getClass().getMethod("grantRuntimePermission", String.class, String.class, android.os.UserHandle.class)
                    .invoke(pm, c.getPackageName(), perm, android.os.Process.myUserHandle());
        } catch (Throwable ignored) {
        }
    }
}
