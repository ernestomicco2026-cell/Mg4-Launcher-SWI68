package com.mg4.launcher.svc;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * 1.0.0: auto senza internet. Invio dei log, controllo aggiornamenti e loghi condividono una sola pausa crescente
 * (1, 2, 4, 8, 16, 30, 60 min) dopo un errore di rete (DNS, connessione, timeout, SSL), invece di riprovare ognuno per
 * conto suo. Nella diagnostica c'è una sola riga "rete.offline" al posto di un errore per ogni tentativo.
 */
public final class Offline {
    private static final long MIN_MS = 60_000L, MAX_MS = 60 * 60_000L;

    private static long until, step = MIN_MS, since;
    private static int failures;
    private static String lastError = "";
    private static final LinkedHashSet<String> who = new LinkedHashSet<>();

    private Offline() {}

    /** True se è un errore di rete (non un errore del server o dei dati). */
    public static boolean isNetError(Throwable e) {
        // un certificato rifiutato (es. orologio dell'auto indietro) non è "offline": resta un errore con la sua causa
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.security.cert.CertificateException || t instanceof java.security.cert.CertPathValidatorException)
                return false;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException || t instanceof java.net.ConnectException
                    || t instanceof java.net.SocketTimeoutException || t instanceof java.net.NoRouteToHostException
                    || t instanceof java.net.SocketException || t instanceof javax.net.ssl.SSLException) return true;
        }
        return false;
    }

    /** True se si può provare ad andare in rete adesso (nessuna pausa in corso). */
    public static synchronized boolean canTry() {
        return System.currentTimeMillis() >= until;
    }

    /** Minuti che mancano alla fine della pausa (0 = nessuna pausa). */
    public static synchronized long waitMs() {
        return Math.max(0, until - System.currentTimeMillis());
    }

    /**
     * Da chiamare quando un'operazione di rete fallisce. Se è un errore di rete allunga la pausa comune, aggiorna la
     * riga della diagnostica e restituisce true (il chiamante non scrive altri errori); altrimenti restituisce false.
     */
    public static synchronized boolean failed(String what, Throwable e) {
        if (!isNetError(e)) return false;
        long now = System.currentTimeMillis();
        if (failures == 0) since = now;
        failures++;
        who.add(what);
        Throwable root = e;
        while (root.getCause() != null) root = root.getCause();
        lastError = e.getClass().getSimpleName() + (root != e ? " ← " + root.getClass().getSimpleName() : "");
        if (now >= until) {
            until = now + step;
            step = Math.min(MAX_MS, step * 2);
        }
        write();
        return true;
    }

    /** Da chiamare quando un'operazione di rete riesce: fine della pausa. */
    public static synchronized void ok() {
        if (failures == 0) return;
        Diag.put("rete.offline", "no · tornata alle " + hhmm(System.currentTimeMillis()) + " dopo " + failures
                + " tentativi falliti (dalle " + hhmm(since) + ")");
        failures = 0;
        until = 0;
        step = MIN_MS;
        who.clear();
    }

    private static void write() {
        Diag.put("rete.offline", "sì dalle " + hhmm(since) + " · " + failures + " tentativi falliti (" + String.join(", ", who)
                + ") · ultimo errore " + lastError + " · prossimo tentativo alle " + hhmm(until));
    }

    private static String hhmm(long t) {
        return new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(t));
    }
}
