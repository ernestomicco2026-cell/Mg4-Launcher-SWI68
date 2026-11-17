package com.mg4.launcher.svc;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * 1.0.4: fotogrammi delle clip di ricarica, estratti una volta sola dagli MP4 e salvati come JPEG accanto alle clip
 * (cartella "fotogrammi/&lt;nome clip&gt;/0001.jpg…", più il file "pronto" con il numero di fotogrammi).
 * <p>
 * Perché: sull'auto la riproduzione con MediaPlayer su TextureView restava ferma sul primo fotogramma (schermo nero,
 * fase "aggancio" bloccata, log del 15/11). Con i fotogrammi già pronti l'animazione è disegnata da una vista
 * normale, senza decoder video a schermo: niente superfici nere e gli errori si vedono nel log.
 * <p>
 * L'estrazione usa MediaMetadataRetriever (lo stesso delle miniature della galleria) a blocchi di 8 fotogrammi, in un
 * thread di lavoro. Circa 290 fotogrammi per tema, 25–30 MB in tutto per un colore. Log "ricarica.fotogrammi".
 */
public final class ChargeFrames {
    private static final String DONE = "pronto", DIR = "fotogrammi";
    private static final int CHUNK = 8, JPEG_Q = 88;

    private ChargeFrames() {}

    /** Cartella dei fotogrammi di una clip. */
    public static File dir(File clip) {
        String n = clip.getName();
        int dot = n.lastIndexOf('.');
        return new File(new File(clip.getParentFile(), DIR), dot > 0 ? n.substring(0, dot) : n);
    }

    /** Fotogrammi pronti della clip (in ordine), oppure null se non ancora estratti. */
    public static File[] frames(File clip) {
        if (clip == null) return null;
        File d = dir(clip);
        File done = new File(d, DONE);
        if (!done.isFile()) return null;
        int n;
        try {
            n = Integer.parseInt(new String(read(done)).trim());
        } catch (Exception e) {
            return null;
        }
        if (n <= 0) return null;
        File[] out = new File[n];
        for (int i = 0; i < n; i++) {
            out[i] = new File(d, String.format(java.util.Locale.ROOT, "%04d.jpg", i + 1));
            if (!out[i].isFile()) return null;
        }
        return out;
    }

    /** true se tutte le clip della lista hanno i fotogrammi pronti. */
    public static boolean ready(File... clips) {
        for (File c : clips) if (c != null && frames(c) == null) return false;
        return true;
    }

    private static final Object LOCK = new Object();

    /**
     * Estrae i fotogrammi delle clip che non li hanno ancora. Da un thread di lavoro (blocca). Restituisce null se tutto
     * è andato bene, altrimenti il motivo.
     */
    public static String build(File... clips) {
        synchronized (LOCK) {
            long t0 = android.os.SystemClock.elapsedRealtime();
            int total = 0, made = 0;
            for (File c : clips) {
                if (c == null || frames(c) != null) continue;
                String err = buildOne(c);
                if (err != null) {
                    Diag.put("ricarica.fotogrammi", "errore " + c.getName() + ": " + err);
                    return err;
                }
                made++;
                File[] f = frames(c);
                total += f == null ? 0 : f.length;
            }
            if (made > 0) Diag.put("ricarica.fotogrammi", "pronti: " + made + " clip, " + total + " fotogrammi in "
                    + (android.os.SystemClock.elapsedRealtime() - t0) / 1000 + " s · " + lastNote);
            return null;
        }
    }

    private static String buildOne(File clip) {
        File d = dir(clip);
        ChargePack.deleteTree(d);
        if (!d.mkdirs() && !d.isDirectory()) return "cartella non creata";
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(clip.getAbsolutePath());
            int n = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT));
            if (n <= 0) {
                long ms = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                n = (int) Math.round(ms * 30 / 1000.0);
            }
            if (n <= 0) return "numero di fotogrammi sconosciuto";
            int written = 0, missing = 0;
            String chunkErr = null;
            boolean chunks = true;   // getFramesAtIndex: se fallisce una volta non si riprova
            File prev = null;
            for (int i = 0; i < n; i += CHUNK) {
                int cnt = Math.min(CHUNK, n - i);
                List<Bitmap> list = null;
                if (chunks) {
                    try {
                        list = r.getFramesAtIndex(i, cnt);
                    } catch (Throwable e) {
                        chunkErr = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage());
                    }
                    if (list == null || list.size() != cnt) {
                        chunks = false;
                        if (list != null) for (Bitmap b : list) if (b != null) b.recycle();
                        list = null;
                    }
                }
                for (int j = 0; j < cnt; j++) {
                    int idx = i + j;
                    Bitmap b = list != null ? list.get(j) : null;
                    if (b == null) {   // un fotogramma alla volta: per indice, poi per tempo (a metà del fotogramma)
                        try {
                            b = r.getFrameAtIndex(idx);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (b == null) {
                        try {
                            b = r.getFrameAtTime(idx * 1_000_000L / 30 + 16_000, MediaMetadataRetriever.OPTION_CLOSEST);
                        } catch (Throwable ignored) {
                        }
                    }
                    written++;
                    File out = new File(d, String.format(java.util.Locale.ROOT, "%04d.jpg", written));
                    if (b == null) {
                        // fotogramma illeggibile (succede verso la fine delle clip): si ripete il precedente
                        missing++;
                        if (prev == null || !copy(prev, out)) return "fotogramma " + written + " non letto";
                        continue;
                    }
                    try (FileOutputStream fo = new FileOutputStream(out)) {
                        if (!b.compress(Bitmap.CompressFormat.JPEG, JPEG_Q, fo)) return "fotogramma " + written + " non salvato";
                    } finally {
                        b.recycle();
                    }
                    prev = out;
                }
            }
            if (missing * 10 > n) return missing + " fotogrammi su " + n + " non letti";
            lastNote = (chunks ? "a blocchi" : "uno alla volta" + (chunkErr != null ? " (blocchi: " + chunkErr + ")" : ""))
                    + (missing > 0 ? ", " + missing + " ripetuti" : "");
            if (written <= 0) return "nessun fotogramma";
            try (FileOutputStream fo = new FileOutputStream(new File(d, DONE))) {
                fo.write(String.valueOf(written).getBytes());
            }
            return null;
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String lastNote = "";

    private static boolean copy(File from, File to) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int parse(String s) {
        try {
            return s == null ? -1 : Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static byte[] read(File f) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(64, f.length())];
            int n = in.read(b);
            return n <= 0 ? new byte[0] : java.util.Arrays.copyOf(b, n);
        }
    }
}
