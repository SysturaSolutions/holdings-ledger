/*
 * Holdings Ledger — a private, on-device investment tracker.
 * Copyright (C) 2026 Holdings Ledger contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.holdingsledger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String APP_URL = "https://appassets.androidplatform.net/assets/www/index.html";
    private static final int REQ_EXPORT = 1;
    private static final int REQ_IMPORT = 2;
    private static final int GROUND = 0xFF050816;
    private static final int MAX_BODY = 8 * 1024 * 1024;

    /** The only hosts the page may reach, all over HTTPS. */
    private static final Set<String> ALLOWED_HOSTS = new HashSet<>(Arrays.asList(
            "api.coingecko.com",
            "query1.finance.yahoo.com",
            "query2.finance.yahoo.com",
            "api.frankfurter.dev"));

    private WebView web;
    private final ExecutorService net = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private String pendingExport;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Draw edge-to-edge on every Android version and pad for the system bars ourselves,
        // so layout is identical before and after Android 15's enforced edge-to-edge.
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(GROUND);
        web = new WebView(this);
        web.setBackgroundColor(GROUND);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                // Anything else opens in the user's browser.
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient()); // enables confirm() dialogs
        web.addJavascriptInterface(new Bridge(), "HLNative");

        web.loadUrl(APP_URL);
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        web.destroy();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("window.__hl ? window.__hl.back() : false", handled -> {
            if (!"true".equals(handled)) finish();
        });
    }

    private void js(String code) {
        main.post(() -> web.evaluateJavascript(code, null));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
        if (requestCode == REQ_EXPORT) {
            final String json = pendingExport;
            pendingExport = null;
            if (uri == null || json == null) return;
            net.execute(() -> {
                boolean ok = false;
                try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                    if (os != null) { os.write(json.getBytes(StandardCharsets.UTF_8)); ok = true; }
                } catch (Exception ignored) { }
                js("window.__hl.exported(" + ok + ")");
            });
        } else if (requestCode == REQ_IMPORT && uri != null) {
            net.execute(() -> {
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    String text = readAll(is, MAX_BODY);
                    js("window.__hl.imported(" + JSONObject.quote(text) + ")");
                } catch (Exception e) {
                    js("window.__hl.imported('')");
                }
            });
        }
    }

    private static String readAll(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > limit) throw new Exception("too large");
        }
        return out.toString("UTF-8");
    }

    /** Methods callable from the page as window.HLNative.*. */
    final class Bridge {

        @JavascriptInterface
        public String version() {
            return BuildConfig.VERSION_NAME;
        }

        /** Fetch a JSON API over HTTPS; the result is delivered to window.__hl.resolve(id, status, body). */
        @JavascriptInterface
        public void get(final String id, final String url) {
            net.execute(() -> {
                int status = 0;
                String body = "";
                HttpURLConnection c = null;
                try {
                    URL u = new URL(url);
                    if (!"https".equals(u.getProtocol()) || !ALLOWED_HOSTS.contains(u.getHost())) {
                        throw new SecurityException("host not allowed");
                    }
                    c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    c.setInstanceFollowRedirects(false);
                    c.setRequestProperty("Accept", "application/json");
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) HoldingsLedger/" + BuildConfig.VERSION_NAME);
                    status = c.getResponseCode();
                    InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
                    if (in != null) try (InputStream is = in) { body = readAll(is, MAX_BODY); }
                } catch (Exception e) {
                    status = 0;
                } finally {
                    if (c != null) c.disconnect();
                }
                js("window.__hl.resolve(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(body) + ")");
            });
        }

        @JavascriptInterface
        public void exportFile(final String name, final String json) {
            main.post(() -> {
                pendingExport = json;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, name);
                try { startActivityForResult(i, REQ_EXPORT); }
                catch (Exception e) { pendingExport = null; js("window.__hl.exported(false)"); }
            });
        }

        @JavascriptInterface
        public void importFile() {
            main.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
                try { startActivityForResult(i, REQ_IMPORT); } catch (Exception ignored) { }
            });
        }
    }
}
