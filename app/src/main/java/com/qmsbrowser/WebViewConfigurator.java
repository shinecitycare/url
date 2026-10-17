package com.qmsbrowser;

import android.os.Build;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;

public final class WebViewConfigurator {
    private WebViewConfigurator() {}

    public static void configureSettings(WebView webView, boolean autofillEnabled) {
        configureSettings(webView, autofillEnabled, true);
    }

    public static void configurePopupSettings(WebView webView, boolean autofillEnabled) {
        configureSettings(webView, autofillEnabled, false);
    }

    private static void configureSettings(
            WebView webView,
            boolean autofillEnabled,
            boolean allowZoom
    ) {
        WebSettings settings = webView.getSettings();

        // Enable browser geolocation support for websites using navigator.geolocation.
        settings.setGeolocationEnabled(true);

        // Force WebView to use Light Mode regardless of Android system theme.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            webView.setForceDarkAllowed(false);
            settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        }

        // Disable algorithmic darkening on Android 13 and newer.
        if (Build.VERSION.SDK_INT >= 33) {
            settings.setAlgorithmicDarkeningAllowed(false);
        }

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportZoom(allowZoom);
        settings.setBuiltInZoomControls(allowZoom);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
            webView.setImportantForAutofill(
                    autofillEnabled
                            ? View.IMPORTANT_FOR_AUTOFILL_YES
                            : View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            );
        }
    }
}
