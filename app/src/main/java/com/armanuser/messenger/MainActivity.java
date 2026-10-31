package com.armanuser.messenger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Build;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public final class MainActivity extends Activity {
    private WebView web;
    private TdEngine telegram;
    private FrameLayout frame;
    private boolean darkTheme = false;
    private static final int LIGHT_BARS = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    private static final int EDGE_LAYOUT = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
    private void syncSystemBars() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 28) getWindow().setNavigationBarDividerColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(EDGE_LAYOUT | (darkTheme ? 0 : LIGHT_BARS));
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        if(frame!=null) frame.setBackgroundColor(darkTheme ? Color.rgb(15,28,52) : Color.rgb(224,240,255));
    }
    private void installInsets(FrameLayout host) {
        host.setOnApplyWindowInsetsListener((view, insets) -> {
            int left=0, top=0, right=0, bottom=0;
            if(Build.VERSION.SDK_INT>=30) {
                Insets bars=insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets keyboard=insets.getInsets(WindowInsets.Type.ime());
                left=bars.left; right=bars.right; top=bars.top;
                bottom=Math.max(bars.bottom,keyboard.bottom);
            } else {
                left=insets.getSystemWindowInsetLeft();
                right=insets.getSystemWindowInsetRight();
                top=insets.getSystemWindowInsetTop();
                bottom=insets.getSystemWindowInsetBottom();
            }
            if(view.getPaddingLeft()!=left || view.getPaddingTop()!=top || view.getPaddingRight()!=right || view.getPaddingBottom()!=bottom)
                view.setPadding(left,top,right,bottom);
            return insets;
        });
        host.requestApplyInsets();
    }
    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        syncSystemBars();
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(225,240,255));
        web.setVerticalScrollBarEnabled(false);
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        telegram = new TdEngine(this,web);
        web.addJavascriptInterface(new Bridge(),"TelegramNative");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req){return true;}
            @Override public void onPageFinished(WebView v,String url){telegram.onWebReady();}
        });
        frame = new FrameLayout(this);
        frame.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        installInsets(frame);
        syncSystemBars();
        web.loadUrl("file:///android_asset/telegram.html");
    }
    public final class Bridge {
        @JavascriptInterface public void ready(){telegram.onWebReady();}
        @JavascriptInterface public void saveApi(String id,String hash){telegram.saveApi(id,hash);}
        @JavascriptInterface public void submit(String mode,String value,String extra){telegram.submit(mode,value,extra);}
        @JavascriptInterface public void chats(){telegram.listChats();}
        @JavascriptInterface public void history(String id){telegram.getHistory(id);}
        @JavascriptInterface public void send(String id,String message){telegram.sendText(id,message);}
        @JavascriptInterface public void logout(){telegram.logout();}
        @JavascriptInterface public void theme(boolean dark){runOnUiThread(()->{ darkTheme=dark; syncSystemBars(); });}
    }
    @SuppressWarnings("deprecation") @Override public void onBackPressed(){
        web.evaluateJavascript("window.armanBack ? window.armanBack() : false",v->{if(!"true".equals(v))super.onBackPressed();});
    }
    @Override public void onDestroy(){if(telegram!=null)telegram.close();if(web!=null)web.destroy();super.onDestroy();}
}