package com.armanuser.messenger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int PICK_FILE = 1907;
    private WebView web;
    private ValueCallback<Uri[]> filePicker;

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(232,244,255));
        getWindow().setNavigationBarColor(Color.rgb(218,234,255));
        getWindow().getDecorView().setSystemUiVisibility(0x2000 | 0x10);
        web = new WebView(this);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setBackgroundColor(Color.rgb(232,244,255));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request.getUrl().toString().startsWith("file:///android_asset/")) return false;
                Toast.makeText(MainActivity.this,"Harici bağlantılar bu demoda kapalı",Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePicker != null) filePicker.onReceiveValue(null);
                filePicker = cb;
                try {
                    Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                    pick.setType("image/*");
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(pick,"Fotoğraf seç"), PICK_FILE);
                    return true;
                } catch (Exception ex) {
                    filePicker = null;
                    cb.onReceiveValue(null);
                    return false;
                }
            }
        });
        web.addJavascriptInterface(new ThemeBridge(),"ArmanTheme");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }
    public class ThemeBridge {
        @JavascriptInterface public void setDarkMode(boolean dark) {
            runOnUiThread(() -> {
                getWindow().setStatusBarColor(dark ? Color.rgb(15,30,53) : Color.rgb(232,244,255));
                getWindow().setNavigationBarColor(dark ? Color.rgb(16,31,55) : Color.rgb(218,234,255));
                getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : (0x2000 | 0x10));
            });
        }
    }
    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code,result,data);
        if(code == PICK_FILE && filePicker != null) {
            Uri[] value = result == RESULT_OK && data != null && data.getData()!=null ? new Uri[]{data.getData()} : null;
            filePicker.onReceiveValue(value);
            filePicker = null;
        }
    }
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if(web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript("window.armanBack ? window.armanBack() : false", v -> {
            if(!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }
    @Override protected void onDestroy() {
        if(filePicker != null) { filePicker.onReceiveValue(null); filePicker=null; }
        if(web != null) { web.destroy(); web=null; }
        super.onDestroy();
    }
}
