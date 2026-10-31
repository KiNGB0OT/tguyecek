package com.armanuser.messenger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.content.Intent;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.widget.Toast;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.FilterInputStream;
import java.util.HashMap;
import java.util.Map;
import android.webkit.WebResourceResponse;
import java.util.UUID;
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
    private static final int PICK_MEDIA=8051;
    private String pendingChat="",pendingKind="";
    private int pendingTtl=0;
    private void chooseMedia(String chatId,String kind,int ttlSeconds) {
        runOnUiThread(()->{
            if(!"photo".equals(kind)&&!"video".equals(kind)&&!"gif".equals(kind)&&
               !"sticker".equals(kind)&&!"file".equals(kind))return;
            pendingChat=chatId;pendingKind=kind;pendingTtl=ttlSeconds;
            try {
                Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                picker.addCategory(Intent.CATEGORY_OPENABLE);
                picker.setType("*/*");
                String[] accepted;
                switch(kind){
                    case "photo":accepted=new String[]{"image/jpeg","image/png","image/webp","image/heic"};break;
                    case "video":accepted=new String[]{"video/mp4","video/quicktime","video/*"};break;
                    case "gif":accepted=new String[]{"image/gif","video/mp4"};break;
                    case "sticker":accepted=new String[]{"image/webp","video/webm"};break;
                    default:accepted=new String[]{"*/*"};break;
                }
                picker.putExtra(Intent.EXTRA_MIME_TYPES,accepted);
                startActivityForResult(picker,PICK_MEDIA);
            }catch(Exception ex){notifyUi("Dosya seçimi açılamadı");}
        });
    }
    private void notifyUi(String text){runOnUiThread(()->Toast.makeText(this,text,Toast.LENGTH_LONG).show());}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=PICK_MEDIA || resultCode!=RESULT_OK || data==null || data.getData()==null)return;
        final Uri uri=data.getData();
        final String chat=pendingChat,kind=pendingKind;
        final int ttl=pendingTtl;
        new Thread(()->{
            File output=null;
            try{
                String mime=getContentResolver().getType(uri);
                String ext=mime==null?null:MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                if(ext==null||!ext.matches("[a-z0-9]{1,8}")) {
                    ext="photo".equals(kind)?"jpg":"video".equals(kind)?"mp4":
                        "gif".equals(kind)?"gif":"sticker".equals(kind)?"webp":"bin";
                }
                File dir=new File(getCacheDir(),"arman-uploads");
                if(!dir.exists()&&!dir.mkdirs())throw new java.io.IOException("Depolama klasörü oluşturulamadı");
                output=new File(dir,UUID.randomUUID()+"."+ext);
                long written=0;
                try(InputStream input=getContentResolver().openInputStream(uri);
                    FileOutputStream out=new FileOutputStream(output)){
                    if(input==null)throw new java.io.IOException("Dosya açılamadı");
                    byte[] buf=new byte[65536];int n;
                    while((n=input.read(buf))!=-1){
                        written+=n;
                        if(written>90L*1024L*1024L)throw new java.io.IOException("90 MB sınırı aşıldı");
                        out.write(buf,0,n);
                    }
                }
                if(written==0)throw new java.io.IOException("Dosya boş");
                telegram.sendMedia(chat,output.getAbsolutePath(),kind,ttl);
            }catch(Exception ex){
                if(output!=null)output.delete();
                notifyUi("Dosya gönderilemedi: "+ex.getMessage());
            }
        },"ArmanUserMediaPicker").start();
    }
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
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest req){
                Uri uri=req.getUrl();
                if(!"https".equals(uri.getScheme())||!"media.armanuser.invalid".equals(uri.getHost()))
                    return super.shouldInterceptRequest(v,req);
                if(!"/media".equals(uri.getPathSegments().size()>0?"/"+uri.getPathSegments().get(0):""))return notFound();
                try{
                    if(uri.getPathSegments().size()!=2)return notFound();
                    String idString=uri.getLastPathSegment();
                    if(idString==null||!idString.matches("[0-9]{1,10}"))return notFound();
                    int id=Integer.parseInt(idString);
                    File file=telegram.safeMediaFile(id);
                    if(file==null)return notFound();
                    long length=file.length(),start=0,end=length-1;
                    if(length<=0)return notFound();
                    String range=req.getRequestHeaders().get("Range");
                    boolean partial=false;
                    if(range!=null && range.startsWith("bytes=")){
                        String[] tokens=range.substring(6).split("-",2);
                        if(tokens.length==2 && !tokens[0].isEmpty()){
                            start=Long.parseLong(tokens[0]);
                            if(!tokens[1].isEmpty())end=Long.parseLong(tokens[1]);
                            partial=true;
                        }
                    }
                    if(start<0||start>=length)return notFound();
                    end=Math.min(end,length-1);
                    if(end<start)return notFound();
                    FileInputStream input=new FileInputStream(file);
                    skipFully(input,start);
                    long bodyLength=end-start+1;
                    Map<String,String> headers=new HashMap<>();
                    headers.put("Content-Length",String.valueOf(bodyLength));
                    headers.put("Accept-Ranges","bytes");
                    headers.put("Access-Control-Allow-Origin","*");
                    headers.put("Cache-Control","private, no-store");
                    headers.put("X-Content-Type-Options","nosniff");
                    if(partial)headers.put("Content-Range","bytes "+start+"-"+end+"/"+length);
                    return new WebResourceResponse(telegram.mediaMime(id),null,partial?206:200,
                        partial?"Partial Content":"OK",headers,new LimitStream(input,bodyLength));
                }catch(Exception ignored){return notFound();}
            }
        });
        frame = new FrameLayout(this);
        frame.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        installInsets(frame);
        syncSystemBars();
        web.loadUrl("file:///android_asset/telegram.html");
    }
    private static final class LimitStream extends FilterInputStream {
        private long remaining;
        LimitStream(InputStream input,long n){super(input);remaining=n;}
        @Override public int read() throws IOException{
            if(remaining<=0)return -1;
            int v=super.read();if(v!=-1)remaining--;return v;
        }
        @Override public int read(byte[] b,int off,int len) throws IOException{
            if(remaining<=0)return -1;
            int count=super.read(b,off,(int)Math.min(len,remaining));
            if(count>0)remaining-=count;
            return count;
        }
    }
    private static void skipFully(InputStream input,long amount) throws IOException {
        long remaining=amount;
        while(remaining>0){
            long skipped=input.skip(remaining);
            if(skipped<=0)throw new IOException("Media seek failed");
            remaining-=skipped;
        }
    }
    private static WebResourceResponse notFound(){
        return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",
            java.util.Collections.singletonMap("Cache-Control","no-store"),
            new java.io.ByteArrayInputStream(new byte[0]));
    }
    public final class Bridge {
        @JavascriptInterface public void ready(){telegram.onWebReady();}
        @JavascriptInterface public void saveApi(String id,String hash){telegram.saveApi(id,hash);}
        @JavascriptInterface public void countries(){telegram.requestCountries();}
        @JavascriptInterface public void submit(String mode,String value,String extra){telegram.submit(mode,value,extra);}
        @JavascriptInterface public void chats(){telegram.listChats();}
        @JavascriptInterface public void checkConnection(){telegram.checkConnection();}
        @JavascriptInterface public void history(String id){telegram.getHistory(id);}
        @JavascriptInterface public void historyOlder(String id,String oldest){telegram.getHistory(id,oldest);}
        @JavascriptInterface public void openChat(String id){telegram.openChat(id);}
        @JavascriptInterface public void closeChat(String id){telegram.closeChat(id);}
        @JavascriptInterface public void readVisible(String id,String idsJson){
            try{
                org.json.JSONArray a=new org.json.JSONArray(idsJson);
                String[] ids=new String[Math.min(100,a.length())];
                for(int i=0;i<ids.length;i++)ids[i]=a.optString(i);
                telegram.readVisible(id,ids);
            }catch(Exception ignored){}
        }
        @JavascriptInterface public void pickMedia(String id,String kind,int ttl){chooseMedia(id,kind,ttl);}
        @JavascriptInterface public void mediaPalette(String kind){telegram.loadMediaPalette(kind);}
        @JavascriptInterface public void requestMedia(int fileId){telegram.requestMedia(fileId);}
        @JavascriptInterface public void sendSavedMedia(String id,String kind,int fileId){telegram.sendSavedMedia(id,kind,fileId);}
        @JavascriptInterface public void send(String id,String message){telegram.sendText(id,message);}
        @JavascriptInterface public void logout(){telegram.logout();}
        @JavascriptInterface public void theme(boolean dark){runOnUiThread(()->{ darkTheme=dark; syncSystemBars(); });}
    }
    @SuppressWarnings("deprecation") @Override public void onBackPressed(){
        web.evaluateJavascript("window.armanBack ? window.armanBack() : false",v->{if(!"true".equals(v))super.onBackPressed();});
    }
    @Override public void onDestroy(){if(telegram!=null)telegram.close();if(web!=null)web.destroy();super.onDestroy();}
}