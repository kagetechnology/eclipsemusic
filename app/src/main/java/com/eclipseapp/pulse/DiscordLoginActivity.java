package com.eclipseapp.pulse;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.*;
import android.widget.*;

/**
 * WebView-based Discord login to capture user token for Rich Presence.
 */
public class DiscordLoginActivity extends Activity {
    static final int BG = 0xFF0A0A0C, W = 0xFFFFFFFF, G2 = 0xFF6B7280;
    private WebView webView;
    private ProgressBar loading;
    private boolean tokenCaptured = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // Top bar
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.HORIZONTAL);
        tb.setGravity(Gravity.CENTER_VERTICAL);
        tb.setPadding(dp(16), dp(44), dp(16), dp(12));
        root.addView(tb);

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_chevron_down);
        back.setColorFilter(W);
        back.setPadding(dp(8), dp(8), dp(8), dp(8));
        back.setOnClickListener(v -> finish());
        tb.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView title = new TextView(this);
        title.setText("Login to Discord");
        title.setTextColor(W);
        title.setTextSize(18);
        title.setPadding(dp(12), 0, 0, 0);
        tb.addView(title);

        // Loading
        loading = new ProgressBar(this);
        loading.setIndeterminate(true);
        root.addView(loading, new LinearLayout.LayoutParams(-2, -2));
        ((LinearLayout.LayoutParams) loading.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;

        // WebView
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setUserAgentString("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36");

        // Clear old data
        CookieManager.getInstance().removeAllCookies(null);
        webView.clearCache(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                loading.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loading.setVisibility(View.GONE);
                // Try to extract token after login completes
                if (url.contains("discord.com/channels") || url.contains("discord.com/app")) {
                    extractToken();
                }
            }
        });

        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        // Load Discord login page
        webView.loadUrl("https://discord.com/login");
    }

    private void extractToken() {
        if (tokenCaptured) return;
        // Extract token from localStorage
        String js = "(function(){" +
                "try{" +
                "var iframe=document.createElement('iframe');" +
                "document.body.appendChild(iframe);" +
                "var token=iframe.contentWindow.localStorage.getItem('token');" +
                "document.body.removeChild(iframe);" +
                "if(token){return token.replace(/\"/g,'');}else{return '';}" +
                "}catch(e){return '';}" +
                "})()";

        webView.evaluateJavascript(js, value -> {
            if (value != null && !value.equals("null") && !value.equals("\"\"") && !value.isEmpty()) {
                String token = value.replace("\"", "").trim();
                if (!token.isEmpty() && token.length() > 20) {
                    tokenCaptured = true;
                    DiscordRPC.get().saveToken(this, token);
                    DiscordRPC.get().connect(this);
                    Toast.makeText(this, "✅ Discord connected!", Toast.LENGTH_LONG).show();
                    finish();
                }
            } else {
                // Retry after 2 seconds
                webView.postDelayed(this::extractToken, 2000);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
