package com.vands.vmixcontroller;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int CONTROLLER_PORT = 8090;
    private static final String FIXED_NAME = "http://vands-vmix.local:" + CONTROLLER_PORT;
    private static final String PREFS_NAME = "vs_vmix_controller_prefs";
    private static final String KEY_LAST_URL = "last_url";
    private static final int QUICK_PROBE_TIMEOUT_MS = 160;
    private static final int PROBE_TIMEOUT_MS = 260;

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView webView;
    private ExecutorService discoveryPool;
    private final AtomicBoolean discovering = new AtomicBoolean(false);
    private SharedPreferences prefs;
    private String connectedUrl = null;
    private int mainFrameErrors = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        configureWindow();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(8, 11, 16));
        root.setFitsSystemWindows(false);
        setContentView(root);

        applyFullscreen();
        startAutoConnect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyFullscreen();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyFullscreen();
    }

    private void configureWindow() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.rgb(8, 11, 16));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(params);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    private void applyFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    private void startAutoConnect() {
        if (discovering.getAndSet(true)) return;
        showSearching();
        if (discoveryPool != null) discoveryPool.shutdownNow();
        discoveryPool = Executors.newFixedThreadPool(48);

        new Thread(() -> {
            String found = discoverController();
            main.post(() -> {
                discovering.set(false);
                if (found != null) openController(found);
                else showNotFound();
            });
        }, "VS-Discovery").start();
    }

    private String discoverController() {
        String remembered = prefs.getString(KEY_LAST_URL, null);
        if (remembered != null && probe(remembered, QUICK_PROBE_TIMEOUT_MS)) return remembered;
        if (connectedUrl != null && !connectedUrl.equals(remembered) && probe(connectedUrl, QUICK_PROBE_TIMEOUT_MS)) return connectedUrl;
        if (probe(FIXED_NAME, QUICK_PROBE_TIMEOUT_MS)) return FIXED_NAME;

        List<String> priority = new ArrayList<>();
        priority.add("http://192.168.137.1:" + CONTROLLER_PORT);
        priority.add("http://192.168.43.1:" + CONTROLLER_PORT);
        priority.add("http://192.168.43.2:" + CONTROLLER_PORT);
        priority.add("http://192.168.232.2:" + CONTROLLER_PORT);
        priority.add("http://192.168.234.2:" + CONTROLLER_PORT);
        for (String u : priority) if (probe(u, QUICK_PROBE_TIMEOUT_MS)) return u;

        Set<String> subnets = collectLocalSubnets();
        for (String base : subnets) {
            String hit = scan24(base);
            if (hit != null) return hit;
        }
        return null;
    }

    private boolean probe(String baseUrl, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            URL url = new URL(baseUrl + "/appinfo");
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs + 160);
            c.setUseCaches(false);
            c.setRequestProperty("Connection", "close");
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            if (code < 200 || code >= 500) return false;

            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
            StringBuilder s = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null && s.length() < 4096) s.append(line);
            String body = s.toString().toLowerCase();
            return body.contains("lanurl") || body.contains("vmix") || body.contains("8090");
        } catch (Exception ignored) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private Set<String> collectLocalSubnets() {
        Set<String> bases = new LinkedHashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
                    byte[] b = addr.getAddress();
                    String base = (b[0] & 255) + "." + (b[1] & 255) + "." + (b[2] & 255) + ".";
                    bases.add(base);
                }
            }
        } catch (Exception ignored) {
        }
        return bases;
    }

    private String scan24(String base) {
        ExecutorCompletionService<String> cs = new ExecutorCompletionService<>(discoveryPool);
        List<Future<String>> jobs = new ArrayList<>();
        for (int host = 1; host <= 254; host++) {
            final String u = "http://" + base + host + ":" + CONTROLLER_PORT;
            jobs.add(cs.submit(() -> probe(u, PROBE_TIMEOUT_MS) ? u : null));
        }

        long deadline = System.currentTimeMillis() + 3200;
        try {
            for (int i = 0; i < jobs.size(); i++) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                Future<String> f = cs.poll(Math.min(remain, 250), java.util.concurrent.TimeUnit.MILLISECONDS);
                if (f == null) continue;
                String hit = f.get();
                if (hit != null) {
                    for (Future<String> j : jobs) j.cancel(true);
                    return hit;
                }
            }
        } catch (Exception ignored) {
        }

        for (Future<String> j : jobs) j.cancel(true);
        return null;
    }

    private void showSearching() {
        applyFullscreen();
        root.removeAllViews();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(28), dp(28), dp(28), dp(28));

        TextView title = text("V&S Vmix Controller", 25, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        box.addView(title, lp(-1, -2, 0, 0, 0, 12));

        TextView subTitle = text("FULLSCREEN · FAST CONNECT", 12, Color.rgb(96, 171, 255));
        subTitle.setGravity(Gravity.CENTER);
        box.addView(subTitle, lp(-1, -2, 0, 0, 0, 20));

        ProgressBar progress = new ProgressBar(this);
        box.addView(progress, lp(dp(58), dp(58), 0, 0, 0, 18));

        TextView msg = text("Finding controller…", 16, Color.rgb(220, 230, 242));
        msg.setGravity(Gravity.CENTER);
        box.addView(msg, lp(-1, -2, 0, 0, 0, 8));

        TextView sub = text(
            "Keep the PC controller running.\n" +
                "The app connects automatically on the same Wi-Fi, LAN, hotspot or supported USB-tether network.",
            13, Color.rgb(140, 157, 178));
        sub.setGravity(Gravity.CENTER);
        box.addView(sub, lp(-1, -2, 0, 0, 0, 0));

        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-1, -2);
        fp.gravity = Gravity.CENTER;
        root.addView(box, fp);
    }

    private void showNotFound() {
        applyFullscreen();
        root.removeAllViews();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(28), dp(28), dp(28), dp(28));

        TextView title = text("Controller not found", 22, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        box.addView(title, lp(-1, -2, 0, 0, 0, 12));

        TextView sub = text(
            "Open V&S Vmix Controller on the PC and make sure both devices are on the same local network.",
            14, Color.rgb(145, 160, 180));
        sub.setGravity(Gravity.CENTER);
        box.addView(sub, lp(-1, -2, 0, 0, 0, 18));

        Button retry = button("RETRY");
        retry.setOnClickListener(v -> startAutoConnect());
        box.addView(retry, lp(-1, dp(52), 0, 0, 0, 0));

        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-1, -2);
        fp.gravity = Gravity.CENTER;
        fp.leftMargin = dp(20);
        fp.rightMargin = dp(20);
        root.addView(box, fp);
    }

    private void openController(String url) {
        connectedUrl = url;
        prefs.edit().putString(KEY_LAST_URL, url).apply();
        mainFrameErrors = 0;
        applyFullscreen();
        root.removeAllViews();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(8, 11, 16));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        webView.setScrollbarFadingEnabled(true);
        webView.setKeepScreenOn(true);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setLoadsImagesAutomatically(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            s.setOffscreenPreRaster(true);
        }

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    view.loadUrl(u);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                mainFrameErrors = 0;
                applyFullscreen();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                mainFrameErrors++;
                if (mainFrameErrors >= 2) {
                    main.postDelayed(MainActivity.this::startAutoConnect, 300);
                }
            }
        });

        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));

        Button reconnect = new Button(this);
        reconnect.setText("↻");
        reconnect.setTextSize(18);
        reconnect.setTextColor(Color.WHITE);
        reconnect.setBackgroundColor(Color.argb(150, 18, 26, 36));
        reconnect.setPadding(0, 0, 0, 0);
        reconnect.setOnClickListener(v -> startAutoConnect());
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(44), dp(44));
        rp.gravity = Gravity.BOTTOM | Gravity.END;
        rp.rightMargin = dp(8);
        rp.bottomMargin = dp(10);
        root.addView(reconnect, rp);

        webView.loadUrl(url);
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.rgb(25, 55, 94));
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (discoveryPool != null) discoveryPool.shutdownNow();
        if (webView != null) webView.destroy();
    }
}
