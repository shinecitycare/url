
package com.qmsbrowser;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PermissionController {
    private final Activity activity;
    private final SharedPreferences preferences;

    private PermissionRequest webPermissionRequest;
    private String pendingWebPermissionOrigin;
    private String[] pendingWebPermissionResources;

    private GeolocationPermissions.Callback geolocationCallback;
    private String geolocationOrigin;
    private WebView geolocationWebView;

    private static final int REQUEST_WEB_PERMISSIONS = 102;
    private static final int REQUEST_LOCATION = 103;
    private static final String PREFS_NAME = "website_permissions_v1";

    public PermissionController(Activity activity) {
        this.activity = activity;
        this.preferences = activity.getSharedPreferences(
                PREFS_NAME, Activity.MODE_PRIVATE);
    }

    private void gpsLog(String message) {
        Log.d("GPS_DEBUG", message);
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).recordGpsDiagnostic(message);
        }
    }

    public void handlePermissionRequest(
            PermissionRequest request,
            WebView webView,
            boolean restrictToStartHost,
            String configuredStartUrl) {

        Uri origin = request.getOrigin();

        gpsLog("Web permission request: origin=" + origin + ", currentUrl="
                + (webView != null ? webView.getUrl() : "null") + ", resources="
                + java.util.Arrays.toString(request.getResources()));
        if (!isAllowedPermissionOrigin(
                origin, webView, restrictToStartHost, configuredStartUrl)) {
            gpsLog("Web permission denied by origin validation: " + origin);
            request.deny();
            return;
        }

        String originKey = getOriginKey(origin);
        List<String> supported = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> alreadyGranted = new ArrayList<>();

        for (String resource : request.getResources()) {
            String androidPermission = null;

            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                androidPermission = Manifest.permission.CAMERA;
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                androidPermission = Manifest.permission.RECORD_AUDIO;
            }

            // Never grant unsupported WebView resources.
            if (androidPermission == null) {
                continue;
            }

            supported.add(resource);

            if (activity.checkSelfPermission(androidPermission)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(androidPermission);
            } else if (isWebsitePermissionSaved(originKey, resource)) {
                alreadyGranted.add(resource);
            }
        }

        if (supported.isEmpty()) {
            request.deny();
            return;
        }

        // Reuse a previous website decision only when Android permission
        // is still granted. Do not display our custom dialog again.
        if (missing.isEmpty() && alreadyGranted.size() == supported.size()) {
            request.grant(alreadyGranted.toArray(new String[0]));
            return;
        }

        String originLabel = origin.getScheme() + "://" + origin.getAuthority();

        new AlertDialog.Builder(activity)
                .setTitle("Allow website access?")
                .setMessage(originLabel + " wants to use "
                        + describeWebResources(supported) + ".")
                .setNegativeButton("Block", (dialog, which) -> request.deny())
                .setPositiveButton("Allow", (dialog, which) -> {
                    if (missing.isEmpty()) {
                        saveWebsitePermissions(originKey, supported);
                        request.grant(supported.toArray(new String[0]));
                    } else {
                        webPermissionRequest = request;
                        pendingWebPermissionOrigin = originLabel;
                        pendingWebPermissionResources =
                                supported.toArray(new String[0]);

                        activity.requestPermissions(
                                missing.toArray(new String[0]),
                                REQUEST_WEB_PERMISSIONS);
                    }
                })
                .setOnCancelListener(dialog -> request.deny())
                .show();
    }

    public void handleGeolocationPermissionsShowPrompt(
            String origin,
            GeolocationPermissions.Callback callback,
            WebView webView,
            boolean restrictToStartHost,
            String configuredStartUrl) {

        Uri originUri = Uri.parse(origin);

        gpsLog("Geolocation prompt: origin=" + origin
                + ", currentUrl=" + (webView != null ? webView.getUrl() : "null")
                + ", hasLocationPermission=" + hasLocationPermission()
                + ", restrictToStartHost=" + restrictToStartHost
                + ", configuredStartUrl=" + configuredStartUrl);

        logDeviceLocationState();
        if (!isAllowedPermissionOrigin(
                originUri, webView, restrictToStartHost, configuredStartUrl)) {
            gpsLog("Geolocation denied: origin/current URL/allowed-host validation failed.");
            callback.invoke(origin, false, false);
            return;
        }

        String originKey = getOriginKey(originUri);

        // Previously allowed website + current Android location permission.
        if (isLocationPermissionSaved(originKey) && hasLocationPermission()) {
            gpsLog("Previously saved website location permission accepted: " + origin);
            callback.invoke(origin, true, true);
            return;
        }

        new AlertDialog.Builder(activity)
                .setTitle("Allow location access?")
                .setMessage(origin + " wants to use this device's location.")
                .setNegativeButton("Block", (dialog, which) -> {
                    gpsLog("User blocked website geolocation: " + origin);
                    callback.invoke(origin, false, false);
                })
                .setPositiveButton("Allow", (dialog, which) -> {
                    gpsLog("User pressed Allow. Android location permission=" + hasLocationPermission());
                    if (hasLocationPermission()) {
                        saveLocationPermission(originKey);
                        callback.invoke(origin, true, true);
                        gpsLog("Geolocation callback invoked: allowed=true (permission already granted).");
                    } else {
                        geolocationOrigin = origin;
                        geolocationCallback = callback;
                        geolocationWebView = webView;
                        gpsLog("Stored WebView for pending location permission: "
                                + (webView != null ? webView.getUrl() : "null"));

                        activity.requestPermissions(
                                new String[]{
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                },
                                REQUEST_LOCATION);
                    }
                })
                .setOnCancelListener(dialog -> {
                    gpsLog("Geolocation permission dialog cancelled: " + origin);
                    callback.invoke(origin, false, false);
                })
                .show();
    }

    public void handlePermissionResult(
            int requestCode,
            int[] grantResults,
            WebView webView,
            boolean restrictToStartHost,
            String configuredStartUrl) {

        if (requestCode == REQUEST_WEB_PERMISSIONS
                && webPermissionRequest != null) {

            PermissionRequest request = webPermissionRequest;
            String origin = pendingWebPermissionOrigin;
            String[] resources = pendingWebPermissionResources;

            boolean originAllowed = origin != null
                    && isAllowedPermissionOrigin(
                    Uri.parse(origin), webView,
                    restrictToStartHost, configuredStartUrl);

            boolean androidPermissionsGranted =
                    originAllowed && allResourcesHaveAndroidPermissions(resources);

            if (androidPermissionsGranted) {
                saveWebsitePermissions(getOriginKey(Uri.parse(origin)), resources);
                request.grant(resources);
            } else {
                request.deny();
            }

            webPermissionRequest = null;
            pendingWebPermissionOrigin = null;
            pendingWebPermissionResources = null;

        } else if (requestCode == REQUEST_LOCATION
                && geolocationCallback != null) {

            String origin = geolocationOrigin;
            GeolocationPermissions.Callback callback = geolocationCallback;

            boolean androidLocationGranted = hasLocationPermission();
            WebView requestingWebView = geolocationWebView != null ? geolocationWebView : webView;
            boolean originAllowed = origin != null
                    && isAllowedPermissionOrigin(
                    Uri.parse(origin), requestingWebView,
                    restrictToStartHost, configuredStartUrl);
            boolean allowed = origin != null && androidLocationGranted && originAllowed;

            gpsLog("Android permission result: origin=" + origin
                    + ", currentUrl=" + (requestingWebView != null ? requestingWebView.getUrl() : "null")
                    + ", androidLocationGranted=" + androidLocationGranted
                    + ", originAllowed=" + originAllowed
                    + ", finalAllowed=" + allowed);

            if (allowed) {
                saveLocationPermission(getOriginKey(Uri.parse(origin)));
            }

            callback.invoke(origin, allowed, allowed);
            gpsLog("Geolocation callback invoked: allowed=" + allowed
                    + ", retain=" + allowed);

            geolocationCallback = null;
            geolocationOrigin = null;
            geolocationWebView = null;
        }
    }

    private boolean allResourcesHaveAndroidPermissions(String[] resources) {
        if (resources == null || resources.length == 0) {
            return false;
        }

        for (String resource : resources) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                if (activity.checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            } else {
                return false;
            }
        }

        return true;
    }

    private boolean isWebsitePermissionSaved(String originKey, String resource) {
        Set<String> saved = preferences.getStringSet(
                "web_" + originKey, new HashSet<>());
        return saved.contains(resource);
    }

    private void saveWebsitePermissions(String originKey, String[] resources) {
        Set<String> saved = new HashSet<>(preferences.getStringSet(
                "web_" + originKey, new HashSet<>()));

        for (String resource : resources) {
            saved.add(resource);
        }

        preferences.edit()
                .putStringSet("web_" + originKey, saved)
                .apply();
    }

    private void saveWebsitePermissions(String originKey, List<String> resources) {
        saveWebsitePermissions(originKey, resources.toArray(new String[0]));
    }

    private boolean isLocationPermissionSaved(String originKey) {
        return preferences.getBoolean("location_" + originKey, false);
    }

    private void saveLocationPermission(String originKey) {
        preferences.edit()
                .putBoolean("location_" + originKey, true)
                .apply();
    }

    private String getOriginKey(Uri origin) {
        return Uri.encode(
                origin.getScheme().toLowerCase()
                        + "://" + origin.getAuthority().toLowerCase());
    }

    private boolean isAllowedPermissionOrigin(
            Uri origin,
            WebView webView,
            boolean restrictToStartHost,
            String configuredStartUrl) {

        if (origin == null || origin.getHost() == null) {
            gpsLog("Origin rejected: missing origin/host. origin=" + origin);
            return false;
        }

        String scheme = origin.getScheme();
        if (!"https".equalsIgnoreCase(scheme)
                && !"http".equalsIgnoreCase(scheme)) {
            gpsLog("Origin rejected: unsupported scheme=" + scheme);
            return false;
        }

        String currentUrl = webView != null ? webView.getUrl() : null;
        if (currentUrl == null) {
            gpsLog("Origin rejected: current WebView URL is null.");
            return false;
        }

        Uri current = Uri.parse(currentUrl);
        boolean sameAuthority = current.getAuthority() != null
                && current.getAuthority().equalsIgnoreCase(origin.getAuthority());
        boolean kioskHostAllowed = NavigationPolicy.isAllowedKioskHost(
                activity, origin, configuredStartUrl, restrictToStartHost);

        if (!sameAuthority) {
            gpsLog("Origin rejected: authority mismatch. origin="
                    + origin.getAuthority() + ", current=" + current.getAuthority());
        }
        if (!kioskHostAllowed) {
            gpsLog("Origin rejected: NavigationPolicy rejected host. origin="
                    + origin + ", configuredStartUrl=" + configuredStartUrl);
        }

        return sameAuthority && kioskHostAllowed;
    }

    private String describeWebResources(List<String> resources) {
        boolean camera = resources.contains(
                PermissionRequest.RESOURCE_VIDEO_CAPTURE);
        boolean microphone = resources.contains(
                PermissionRequest.RESOURCE_AUDIO_CAPTURE);

        return camera && microphone
                ? "your camera and microphone"
                : camera ? "your camera" : "your microphone";
    }

    private void logDeviceLocationState() {
        try {
            android.location.LocationManager manager = (android.location.LocationManager)
                    activity.getSystemService(Activity.LOCATION_SERVICE);
            boolean gps = manager != null && manager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER);
            boolean network = manager != null && manager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
            gpsLog("Device location providers: GPS=" + gps + ", NETWORK=" + network
                    + ", finePermission=" + (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                    + ", coarsePermission=" + (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED));
        } catch (Exception e) {
            gpsLog("Could not inspect location providers: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private boolean hasLocationPermission() {
        return activity.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || activity.checkSelfPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }
}
