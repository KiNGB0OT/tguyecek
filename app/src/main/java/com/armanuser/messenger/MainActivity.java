package com.armanuser.messenger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public final class MainActivity extends Activity {
    private WebView web;
    private TdEngine telegram;
    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(219,236,255));
        getWindow().setNavigationBarColor(Color.rgb(220,236,250));
        getWindow().getDecorView().setSystemUiVisibility(0x2000 | 0x10);
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
        setContentView(web);
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
        @JavascriptInterface public void theme(boolean dark){runOnUiThread(()->{
            getWindow().setStatusBarColor(dark?Color.rgb(13,25,47):Color.rgb(219,236,255));
            getWindow().setNavigationBarColor(dark?Color.rgb(12,25,46):Color.rgb(220,236,250));
            getWindow().getDecorView().setSystemUiVisibility(dark?0:0x2000 | 0x10);
        });}
    }
    @SuppressWarnings("deprecation") @Override public void onBackPressed(){
        web.evaluateJavascript("window.armanBack ? window.armanBack() : false",v->{if(!"true".equals(v))super.onBackPressed();});
    }
    @Override public void onDestroy(){if(telegram!=null)telegram.close();if(web!=null)web.destroy();super.onDestroy();}
}