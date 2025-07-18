package ch.shiftcrypto.bitboxapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import mobileserver.Mobileserver;

public class MainActivity extends AppCompatActivity {
    static {
        System.loadLibrary("signal_handler");
    }
    public native void initsignalhandler();
    private final int PERMISSIONS_REQUEST_CAMERA_QRCODE = 0;
    private static final String ACTION_USB_PERMISSION = "ch.shiftcrypto.bitboxapp.USB_PERMISSION";
    // The WebView is configured with this as the base URL. The purpose is so that requests made
    // from the app include shiftcrypto.ch in the Origin header to allow Moonpay to load in the
    // iframe. Moonpay compares the origin against a list of origins configured in the Moonpay admin.
    // This is a security feature relevant for websites running in browsers, but in the case of the
    // BitBoxApp, it is useless, as any app can do this.
    //
    // Unfortunately there seems to be no simple way to include this header only in requests to Moonpay.
    private static final String BASE_URL = "https://shiftcrypto.ch/";

    // stores the request from onPermissionRequest until the user has granted or denied the permission.
    private PermissionRequest webViewpermissionRequest;

    GoService goService;

    // This is for the file picker dialog invoked by file upload forms in the WebView.
    // Used by e.g. MoonPay's KYC forms.
    private ValueCallback<Uri[]> filePathCallback;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private boolean hasInternetConnectivity(NetworkCapabilities capabilities) {
        // To avoid false positives, if we can't obtain connectivity info,
        // we return true.
        // Note: this should never happen per Android documentation, as:
        // - these can not be null it come from the onCapabilitiesChanged callback.
        // - when obtained with getNetworkCapabilities(network), they can only be null if the
        //   network is null or unknown, but we guard against both in the caller.
        if (capabilities == null) {
            Util.log("Got null capabilities when we shouldn't have. Assuming we are online.");
            return true;
        }


        boolean hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

        // Use VALIDATED where available (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // We need to check for both internet and validated, since validated reports that the system
            // found connectivity the last time it checked. But if this callback triggers when going offline
            // (e.g. airplane mode), this bit would still be true when we execute this method.
            boolean isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            return hasInternet && isValidated;
        }

