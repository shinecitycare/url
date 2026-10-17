import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.SafeBrowsingResponse;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Switch;
import android.widget.Toast;
import android.util.Log;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.AnimatorListenerAdapter;
import android.view.animation.LinearInterpolator;
import android.view.animation.DecelerateInterpolator;

import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int REQUEST_SETTINGS = 100;
    private static final int REQUEST_FILE = 101;
    private static final int REQUEST_WEB_PERMISSIONS = 102;
    private static final int REQUEST_LOCATION = 103;
    private static final int REQUEST_DOWNLOAD = 104;
    private static final int REQUEST_SCAN_QR = 105;
    private static final long AUTO_HIDE_DELAY_MS = 2200;

    private FrameLayout root;
    private FrameLayout webViewContainer;
    private ImageView remoteCursor;
    private LinearLayout toolbar;
    private LinearLayout addressContainer;
    private ImageButton backButton;
    private ImageButton forwardButton;
    private ImageButton reloadButton;
    private ImageButton clearAddressButton;
    private ImageButton menuButton;
    private ImageView securityIcon;
    private BrowserWebView webView;
    private EditText addressBar;
    private ProgressBar progressBar;
    private FrameLayout pullIndicator;
    private BrowserErrorView browserErrorView;
    private ImageView pullIndicatorIcon;
    private ObjectAnimator reloadAnimator;
    private ObjectAnimator pullIndicatorAnimator;
    private ValueCallback<Uri[]> fileCallback;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideToolbarRunnable = () -> hideToolbar(true);
    private boolean toolbarVisible = true;
    private boolean addressFocused;
    private boolean pullArmed;
    private boolean isRefreshing;
    private boolean gestureStartedAtTop;
    private boolean gestureStartedWithToolbarHidden;
    private float gestureStartY = -1;
    private String mobileUserAgent;
    private String configuredStartUrl = BrowserPreferences.DEFAULT_START_URL;
    private boolean fullscreenMode;
    private boolean restrictToStartHost;
    private boolean blockExternalApps;
    private LinearLayout findBar;
    private View findBarDivider;
    private EditText findInput;
    private TextView findStatus;
    private RemoteControlClient remoteClient;
    private RemotePairingStore remotePairingStore;
    private String remoteControlTopic;
    private String remoteControlSecret;
    private boolean remotePairingPersistent;
    private AlertDialog remoteControlDialog;
    private float remotePointerX;
    private float remotePointerY;
    
    // Phase 1: Recovery and state tracking
    private BrowserRecoveryController recoveryController;
    private boolean hasLoadError = false;

    // Phase 2: Auth, Autofill, and SSO
    private AuthenticationController authController;
    private android.app.Dialog popupDialog;
    private WebView popupWebView;

    // Phase 4: Download and permission controllers
    private DownloadController downloadController;
    private PermissionController permissionController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.AppTheme);
        super.onCreate(savedInstanceState);
        
        remotePairingStore = new RemotePairingStore(this);
        RemotePairingStore.Pairing pairing = remotePairingStore.loadOrCreate();
        remoteControlTopic = pairing.topic;
        remoteControlSecret = pairing.secret;
        remotePairingPersistent = pairing.persistent;
        if (pairing.enabled) {
            remoteClient = createRemoteClient();
            remoteClient.start();
        }
        
        // Apply dark theme colors to status and navigation bars
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        getWindow().setStatusBarColor(Color.rgb(16, 17, 36));
        getWindow().setNavigationBarColor(Color.rgb(16, 17, 36));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }

        authController = new AuthenticationController(this);
        downloadController = new DownloadController(this);
        permissionController = new PermissionController(this);
        buildBrowser();
        applyPreferences(false);

        recoveryController = new BrowserRecoveryController(this, new BrowserRecoveryController.RecoveryListener() {
            @Override
            public void onReloadRequired() {
                runOnUiThread(() -> {
                    if (webView != null) {
                        Log.d("MainActivity", "Recovery reload triggered");
                        webView.reload();
                    }
                });
            }
