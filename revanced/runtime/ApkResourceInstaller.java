package me.crema.millietls;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;

/** Restores APK resource lookup for the separate packed-DEX loader on KitKat. */
public final class ApkResourceInstaller {
    private static final String TAG = "MillieResources";
    private static final String BUILTINS = "kotlin/kotlin.kotlin_builtins";

    private ApkResourceInstaller() {}

    public static void install(Context context) {
        if (Build.VERSION.SDK_INT >= 21) return;
        try {
            ClassLoader loader = Class.forName("kotlin.Unit", false,
                    context.getClassLoader()).getClassLoader();
            if (loader == null || hasBuiltIns(loader)) return;

            // The packed loader's parent is the boot loader, while the APK is
            // on its child PathClassLoader. Parent delegation cannot see it.
            // URLClassLoader supplies ZIP resources without loading APK DEX.
            URL apk = new File(context.getApplicationInfo().sourceDir).toURI().toURL();
            URLClassLoader resources = new URLClassLoader(new URL[] { apk }, loader.getParent());
            if (!hasBuiltIns(resources)) {
                throw new IllegalStateException("Kotlin built-ins missing from APK");
            }
            Field parent = ClassLoader.class.getDeclaredField("parent");
            parent.setAccessible(true);
            parent.set(loader, resources);
            if (!hasBuiltIns(loader)) {
                throw new IllegalStateException("Packed loader cannot access APK resources");
            }
            Log.i(TAG, "APK resources visible to packed classes");
        } catch (Throwable failure) {
            Log.e(TAG, "APK resource initialization failed", failure);
        }
    }

    private static boolean hasBuiltIns(ClassLoader loader) throws java.io.IOException {
        InputStream stream = loader.getResourceAsStream(BUILTINS);
        if (stream == null) return false;
        stream.close();
        return true;
    }
}