        // Fallback for older devices
        return hasInternet;
    }

    private void checkConnectivity() {
        Network activeNetwork = connectivityManager.getActiveNetwork();

        // If there is no active network (e.g. airplane mode), there is no check to perform.
        if (activeNetwork == null) {
            Mobileserver.setOnline(false);
            return;
        }

        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);

        Mobileserver.setOnline(hasInternetConnectivity(capabilities));
    }

    // Connection to bind with GoService
    private ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            GoService.GoServiceBinder binder = (GoService.GoServiceBinder) service;
            goService = binder.getService();
            goService.setViewModelStoreOwner(MainActivity.this);
            Util.log("Bind connection completed!");
            startServer();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            goService = null;
            Util.log("Bind connection unexpectedly closed!");
        }
    };

    private class JavascriptBridge {
        private Context context;

        JavascriptBridge(Context context) {
            this.context = context;
        }

        @JavascriptInterface
        public void call(int queryID, String query) {
            Mobileserver.backendCall(queryID, query);
        }
    }

    private final BroadcastReceiver usbStateReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            handleIntent(intent);
        }
    };

    private BroadcastReceiver networkStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Mobileserver.usingMobileDataChanged();
        }
    };


    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        int currentNightMode = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        switch (currentNightMode) {
            case Configuration.UI_MODE_NIGHT_NO:
                // Night mode is not active, we're using the light theme
                setDarkTheme(false);
                break;
            case Configuration.UI_MODE_NIGHT_YES:
                // Night mode is active, we're using dark theme
                setDarkTheme(true);
                break;
        }
        super.onConfigurationChanged(newConfig);
    }

    public void setDarkTheme(boolean isDark) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = getWindow().getDecorView().getSystemUiVisibility(); // get current flag
            if (isDark) {
                Util.log("Dark theme");
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                getWindow().setStatusBarColor(ContextCompat.getColor(getApplicationContext(), R.color.colorPrimaryDark));
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;   // remove LIGHT_STATUS_BAR to flag
                getWindow().getDecorView().setSystemUiVisibility(flags);
            } else {
                Util.log("Light theme");
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                getWindow().setStatusBarColor(ContextCompat.getColor(getApplicationContext(), R.color.colorPrimary));
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;   // add LIGHT_STATUS_BAR to flag
                getWindow().getDecorView().setSystemUiVisibility(flags);
            }
        } else {
            Util.log("Status bar theme not updated");
        }
    }

    @SuppressLint("HandlerLeak")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Util.log("lifecycle: onCreate");

        initsignalhandler();

        getSupportActionBar().hide(); // hide title bar with app name.
        onConfigurationChanged(getResources().getConfiguration());
        setContentView(R.layout.activity_main);
        final WebView vw = (WebView)findViewById(R.id.vw);
        // For onramp iframe'd widgets like MoonPay.
        CookieManager.getInstance().setAcceptThirdPartyCookies(vw, true);

        // GoModel manages the Go backend. It is in a ViewModel so it only runs once, not every time
        // onCreate is called (on a configuration change like orientation change).
        final GoViewModel goViewModel = ViewModelProviders.of(this).get(GoViewModel.class);

        // The backend is run inside GoService, to avoid (as much as possible) latency errors due to
        // the scheduling when the app is out of focus.
        Intent intent = new Intent(this, GoService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);

        goViewModel.setMessageHandlers(
                new Handler() {
                    @Override
                    public void handleMessage(final Message msg) {
                        final GoViewModel.Response response = (GoViewModel.Response) msg.obj;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                vw.evaluateJavascript("window.onMobileCallResponse(" + String.valueOf(response.queryID) + ", " + response.response + ");", null);
                            }
                        });
                    }
                },
                new Handler() {
                    @Override
                    public void handleMessage(final Message msg) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                vw.evaluateJavascript("window.onMobilePushNotification(" + (String)(msg.obj) + ");", null);
                            }
                        });
                    }
                }
        );
        vw.clearCache(true);
        vw.clearHistory();
        vw.getSettings().setJavaScriptEnabled(true);
        vw.getSettings().setAllowUniversalAccessFromFileURLs(true);
        vw.getSettings().setAllowFileAccess(true);

        // For Moonpay widget: DOM storage and WebRTC camera access required.
        vw.getSettings().setDomStorageEnabled(true);
        vw.getSettings().setMediaPlaybackRequiresUserGesture(false);

        vw.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // override the default readText method, that doesn't work
                // because of read permission denied.
                view.evaluateJavascript(
                        "navigator.clipboard.readText = () => {" +
                        "    return androidClipboard.readFromClipboard();" +
                        "};", null);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(final WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    String url = request.getUrl().toString();
                    if (url != null && url.startsWith(BASE_URL)) {
                        // Intercept local requests and serve the response from the Android assets folder.
                        try {
                            InputStream inputStream = getAssets().open(url.replace(BASE_URL, "web/"));
                            String mimeType = Util.getMimeType(url);
                            if (mimeType != null) {
                                return new WebResourceResponse(mimeType, "UTF-8", inputStream);
                            }
                            Util.log("Unknown MimeType: " + url);
                        } catch (IOException e) {
                            Util.log("Internal resource not found: " + url);
                        }
                    } else {
                        // external request
                        // Unlike the Qt app, we don't allow requests based on which URL we are in
                        // currently within the React app, as it's very hard to figure what the
                        // current app URL is without having the frontend itself inform us.
                        return super.shouldInterceptRequest(view, request);
                    }
                } else {
                    Util.log("Null request!");
                }
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream("".getBytes()));
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view,  WebResourceRequest request) {
                // Block navigating to any external site inside the app.
                // This is only called if the whole page is about to change. Changes inside an iframe proceed normally.
                String url = request.getUrl().toString();

                try {
                    // Allow opening in external browser instead, for listed domains.
                    List<Pattern> patterns = new ArrayList<>();
                    patterns.add(Pattern.compile("^(.*\\.)?pocketbitcoin\\.com$"));
                    patterns.add(Pattern.compile("^(.*\\.)?moonpay\\.com$"));
                    patterns.add(Pattern.compile("^(.*\\.)?bitsurance\\.eu$"));
                    patterns.add(Pattern.compile("^(.*\\.)?btcdirect\\.eu$"));

                    for (Pattern pattern : patterns) {
                        if (pattern.matcher(request.getUrl().getHost()).matches()) {
                            Util.systemOpen(getApplication(), url);
                            return true;
                        }
                    }
                } catch(Exception e) {
                    Util.log(e.getMessage());
                }
                Util.log("Blocked: " + url);
                return true;
            }
        });

        // WebView.setWebContentsDebuggingEnabled(true); // enable remote debugging in chrome://inspect/#devices
        vw.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Util.log(consoleMessage.message() + " -- From line "
                        + consoleMessage.lineNumber() + " of "
                        + consoleMessage.sourceId());
                return super.onConsoleMessage(consoleMessage);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Handle webview permission request for camera when launching the QR code scanner.
                for (String resource : request.getResources()) {
                    if (resource.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                                == PackageManager.PERMISSION_GRANTED) {
                            // App already has the camera permission, so we grant the permission to
                            // the webview.
                            request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                            return;
                        }
                        // Otherwise we ask the user for permission.
                        MainActivity.this.webViewpermissionRequest = request;
                        ActivityCompat.requestPermissions(MainActivity.this,
                                new String[]{Manifest.permission.CAMERA},
                                PERMISSIONS_REQUEST_CAMERA_QRCODE);
                        // Permission will be granted or denied in onRequestPermissionsResult()
                        return;
                    }
                }
                request.deny();
            }

            // file picker result handler.
            ActivityResultLauncher<String> mGetContent = registerForActivityResult(new ActivityResultContracts.GetContent(),
                    new ActivityResultCallback<Uri>() {
                        @Override
                        public void onActivityResult(Uri uri) {
                            if (filePathCallback != null) {
                                if (uri != null) {
                                    filePathCallback.onReceiveValue(new Uri[]{uri});
                                } else {
                                    Util.log("Received null Uri in activity result");
                                    filePathCallback.onReceiveValue(new Uri[]{});
                                }
                                filePathCallback = null;
                            }
                        }
                    });
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                MainActivity.this.filePathCallback = filePathCallback;
                String[] mimeTypes = fileChooserParams.getAcceptTypes();
                String fileType = "*/*";
                if (mimeTypes.length == 1 && MimeTypeMap.getSingleton().hasMimeType(mimeTypes[0])) {
                    fileType = mimeTypes[0];
                }
                mGetContent.launch(fileType);
                return true;
            }
        });

        final String javascriptVariableName = "android";
        vw.addJavascriptInterface(new JavascriptBridge(this), javascriptVariableName);
        vw.addJavascriptInterface(new ClipboardHandler(this), "androidClipboard");
        vw.loadUrl(BASE_URL + "index.html");

        // We call updateDevice() here in case the app was started while the device was already connected.
        // In that case, handleIntent() is not called with ACTION_USB_DEVICE_ATTACHED.
        this.updateDevice();

        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(android.net.Network network, android.net.NetworkCapabilities capabilities) {
                super.onCapabilitiesChanged(network, capabilities);
                Mobileserver.setOnline(hasInternetConnectivity(capabilities));
            }
            // When we lose the network, onCapabilitiesChanged does not trigger, so we need to override onLost.
            @Override
            public void onLost(Network network) {
                super.onLost(network);
                Mobileserver.setOnline(false);
            }
        };

    }

    private void startServer() {
        final GoViewModel gVM = ViewModelProviders.of(this).get(GoViewModel.class);
        goService.startServer(getApplicationContext().getFilesDir().getAbsolutePath(), gVM.getGoEnvironment(), gVM.getGoAPI());

        // Trigger connectivity check (as the network may already be unavailable when the app starts).
        checkConnectivity();
    }

    private static String readRawText(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return null;
        }

        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder fileContent = new StringBuilder();
        String currentLine = bufferedReader.readLine();
        while (currentLine != null) {
            fileContent.append(currentLine);
            fileContent.append("\n");
            currentLine = bufferedReader.readLine();
        }
        return fileContent.toString();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        // This is only called reliably when intents are received (e.g. USB is attached or when
        // handling 'aopp:' URIs through the android.intent.action.VIEW intent) with
        // android:launchMode="singleTop"
        super.onNewIntent(intent);
        setIntent(intent); // make sure onResume will have access to this intent
    }

    @Override
    protected void onStart() {
        super.onStart();
        Util.log("lifecycle: onStart");
        final GoViewModel goViewModel = ViewModelProviders.of(this).get(GoViewModel.class);
        goViewModel.getIsDarkTheme().observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean isDarkTheme) {
                setDarkTheme(isDarkTheme);
            }
        });

        goViewModel.getAuthenticator().observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean requestAuth) {
                if (!requestAuth) {
                    return;
                }

                BiometricAuthHelper.showAuthenticationPrompt(MainActivity.this, new BiometricAuthHelper.AuthCallback() {
                    @Override
                    public void onSuccess() {
                        // Authenticated successfully
                        Util.log("Auth success");
                        goViewModel.closeAuth();
                        Mobileserver.authResult(true);
                    }

                    @Override
                    public void onFailure() {
                        // Failed
                        Util.log("Auth failed");
                        goViewModel.closeAuth();
                        Mobileserver.authResult(false);
                    }

                    @Override
                    public void onCancel() {
                        // Canceled
                        Util.log("Auth canceled");
                        goViewModel.closeAuth();
                        Mobileserver.cancelAuth();
                    }
                });
            }
        });

        goViewModel.getAuthSetting().observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean enabled) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (enabled) {
                            // Treat the content of the window as secure, preventing it from appearing in
                            // screenshots, the app switcher, or from being viewed on non-secure displays. We
                            // are really only interested in hiding the app contents from the app switcher -
                            // screenshots unfortunately also get disabled.
                            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
                        } else {
                            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                        }
                    }
                });
            }
        });

        NetworkRequest request = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build();
        // Register the network callback to listen for changes in network capabilities.
        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Util.log("lifecycle: onResume");
        Mobileserver.triggerAuth();

        // This is only called reliably when USB is attached with android:launchMode="singleTop"

        // Usb device list is updated on ATTACHED / DETACHED intents.
        // ATTACHED intent is an activity intent in AndroidManifest.xml so the app is launched when
        // a device is attached. On launch or when it is already running, onIntent() is called
        // followed by onResume(), where the intent is handled.
        // DETACHED intent is a broadcast intent which we register here.
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(this.usbStateReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(this.usbStateReceiver, filter);
        }


        // Listen on changes in the network connection. We are interested in if the user is connected to a mobile data connection.
        registerReceiver(this.networkStateReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        // Trigger connectivity check (as the network may already be unavailable when the app starts).
        checkConnectivity();

        Intent intent = getIntent();
        handleIntent(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Util.log("lifecycle: onPause");
        unregisterReceiver(this.usbStateReceiver);
        unregisterReceiver(this.networkStateReceiver);
    }

    private void handleIntent(Intent intent) {
        if (intent.getAction().equals(ACTION_USB_PERMISSION)) {
            // See https://developer.android.com/guide/topics/connectivity/usb/host#permission-d
            synchronized (this) {
                UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (device != null) {
                        Util.log("usb: permission granted");
                        final GoViewModel goViewModel = ViewModelProviders.of(this).get(GoViewModel.class);
                        goViewModel.setDevice(device);
                    }
                } else {
                    Util.log("usb: permission denied");
                }
            }
        }
        if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            Util.log("usb: attached");
            this.updateDevice();
        }
        if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
            Util.log("usb: detached");
            this.updateDevice();
        }
        // Handle 'aopp:' URIs. This is called when the app is launched and also if it is already
        // running and brought to the foreground.
        if (intent.getAction().equals(Intent.ACTION_VIEW)) {
            Uri uri = intent.getData();
            if (uri != null) {
                if (uri.getScheme().equals("aopp")) {
                    Mobileserver.handleURI(uri.toString());
                }
            }
        }
    }

    private void updateDevice() {
        // Triggered by usb device attached intent and usb device detached broadcast events.
        final GoViewModel goViewModel = ViewModelProviders.of(this).get(GoViewModel.class);
        goViewModel.setDevice(null);
        UsbManager manager = (UsbManager) getApplication().getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> deviceList = manager.getDeviceList();
        Iterator<UsbDevice> deviceIterator = deviceList.values().iterator();
        while (deviceIterator.hasNext()){
            UsbDevice device = deviceIterator.next();
            // One other instance where we filter vendor/product IDs is in
            // @xml/device_filter resource, which is used for USB_DEVICE_ATTACHED
            // intent to launch the app when a device is plugged and the app is still
            // closed. This filter, on the other hand, makes sure we feed only valid
            // devices to the Go backend once the app is launched or opened.
            //
            // BitBox02 Vendor ID: 0x03eb, Product ID: 0x2403.
            if (device.getVendorId() == 1003 && device.getProductId() == 9219) {
                if (manager.hasPermission(device)) {
                    goViewModel.setDevice(device);
                } else {
                    PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
                    manager.requestPermission(device, permissionIntent);
                }
                break; // only one device supported for now
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
        Util.log("lifecycle: onStop");
    }

    @Override
    protected void onRestart() {
        // This is here so that if the GoService gets killed while the app is in background it
        // will be started again.
        if (goService == null) {
            Intent intent = new Intent(this, GoService.class);
            bindService(intent, connection, Context.BIND_AUTO_CREATE);
        }
        super.onRestart();
    }

    @Override
    protected void onDestroy() {
        Util.log("lifecycle: onDestroy");
        if (goService != null) {
            unbindService(connection);
        }
        super.onDestroy();
        Util.quit(MainActivity.this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST_CAMERA_QRCODE:
                if (this.webViewpermissionRequest != null) {
                    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                        this.webViewpermissionRequest.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        this.webViewpermissionRequest.deny();
                    }
                    this.webViewpermissionRequest = null;
                }
                break;
        }
    }

    // Handle Android back button behavior:
    //
    // By default, if the webview can go back in browser history, we do that.
    // If there is no more history, we prompt the user to quit the app. If
    // confirmed, the app will be force quit.
    //
    // The default behavior can be modified by the frontend via the
    // window.onBackButtonPressed() function. See the `useBackButton` React
    // hook. It will be called first, and if it returns false, the default
    // behavior is prevented, otherwise we proceed with the above default
    // behavior.
    //
    // Without forced app process exit, some goroutines may remain active even after
    // the app resumption at which point new copies of goroutines are spun up.
    // Note that this is different from users tapping on "home" button or switching
    // to another app and then back, in which case no extra goroutines are created.
    //
    // A proper fix is to make the backend process run in a separate system thread.
    // Until such solution is implemented, forced app exit seems most appropriate.
    //
    // See the following for details about task and activity stacks:
    // https://developer.android.com/guide/components/activities/tasks-and-back-stack
    @Override
    public void onBackPressed() {
        runOnUiThread(new Runnable() {
            final WebView vw = (WebView) findViewById(R.id.vw);
            @Override
            public void run() {
                vw.evaluateJavascript("window.onBackButtonPressed();", value -> {
                    boolean doDefault = Boolean.parseBoolean(value);
                    if (doDefault) {
                        // Default behavior: go back in history if we can, otherwise prompt user
                        // if they want to quit the app.
                        if (vw.canGoBack()) {
                            vw.goBack();
                            return;
                        }
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Close BitBoxApp")
                                .setMessage("Do you really want to exit?")
                                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        Util.quit(MainActivity.this);
                                    }
                                })
                                .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        dialog.dismiss();
                                    }
                                })
                                .setIcon(android.R.drawable.ic_dialog_alert)
                                .show();
                    }
                });
            }
        });
    }
}
