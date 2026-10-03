package me.crema.millietls;

import android.util.Log;
import java.security.Provider;
import java.security.Security;

/** Installs the API19-compatible Conscrypt provider before OkHttp initializes. */
public final class TlsInstaller {
    public static final String TAG = "MillieTls";
    private static volatile boolean installed;

    private TlsInstaller() {}

    public static synchronized void install() {
        try {
            if (installed) return;
            Log.i(TAG, "install: begin sdk=" + android.os.Build.VERSION.SDK_INT);
            // Resolve inside the handler so missing classes or JNI cannot stop startup.
            Class<?> conscrypt = Class.forName("org.conscrypt.Conscrypt", true,
                    TlsInstaller.class.getClassLoader());
            Provider provider = (Provider) conscrypt.getMethod("newProvider").invoke(null);
            int position = Security.insertProviderAt(provider, 1);
            installed = true;
            // OkHttp 3.12.12 selects Conscrypt through its normal provider lookup.
            // Default trust anchors and certificate/hostname verification stay intact.
            Log.i(TAG, "TLS engine ready: " + provider.getName() + " insertedAt=" + position);
        } catch (Throwable failure) {
            try {
                Log.e(TAG, "TLS install failed; startup continues", failure);
            } catch (Throwable ignored) {
                // Even diagnostic failures must not escape attachBaseContext().
            }
        }
    }

    public static boolean isInstalled() {
        return installed;
    }
}
