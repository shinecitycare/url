
package com.qmsbrowser;

import android.content.Context;
import android.net.Uri;

import java.util.Locale;

public final class NavigationPolicy {

    private NavigationPolicy() {}

    public static boolean isAllowedKioskHost(
            Context context,
            Uri uri,
            String configuredStartUrl,
            boolean restrictToStartHost) {

        if (uri == null) {
            return false;
        }

        String scheme = uri.getScheme();
        if (scheme == null
                || !("https".equalsIgnoreCase(scheme)
                || "http".equalsIgnoreCase(scheme))) {
            return false;
        }

        String host = uri.getHost();
        if (host == null || host.trim().isEmpty()) {
            return false;
        }

        host = host.toLowerCase(Locale.ROOT);

        // 1. Allow configured identity-provider hosts.
        String allowlist = BrowserPreferences.get(context)
                .getString(BrowserPreferences.IDP_ALLOWLIST, "");

        if (SecurityPolicy.isHostInAllowlist(host, allowlist)) {
            return true;
        }

        // 2. Explicitly allow only the required company websites.
        // Exact host and its genuine subdomains are accepted.
        if (isTrustedCompanyHost(host, "smssure.com")
                || isTrustedCompanyHost(host, "prarabdha.com")) {
            return true;
        }

        // 3. Preserve the existing startup-host restriction.
        if (configuredStartUrl == null
                || configuredStartUrl.trim().isEmpty()) {
            return false;
        }

        try {
            Uri startUri = Uri.parse(configuredStartUrl);
            String startHost = startUri.getHost();

            if (startHost == null) {
                return false;
            }

            return SecurityPolicy.isAllowedKioskHost(
                    host,
                    startHost.toLowerCase(Locale.ROOT),
                    restrictToStartHost
            );

        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isTrustedCompanyHost(
            String host,
            String trustedDomain) {

        return host.equals(trustedDomain)
                || host.endsWith("." + trustedDomain);
    }
}
