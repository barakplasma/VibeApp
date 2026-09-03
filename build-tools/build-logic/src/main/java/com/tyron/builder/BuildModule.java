package com.tyron.builder;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.tyron.common.util.Decompress;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class BuildModule {

    private static final String TAG = "BuildModule";

    private static Context sApplicationContext;
    private static File sAndroidJar;
    private static File sLambdaStubs;

    /** Bundled library artifacts already extracted this process, keyed by asset name. */
    private static final Map<String, File> sBundledArtifacts = new HashMap<>();

    public static void initialize(Context applicationContext) {
            sApplicationContext = applicationContext.getApplicationContext();
    }

    public static Context getContext() {
        return sApplicationContext;
    }

    public static File getAndroidJar() {
        if (sAndroidJar == null) {
            Context context = BuildModule.getContext();
            if (context == null) {
                return null;
            }

            sAndroidJar = new File(context
                    .getFilesDir(), "rt.jar");
            if (!sAndroidJar.exists()) {
                Decompress.unzipFromAssets(BuildModule.getContext(),
                        "rt.zip",
                        sAndroidJar.getParentFile().getAbsolutePath());
            }
        }

        return sAndroidJar;
    }

    public static File getLambdaStubs() {
        if (sLambdaStubs == null) {
            sLambdaStubs = new File(BuildModule.getContext().getFilesDir(), "core-lambda-stubs.jar");

            if (!sLambdaStubs.exists()) {
                Decompress.unzipFromAssets(BuildModule.getContext(), "lambda-stubs.zip", sLambdaStubs.getParentFile().getAbsolutePath());
            }
        }
        return sLambdaStubs;
    }

    /**
     * Extracts a bundled library file (typically a jar) from assets into filesDir.
     *
     * <p>Assets ending in {@code .zip} are unzipped beside the target; anything else is
     * copied verbatim. Extraction happens once per process; the returned {@link File} is
     * not guaranteed to exist, so callers should check before use — a missing asset is
     * how a build flavor opts out of a library.
     *
     * @param assetName name of the asset, e.g. {@code "jsoup.jar.zip"}
     * @param fileName  resulting file directly under filesDir, e.g. {@code "jsoup.jar"}
     */
    public static File getBundledFile(@NonNull String assetName, @NonNull String fileName) {
        return resolveBundled(assetName, fileName, false);
    }

    /**
     * As {@link #getBundledFile}, but for zips whose entries make up a directory
     * (pre-compiled {@code .flat} resources, library assets, native libraries). The
     * archive is expanded into {@code dirName} rather than beside it.
     */
    public static File getBundledDir(@NonNull String assetName, @NonNull String dirName) {
        return resolveBundled(assetName, dirName, true);
    }

    private static synchronized File resolveBundled(String assetName, String name, boolean asDirectory) {
        File cached = sBundledArtifacts.get(assetName);
        if (cached != null) {
            return cached;
        }
        Context context = getContext();
        if (context == null) {
            return null;
        }
        File target = new File(context.getFilesDir(), name);
        if (!target.exists()) {
            if (assetName.endsWith(".zip")) {
                String destination = asDirectory
                        ? target.getAbsolutePath()
                        : target.getParentFile().getAbsolutePath();
                Decompress.unzipFromAssets(context, assetName, destination);
            } else {
                copyAsset(context, assetName, target);
            }
        }
        sBundledArtifacts.put(assetName, target);
        return target;
    }

    private static void copyAsset(Context context, String assetName, File target) {
        try (InputStream input = context.getAssets().open(assetName);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to copy bundled asset " + assetName, e);
        }
    }

    public static void setAndroidJar(@NonNull File jar) {
        sAndroidJar = jar;
    }

    public static void setLambdaStubs(File file) {
        sLambdaStubs = file;
    }
}
