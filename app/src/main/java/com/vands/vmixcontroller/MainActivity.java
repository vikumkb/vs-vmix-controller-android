package com.vands.vmixcontroller;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
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
    private static final int PROBE_TIMEOUT_MS = 280;

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView webView;
    private ExecutorService discoveryPool;
    private AtomicBoolean discovering = new AtomicBoolean(false);
    private String connectedUrl = null;
    private int mainFrameErrors = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(8,11,16));
        getWindow().setNavigationBarColor(Color.rgb(8,11,16));
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(8,11,16));
        setContentView(root);
        startAutoConnect();
    }

    private void startAutoConnect() {
        if (discovering.getAndSet(true)) return;
        showSearching();
        discoveryPool = Executors.newFixedThreadPool(40);
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
        if (probe(FIXED_NAME)) return FIXED_NAME;
        List<String> priority = new ArrayList<>();
        priority.add("http://192.168.137.1:" + CONTROLLER_PORT);
        priority.add("http://192.168.43.2:" + CONTROLLER_PORT);
        priority.add("http://192.168.232.2:" + CONTROLLER_PORT);
        priority.add("http://192.168.234.2:" + CONTROLLER_PORT);
        for (String u : priority) if (probe(u)) return u;
        Set<String> subnets = collectLocalSubnets();
        for (String base : subnets) {
            String hit = scan24(base);
            if (hit != null) return hit;
        }
        return null;
    }

    private boolean probe(String baseUrl) {
        HttpURLConnection c = null;
        try {
            URL url = new URL(baseUrl + "/appinfo");
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(PROBE_TIMEOUT_MS);
            c.setReadTimeout(PROBE_TIMEOUT_MS + 180);
            c.setUseCaches(false);
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
        } catch (Exception ignored) {}
        return bases;
    }

    private String scan24(String base) {
        ExecutorCompletionService<String> cs = new ExecutorCompletionService<>(discoveryPool);
        List<Future<String>> jobs = new ArrayList<>();
        for (int host = 1; host <= 254; host++) {
            final String u = "http://" + base + host + ":" + CONTROLLER_PORT;
            jobs.add(cs.submit(() -> probe(u) ? u : null));
        }
        long deadline = System.currentTimeMillis() + 3500;
        try {
            for (int i = 0; i < jobs.size(); i++) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                Future<String> f = cs.poll(Math.min(remain, 350), java.util.concurrent.TimeUnit.MILLISECONDS);
                if (f == null) continue;
                String hit = f.get();
                if (hit != null) {
                    for (Future<String> j : jobs) j.cancel(true);
                    return hit;
                }
            }
        } catch (Exception ignored) {}
        for (Future<String> j : jobs) j.cancel(true);
        return null;
    }

    private void showSearching() {
        root.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(28), dp(28), dp(28), dp(28));
        TextView title = text("V&S vMix Controller", 25, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        box.addView(title, lp(-1,-2,0,0,0,12));
        ProgressBar progress = new ProgressBar(this);
        box.addView(progress, lp(dp(58),dp(58),0,0,0,18));
        TextView msg = text("Finding controller…", 16, Color.rgb(220,230,242));
        msg.setGravity(Gravity.CENTER);
        box.addView(msg, lp(-1,-2,0,0,0,8));
        TextView sub = text("Keep the PC controller running.\nThe app connects automatically on the same Wi-Fi, LAN, hotspot or supported USB-tether network.", 13, Color.rgb(140,157,178));
        sub.setGravity(Gravity.CENTER);
        box.addView(sub, lp(-1,-2,0,0,0,0));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-1,-2);
        fp.gravity = Gravity.CENTER;
        root.addView(box, fp);
    }

    private void showNotFound() {
        root.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(28),dp(28),dp(28),dp(28));
        TextView title = text("Controller not found", 22, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        box.addView(title, lp(-1,-2,0,0,0,12));
        TextView sub = text("Open V&S vMix Controller on the PC and make sure both devices are on the same local network.", 14, Color.rgb(145,160,180));
        sub.setGravity(Gravity.CENTER);
        box.addView(sub, lp(-1,-2,0,0,0,18));
        Button retry = button("RETRY");
        retry.setOnClickListener(v -> startAutoConnect());
        box.addView(retry, lp(-1,dp(52),0,0,0,0));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-1,-2);
        fp.gravity = Gravity.CENTER;
        fp.leftMargin = dp(20);
        fp.rightMargin = dp(20);
        root.addView(box, fp);
    }

    private void openController(String url) {
        connectedUrl = url;
        mainFrameErrors = 0;
        root.removeAllViews();
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(8,11,16));
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
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
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                mainFrameErrors++;
                if (mainFrameErrors >= 2) main.postDelayed(() -> startAutoConnect(), 350);
            }
        });
        root.addView(webView, new FrameLayout.LayoutParams(-1,-1));
        Button reconnect = new Button(this);
        reconnect.setText("↻");
        reconnect.setTextSize(18);
        reconnect.setTextColor(Color.WHITE);
        reconnect.setBackgroundColor(Color.argb(175,18,26,36));
        reconnect.setPadding(0,0,0,0);
        reconnect.setOnClickListener(v -> startAutoConnect());
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(44),dp(44));
        rp.gravity = Gravity.BOTTOM | Gravity.END;
        rp.rightMargin = dp(8);
        rp.bottomMargin = dp(10);
        root.addView(reconnect, rp);
        webView.loadUrl(url);
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(color);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.rgb(25,55,94));
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w,h);
        p.setMargins(dp(l),dp(t),dp(r),dp(b)); return p;
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
