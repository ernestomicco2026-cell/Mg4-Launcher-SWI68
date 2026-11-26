package com.mg4.launcher.svc;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 1.0.4: fotogrammi delle clip di ricarica, estratti una volta sola dagli MP4 e salvati come JPEG accanto alle clip
 * (cartella "fotogrammi/&lt;nome clip&gt;/0001.jpg…", più il file "pronto" con il numero di fotogrammi).
 * <p>
 * Perché: sull'auto la riproduzione con MediaPlayer su TextureView restava ferma sul primo fotogramma (schermo nero,
 * fase "aggancio" bloccata, log del 15/11). Con i fotogrammi già pronti l'animazione è disegnata da una vista
 * normale, senza decoder video a schermo: niente superfici nere e gli errori si vedono nel log.
 * <p>
 * 1.0.6: l'estrazione usa MediaCodec verso la memoria (YUV → JPEG); se non va, MediaMetadataRetriever un fotogramma
 * alla volta. In un thread di lavoro. Circa 290 fotogrammi per tema, 25–30 MB in tutto per un colore. Log "ricarica.fotogrammi".
 */
public final class ChargeFrames {
    private static final String DONE = "pronto", DIR = "fotogrammi";
    private static final int JPEG_Q = 88;
    /**
     * 1.0.7: marcatore nel file "pronto" dei fotogrammi con i colori corretti. Quelli della 1.0.6 (MediaCodec) leggevano
     * i colori "TV" (16-235, BT.709) delle clip come pieni: sfondo 222327 invece di 15171B (riquadro visibile attorno
     * all'auto) e bianco grigio.
     */
    private static final String COLOR_MARK = "colori2";

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
            String[] t = new String(read(done)).trim().split("\\s+");
            // 1.0.7: senza il marcatore dei colori corretti (fotogrammi della 1.0.6) si rifanno da capo
            if (t.length < 2 || !COLOR_MARK.equals(t[1])) return null;
            n = Integer.parseInt(t[0]);
        } catch (Exception e) {
            return null;
        }
        if (n <= 0) return null;
        File[] out = new File[n];
        for (int i = 0; i < n; i++) {
            out[i] = new File(d, String.format(java.util.Locale.US, "%04d.jpg", i + 1));
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
        long t0 = android.os.SystemClock.elapsedRealtime();
        // 1.0.6: prima MediaCodec verso la memoria (niente Surface), YUV → JPEG con YuvImage. Il metodo "a blocchi" di
        // MediaMetadataRetriever falliva sempre sulle auto ("No frames from retriever") ed è stato tolto.
        String codecErr;
        try {
            codecErr = buildWithCodec(clip, d);
        } catch (Throwable e) {
            codecErr = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage());
        }
        if (codecErr == null) {
            lastNote = "MediaCodec · " + lastWritten + " fotogrammi in " + (android.os.SystemClock.elapsedRealtime() - t0) / 1000 + " s · 0 ripetuti · " + convNote;
            return writeDone(d, lastWritten);
        }
        ChargePack.deleteTree(d);
        if (!d.mkdirs() && !d.isDirectory()) return "cartella non creata";
        String err = buildWithRetriever(clip, d);
        if (err == null) lastNote = lastNote + " (MediaCodec: " + codecErr + ")";
        return err;
    }

    private static int lastWritten;
    private static String convNote = "";

    /**
     * 1.0.7: YuvImage.compressToJpeg considera i valori YUV come JPEG (BT.601, pieni 0-255). Le clip sono BT.709 "TV"
     * (16-235): qui i valori NV21 si trasformano perché il JPEG dia gli stessi colori RGB della clip. È una
     * trasformazione lineare esatta: Y' dipende da Y, U, V del blocco; U', V' solo da U, V (niente media fra pixel).
     */
    static final class ColorConv {
        final int yy, yu, yv, uu, uv, vu, vv, yOff;
        final String note;

        private ColorConv(double kr, double kb, boolean limited, String note) {
            double kg = 1 - kr - kb;
            // YUV normalizzato → RGB
            double[][] a = {{1, 0, 2 * (1 - kr)}, {1, -2 * (1 - kb) * kb / kg, -2 * (1 - kr) * kr / kg}, {1, 2 * (1 - kb), 0}};
            double sy = limited ? 1 / 219.0 : 1 / 255.0, sc = limited ? 1 / 224.0 : 1 / 255.0;
            // RGB → YUV JPEG (BT.601 pieno) × 255
            double[][] b = {{0.299, 0.587, 0.114}, {-0.168736, -0.331264, 0.5}, {0.5, -0.418688, -0.081312}};
            double[][] t = new double[3][3];
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++) {
                    double v = 0;
                    for (int k = 0; k < 3; k++) v += b[i][k] * a[k][j];
                    t[i][j] = v * 255 * (j == 0 ? sy : sc);
                }
            yy = (int) Math.round(t[0][0] * 1024);
            yu = (int) Math.round(t[0][1] * 1024);
            yv = (int) Math.round(t[0][2] * 1024);
            uu = (int) Math.round(t[1][1] * 1024);
            uv = (int) Math.round(t[1][2] * 1024);
            vu = (int) Math.round(t[2][1] * 1024);
            vv = (int) Math.round(t[2][2] * 1024);
            yOff = limited ? 16 : 0;
            this.note = note;
        }

        /** null se i colori sono già quelli del JPEG (BT.601 pieni). Senza indicazioni: BT.709 TV, come le clip. */
        static ColorConv of(android.media.MediaFormat in, android.media.MediaFormat out) {
            int range = key(out, android.media.MediaFormat.KEY_COLOR_RANGE, key(in, android.media.MediaFormat.KEY_COLOR_RANGE, 0));
            int std = key(out, android.media.MediaFormat.KEY_COLOR_STANDARD, key(in, android.media.MediaFormat.KEY_COLOR_STANDARD, 0));
            boolean limited = range != android.media.MediaFormat.COLOR_RANGE_FULL;
            boolean bt601 = std == android.media.MediaFormat.COLOR_STANDARD_BT601_PAL || std == android.media.MediaFormat.COLOR_STANDARD_BT601_NTSC;
            if (!limited && bt601) return null;
            String n = "colori " + (bt601 ? "BT.601" : "BT.709") + (limited ? " TV" : " pieni") + " convertiti"
                    + (range == 0 && std == 0 ? " (non indicati dal decoder)" : "");
            return bt601 ? new ColorConv(0.299, 0.114, limited, n) : new ColorConv(0.2126, 0.0722, limited, n);
        }

        private static int key(android.media.MediaFormat f, String k, int def) {
            try {
                return f != null && f.containsKey(k) ? f.getInteger(k) : def;
            } catch (Throwable e) {
                return def;
            }
        }

        private static byte clamp(int v) {
            return (byte) (v < 0 ? 0 : v > 255 ? 255 : v);
        }

        /** NV21 (Y piena, poi VU alternati a metà risoluzione) trasformato sul posto. */
        void apply(byte[] nv21, int w, int h) {
            int cw = w / 2, base = w * h;
            for (int r = 0; r < h / 2; r++) {
                for (int c = 0; c < cw; c++) {
                    int ci = base + (r * cw + c) * 2;
                    int v = (nv21[ci] & 0xff) - 128, u = (nv21[ci + 1] & 0xff) - 128;
                    int cy = yu * u + yv * v;
                    for (int dy = 0; dy < 2; dy++) {
                        int yi = (2 * r + dy) * w + 2 * c;
                        nv21[yi] = clamp((yy * ((nv21[yi] & 0xff) - yOff) + cy + 512) >> 10);
                        nv21[yi + 1] = clamp((yy * ((nv21[yi + 1] & 0xff) - yOff) + cy + 512) >> 10);
                    }
                    nv21[ci] = clamp(128 + ((vu * u + vv * v + 512) >> 10));
                    nv21[ci + 1] = clamp(128 + ((uu * u + uv * v + 512) >> 10));
                }
            }
        }
    }

    private static String writeDone(File d, int written) {
        if (written <= 0) return "nessun fotogramma";
        try (FileOutputStream fo = new FileOutputStream(new File(d, DONE))) {
            fo.write((written + " " + COLOR_MARK).getBytes());
            return null;
        } catch (Exception e) {
            return "file pronto non scritto";
        }
    }

    /** Decodifica con MediaCodec in memoria; null se ha scritto tutti i fotogrammi, altrimenti il motivo. */
    private static String buildWithCodec(File clip, File d) throws Exception {
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        android.media.MediaCodec codec = null;
        lastWritten = 0;
        try {
            ex.setDataSource(clip.getAbsolutePath());
            int track = -1;
            android.media.MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                android.media.MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(android.media.MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0) return "nessuna traccia video";
            ex.selectTrack(track);
            String mime = fmt.getString(android.media.MediaFormat.KEY_MIME);
            fmt.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            codec = android.media.MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            ColorConv conv = null;
            boolean convSet = false;
            boolean inEos = false;
            int written = 0, idle = 0;
            byte[] nv21 = null;
            while (true) {
                if (!inEos) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        java.nio.ByteBuffer buf = codec.getInputBuffer(in);
                        int n = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                        } else {
                            codec.queueInputBuffer(in, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 10_000);
                if (out >= 0) {
                    idle = 0;
                    if (!convSet) {
                        convSet = true;
                        conv = ColorConv.of(fmt, codec.getOutputFormat());
                        convNote = conv == null ? "colori già pieni BT.601" : conv.note;
                    }
                    if (info.size > 0) {
                        android.media.Image img = codec.getOutputImage(out);
                        if (img == null) {
                            codec.releaseOutputBuffer(out, false);
                            return "getOutputImage nullo";
                        }
                        try {
                            android.graphics.Rect crop = img.getCropRect();
                            int w = crop.width() & ~1, h = crop.height() & ~1;
                            if (nv21 == null || nv21.length != w * h * 3 / 2) nv21 = new byte[w * h * 3 / 2];
                            toNv21(img, crop, w, h, nv21);
                            if (conv != null) conv.apply(nv21, w, h);
                            written++;
                            File o = new File(d, String.format(java.util.Locale.US, "%04d.jpg", written));
                            try (FileOutputStream fo = new FileOutputStream(o)) {
                                if (!new android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null)
                                        .compressToJpeg(new android.graphics.Rect(0, 0, w, h), JPEG_Q, fo))
                                    return "fotogramma " + written + " non salvato";
                            }
                        } finally {
                            img.close();
                        }
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } else if (out == android.media.MediaCodec.INFO_TRY_AGAIN_LATER && inEos && ++idle > 300) {
                    break;   // 3 s senza uscite dopo la fine dell'ingresso
                }
            }
            lastWritten = written;
            // controllo: devono esserci (quasi) tutti i fotogrammi attesi
            int expected = -1;
            try {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                r.setDataSource(clip.getAbsolutePath());
                expected = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT));
                r.release();
            } catch (Throwable ignored) {
            }
            if (written == 0) return "nessun fotogramma decodificato";
            if (expected > 0 && written < expected) return "decodificati " + written + " fotogrammi su " + expected;
            return null;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                }
                codec.release();
            }
            ex.release();
        }
    }

    /** YUV_420_888 (qualsiasi disposizione dei piani) → NV21 w×h dal riquadro crop. */
    private static void toNv21(android.media.Image img, android.graphics.Rect crop, int w, int h, byte[] out) {
        android.media.Image.Plane[] pl = img.getPlanes();
        // Y
        java.nio.ByteBuffer yb = pl[0].getBuffer();
        int yRow = pl[0].getRowStride(), yPix = pl[0].getPixelStride();
        int pos = 0;
        for (int r = 0; r < h; r++) {
            int base = (crop.top + r) * yRow + crop.left * yPix;
            if (yPix == 1) {
                yb.position(base);
                yb.get(out, pos, w);
                pos += w;
            } else {
                for (int c = 0; c < w; c++) out[pos++] = yb.get(base + c * yPix);
            }
        }
        // V e U alternati (NV21 = VU)
        java.nio.ByteBuffer ub = pl[1].getBuffer(), vb = pl[2].getBuffer();
        int uRow = pl[1].getRowStride(), uPix = pl[1].getPixelStride();
        int vRow = pl[2].getRowStride(), vPix = pl[2].getPixelStride();
        int cw = w / 2, chh = h / 2, ct = crop.top / 2, cl = crop.left / 2;
        for (int r = 0; r < chh; r++) {
            for (int c = 0; c < cw; c++) {
                out[pos++] = vb.get((ct + r) * vRow + (cl + c) * vPix);
                out[pos++] = ub.get((ct + r) * uRow + (cl + c) * uPix);
            }
        }
    }

    /** Metodo di riserva: MediaMetadataRetriever un fotogramma alla volta (per indice, poi per tempo). */
    private static String buildWithRetriever(File clip, File d) {
        long t0 = android.os.SystemClock.elapsedRealtime();
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
            File prev = null;
            for (int idx = 0; idx < n; idx++) {
                Bitmap b = null;
                try {
                    b = r.getFrameAtIndex(idx);
                } catch (Throwable ignored) {
                }
                if (b == null) {
                    try {
                        b = r.getFrameAtTime(idx * 1_000_000L / 30 + 16_000, MediaMetadataRetriever.OPTION_CLOSEST);
                    } catch (Throwable ignored) {
                    }
                }
                written++;
                File out = new File(d, String.format(java.util.Locale.US, "%04d.jpg", written));
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
            if (missing * 10 > n) return missing + " fotogrammi su " + n + " non letti";
            lastNote = "uno alla volta · " + written + " fotogrammi in " + (android.os.SystemClock.elapsedRealtime() - t0) / 1000
                    + " s · " + missing + " ripetuti";
            return writeDone(d, written);
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
