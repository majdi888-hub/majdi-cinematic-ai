package com.majdi.cinematicai;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Installs the bundled ONNX models from the APK assets into app storage (once per version)
 * so ONNX Runtime can open them by path. Everything runs offline.
 */
final class ModelStore {

    static final String DEPTH = "depth_anything_v2_vits.onnx";
    static final String MATTE = "isnet-general-use.onnx";

    private final Context ctx;
    private SceneAnalyzer analyzer;
    private boolean tried;

    ModelStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Returns the analyzer, or null if the models are not packaged. Call off the UI thread. */
    synchronized SceneAnalyzer analyzer() {
        if (tried) return analyzer;
        tried = true;
        String d = install(DEPTH), m = install(MATTE);
        if (d == null && m == null) return null;
        int threads = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 2));
        analyzer = new SceneAnalyzer(d, m, threads);
        return analyzer;
    }

    private String install(String name) {
        File dir = new File(ctx.getFilesDir(), "models");
        if (!dir.exists() && !dir.mkdirs()) return null;
        File f = new File(dir, name);
        long size = -1;
        try (AssetFileDescriptor afd = ctx.getAssets().openFd("models/" + name)) {
            size = afd.getLength();
        } catch (Exception e) {
            // compressed or missing asset: fall back to streaming copy below
        }
        if (f.exists() && (size < 0 ? f.length() > 0 : f.length() == size)) return f.getAbsolutePath();
        File tmp = new File(dir, name + ".part");
        try (InputStream in = ctx.getAssets().open("models/" + name);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            tmp.delete();
            return null;
        }
        if (!tmp.renameTo(f)) return null;
        return f.getAbsolutePath();
    }
}
