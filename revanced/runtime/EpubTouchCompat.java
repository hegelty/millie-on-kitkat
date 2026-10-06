package me.crema.millietls;

import android.os.Build;

/** Avoid the viewer's temporary input focus cycle on the KitKat WebView. */
public final class EpubTouchCompat {
    // Exact function bodies from bundled 1.0.45 and downloaded 1.5.7 viewers.
    // Include the function boundary; do not rewrite unrelated focus calls.
    private static final String BUNDLED = "(){var t=document.createElement(\"input\");"
            + "t.setAttribute(\"type\",\"text\"),t.setAttribute(\"readonly\",\"readonly\"),"
            + "document.querySelector(\"body\").appendChild(t),t.focus(),t.blur(),t.parentNode.removeChild(t)}";
    private static final String DOWNLOADED = "(){var t=arguments.length>0&&void 0!==arguments[0]?arguments[0]:document,"
            + "e=t.createElement(\"input\");e.setAttribute(\"type\",\"text\"),e.setAttribute(\"readonly\",\"readonly\"),"
            + "t.querySelector(\"body\").appendChild(e),e.focus(),e.blur(),e.parentNode.removeChild(e)}";

    private EpubTouchCompat() {}

    public static String rewrite(String source) {
        if (Build.VERSION.SDK_INT != 19 || source == null) return source;
        if (source.indexOf("key:\"_onSelStart\"") < 0) return source;
        if (source.indexOf(BUNDLED) >= 0 && source.indexOf(DOWNLOADED) >= 0) return source;
        String marker = source.indexOf(BUNDLED) >= 0 ? BUNDLED : DOWNLOADED;
        int start = source.indexOf(marker);
        // Unknown or ambiguous viewer code remains unchanged.
        if (start < 0 || source.indexOf(marker, start + marker.length()) >= 0) return source;
        return source.substring(0, start) + "(){}" + source.substring(start + marker.length());
    }
}
