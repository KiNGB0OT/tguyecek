package com.armanuser.messenger;

import android.app.Activity;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.WebView;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.nio.file.Files;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class TdEngine {
    private final Activity activity;
    private final WebView web;
    private Client client;
    private volatile boolean ready;
    private volatile boolean awaitingParameters;
    private final Object loginGuard=new Object();
    private volatile boolean phoneRequestPending=false;
    private volatile String currentAuthStage="loading";
    private volatile boolean countriesLoaded=false;
    private volatile int apiId;
    private volatile String apiHash = "";
    private final Map<Long, TdApi.Chat> chats = Collections.synchronizedMap(new HashMap<>());
    private final Map<Long, Boolean> bots = Collections.synchronizedMap(new HashMap<>());
    private final Map<Long, TdApi.User> cachedSenders = Collections.synchronizedMap(new HashMap<>());
    private final Set<Long> pendingSenders = Collections.synchronizedSet(new HashSet<>());
    private final Set<Long> pendingSenderChats = Collections.synchronizedSet(new HashSet<>());
    private final Set<Integer> senderPhotoDownloads = Collections.synchronizedSet(new HashSet<>());
    private final Map<Long, Integer> senderPhotoCache = Collections.synchronizedMap(new HashMap<>());
    private final Set<Integer> avatarRequests = Collections.synchronizedSet(new HashSet<>());
    private final Map<Long,Integer> currentAvatarIds=Collections.synchronizedMap(new HashMap<>());
    private static final String KEY_ALIAS = "ArmanUserTelegramApiCredentials";

    TdEngine(Activity activity, WebView web) {
        this.activity = activity; this.web = web;
        try {
            JSONObject saved = readCredentials();
            if (saved != null) { apiId = saved.optInt("id"); apiHash = saved.optString("hash", ""); }
            else {
                // Optional compile-time credentials are supplied only by the private APK build.
                // Public source builds continue using the manual one-time API setup.
                try {
                    Class<?> c = Class.forName("com.armanuser.messenger.TelegramApiConfig");
                    apiId = c.getField("API_ID").getInt(null);
                    apiHash = (String)c.getField("API_HASH").get(null);
                } catch (ReflectiveOperationException ignored) { }
            }
        } catch (Exception ignored) { }
        try {
            client = Client.create(this::onUpdate, null, null);
            emit("status", "TDLib başlatıldı");
        } catch (Throwable error) { emit("error", "Telegram kitaplığı açılamadı: " + error.getMessage()); }
    }

    private void send(TdApi.Function<?> f) {
        if (client == null) { emit("error", "Telegram motoru başlatılamadı"); return; }
        client.send(f, result -> {
            if (result instanceof TdApi.Error) emit("error", ((TdApi.Error)result).message);
        });
    }
    private void send(TdApi.Function<?> f, Client.ResultHandler cb) {
        if (client == null) return;
        client.send(f, result -> {
            if (result instanceof TdApi.Error) emit("error", ((TdApi.Error)result).message);
            cb.onResult(result);
        });
    }

    private void emit(String name, String value) {
        JSONObject data = new JSONObject();
        try { data.put("type", name); data.put("value", value); } catch(Exception ignored){}
        emit(data);
    }
    private void emit(JSONObject data) {
        activity.runOnUiThread(() -> web.evaluateJavascript("window.onTdEvent&&window.onTdEvent(" + data.toString() + ")", null));
    }
    void onWebReady() {
        if (ready) { emit("auth", "ready"); listChats(); }
        else if (awaitingParameters) emit("auth", "api");
        else emit("auth", currentAuthStage);
        loadCountries();
    }
    private void loadCountries(){
        if(client==null||countriesLoaded)return;
        countriesLoaded=true;
        client.send(new TdApi.GetCountries(),r->{
            if(!(r instanceof TdApi.Countries)) {countriesLoaded=false;return;}
            JSONArray a=new JSONArray();
            for(TdApi.CountryInfo c:((TdApi.Countries)r).countries) {
                if(c.isHidden||c.callingCodes==null||c.callingCodes.length==0)continue;
                for(String code:c.callingCodes) {
                    try{a.put(new JSONObject().put("iso",c.countryCode)
                         .put("name",c.englishName==null?c.name:c.englishName)
                         .put("dial","+"+code));}catch(Exception ignored){}
                }
            }
            try{emit(new JSONObject().put("type","countries").put("items",a));}
            catch(Exception ignored){}
        });
    }
    private void onUpdate(TdApi.Object o) {
        if (o instanceof TdApi.UpdateAuthorizationState) {
            onAuth(((TdApi.UpdateAuthorizationState)o).authorizationState);
        } else if (o instanceof TdApi.UpdateNewChat) {
            TdApi.Chat chat = ((TdApi.UpdateNewChat)o).chat;
            chats.put(chat.id, chat); loadUserType(chat); loadAvatar(chat); publishSenderName("c:"+chat.id,chat.title); publishChats();
        } else if (o instanceof TdApi.UpdateChatLastMessage) {
            TdApi.UpdateChatLastMessage u=(TdApi.UpdateChatLastMessage)o;
            TdApi.Chat chat = chats.get(u.chatId);
            if(chat != null){ chat.lastMessage=u.lastMessage; publishChats(); }
        } else if (o instanceof TdApi.UpdateChatTitle) {
            TdApi.UpdateChatTitle u=(TdApi.UpdateChatTitle)o;
            TdApi.Chat chat = chats.get(u.chatId);
            if(chat != null){ chat.title=u.title; publishSenderName("c:"+u.chatId,u.title); publishChats(); }
        } else if (o instanceof TdApi.UpdateChatPhoto) {
            TdApi.UpdateChatPhoto u=(TdApi.UpdateChatPhoto)o;
            TdApi.Chat c=chats.get(u.chatId);
            if(c!=null) { c.photo=u.photo;loadAvatar(c);publishChats(); }
        } else if (o instanceof TdApi.UpdateChatUnreadMentionCount) {
            TdApi.UpdateChatUnreadMentionCount u=(TdApi.UpdateChatUnreadMentionCount)o;
            TdApi.Chat chat=chats.get(u.chatId);
            if(chat!=null){ chat.unreadMentionCount=u.unreadMentionCount; publishChats(); }
        } else if (o instanceof TdApi.UpdateChatPosition) {
            TdApi.UpdateChatPosition u=(TdApi.UpdateChatPosition)o;
            send(new TdApi.GetChat(u.chatId), result -> {
                if(result instanceof TdApi.Chat) { chats.put(u.chatId,(TdApi.Chat)result); publishChats(); }
            });
        } else if (o instanceof TdApi.UpdateUser) {
            TdApi.User user=((TdApi.UpdateUser)o).user;
            bots.put(user.id,user.type instanceof TdApi.UserTypeBot);
            cachedSenders.put(user.id,user);
            publishUser(user);
            publishChats();
        } else if (o instanceof TdApi.UpdateChatReadInbox) {
            TdApi.UpdateChatReadInbox u=(TdApi.UpdateChatReadInbox)o;
            TdApi.Chat chat = chats.get(u.chatId);
            if(chat != null){ chat.unreadCount=u.unreadCount; publishChats(); }
        } else if (o instanceof TdApi.UpdateNewMessage) {
            TdApi.Message msg=((TdApi.UpdateNewMessage)o).message;
            JSONObject obj = messageJson(msg);
            try { obj.put("type", "message"); } catch(Exception ignored){}
            emit(obj);
        } else if (o instanceof TdApi.UpdateConnectionState) {
            emit("status", "Bağlantı: " + ((TdApi.UpdateConnectionState)o).state.getClass().getSimpleName().replace("ConnectionState", ""));
        }
    }
    private void onAuth(TdApi.AuthorizationState state) {
        // TDLib authorization state transitions, not timers, release the pending request.
        synchronized(loginGuard) { phoneRequestPending=false; }
        if(state instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            currentAuthStage="phone";
            loadCountries();
        }else if(state instanceof TdApi.AuthorizationStateWaitCode)currentAuthStage="code";
        else if(state instanceof TdApi.AuthorizationStateWaitPassword)currentAuthStage="password";
        else if(state instanceof TdApi.AuthorizationStateWaitEmailAddress)currentAuthStage="email";
        else if(state instanceof TdApi.AuthorizationStateWaitEmailCode)currentAuthStage="emailcode";
        else if(state instanceof TdApi.AuthorizationStateWaitRegistration)currentAuthStage="register";
        else if(state instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation)currentAuthStage="otherdevice";
        else if(state instanceof TdApi.AuthorizationStateReady)currentAuthStage="ready";
        else if(state instanceof TdApi.AuthorizationStateWaitTdlibParameters)currentAuthStage="api";
        else currentAuthStage="loading";
        if(state instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
            awaitingParameters=true;
            if(apiId>0 && apiHash.length()>5) configure();
            else emit("auth", "api");
        } else if(state instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            emit("auth", "phone");
        } else if(state instanceof TdApi.AuthorizationStateWaitCode) {
            emit("auth", "code");
        } else if(state instanceof TdApi.AuthorizationStateWaitPassword) {
            emit("auth", "password");
        } else if(state instanceof TdApi.AuthorizationStateWaitEmailAddress) {
            emit("auth", "email");
        } else if(state instanceof TdApi.AuthorizationStateWaitEmailCode) {
            emit("auth", "emailcode");
        } else if(state instanceof TdApi.AuthorizationStateWaitRegistration) {
            emit("auth", "register");
        } else if(state instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation) {
            emit("auth", "otherdevice");
            emit("status", ((TdApi.AuthorizationStateWaitOtherDeviceConfirmation)state).link);
        } else if(state instanceof TdApi.AuthorizationStateReady) {
            ready=true; emit("auth", "ready"); listChats();
            send(new TdApi.GetMe(), result -> {
                if(result instanceof TdApi.User) emit("me", ((TdApi.User)result).firstName);
            });
        } else if(state instanceof TdApi.AuthorizationStateClosing || state instanceof TdApi.AuthorizationStateLoggingOut || state instanceof TdApi.AuthorizationStateClosed) {
            ready=false; emit("auth", "loading");
        }
    }
    void saveApi(String id, String hash) {
        try {
            int n=Integer.parseInt(id.trim());
            if(n<=0 || !hash.trim().matches("(?i)[0-9a-f]{32}")) { emit("error", "API ID veya API Hash geçersiz"); return; }
            apiId=n; apiHash=hash.trim();
            JSONObject credentials=new JSONObject().put("id", n).put("hash", apiHash);
            persistCredentials(credentials);
            if(awaitingParameters) configure();
        } catch(Exception e) { emit("error", "API bilgileri kaydedilemedi: " + e.getMessage()); }
    }
    private void configure() {
        awaitingParameters = false;
        TdApi.SetTdlibParameters p = new TdApi.SetTdlibParameters();
        p.databaseDirectory = activity.getFilesDir().getAbsolutePath() + "/tdlib";
        p.filesDirectory = activity.getFilesDir().getAbsolutePath() + "/tdlib-files";
        p.useFileDatabase = true;
        p.useChatInfoDatabase = true;
        p.useMessageDatabase = true;
        p.useSecretChats = true;
        p.apiId = apiId;
        p.apiHash = apiHash;
        p.systemLanguageCode = "tr";
        p.deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
        p.systemVersion = Build.VERSION.RELEASE;
        p.applicationVersion = "0.11.0";
        emit("auth", "loading");
        send(p, result -> {
            if(result instanceof TdApi.Error) { awaitingParameters=true; emit("auth", "api"); }
        });
    }
    void submit(String mode, String value, String extra) {
        String v=value==null?"":value.trim();
        if(v.isEmpty()) { emit("error", "Alan boş bırakılamaz"); return; }
        switch(mode) {
            case "phone": {
                String number=v.replaceAll("[\\s()\\-]", "");
                if(!number.matches("\\+[1-9][0-9]{6,14}")){
                    emit("error","Numarayı +ülke kodu ile gir (+994..., +90..., +1...).");return;
                }
                synchronized(loginGuard){
                    if(phoneRequestPending){emit("error","Önceki doğrulama isteği henüz tamamlanmadı.");return;}
                    if(!"phone".equals(currentAuthStage)){emit("error","Telegram henüz telefon numarası istemiyor.");return;}
                    phoneRequestPending=true;
                }
                TdApi.SetAuthenticationPhoneNumber request=new TdApi.SetAuthenticationPhoneNumber();
                request.phoneNumber=number;
                if(client==null){synchronized(loginGuard){phoneRequestPending=false;}emit("error","Telegram bağlantısı hazır değil");return;}
                client.send(request,result -> {
                    if(result instanceof TdApi.Error){
                        synchronized(loginGuard){phoneRequestPending=false;}
                        String problem=((TdApi.Error)result).message;
                        if(problem.contains("Another authorization query")){
                            emit("error","Önceki giriş isteği sürüyor. Tekrar basmadan önce Telegram'ın yanıtını bekle.");
                        }else emit("error",problem);
                        emit("authRetry","phone");
                    }
                    // Successful requests remain locked until TDLib changes the auth state.
                });
                break;
            }
            case "code": send(new TdApi.CheckAuthenticationCode(v)); break;
            case "password": send(new TdApi.CheckAuthenticationPassword(value)); break;
            case "email": send(new TdApi.SetAuthenticationEmailAddress(v)); break;
            case "emailcode": send(new TdApi.CheckAuthenticationEmailCode(new TdApi.EmailAddressAuthenticationCode(v))); break;
            case "register": send(new TdApi.RegisterUser(v, extra==null?"":extra, false)); break;
        }
    }
    void listChats() {
        if(!ready) return;
        send(new TdApi.LoadChats(new TdApi.ChatListMain(), 300), result -> { publishChats(); });
        publishChats();
    }
    private void loadUserType(TdApi.Chat chat) {
        if(!(chat.type instanceof TdApi.ChatTypePrivate)) return;
        long userId=((TdApi.ChatTypePrivate)chat.type).userId;
        if(bots.containsKey(userId)) return;
        send(new TdApi.GetUser(userId), result -> {
            if(result instanceof TdApi.User) {
                bots.put(userId,((TdApi.User)result).type instanceof TdApi.UserTypeBot);
                publishChats();
            }
        });
    }
    private String kindOf(TdApi.Chat chat) {
        if(chat.type instanceof TdApi.ChatTypeBasicGroup) return "group";
        if(chat.type instanceof TdApi.ChatTypeSupergroup)
            return ((TdApi.ChatTypeSupergroup)chat.type).isChannel ? "channel" : "group";
        if(chat.type instanceof TdApi.ChatTypePrivate) {
            long uid=((TdApi.ChatTypePrivate)chat.type).userId;
            return Boolean.TRUE.equals(bots.get(uid)) ? "bot" : "private";
        }
        return "private";
    }
    private boolean isPinned(TdApi.Chat chat) {
        if(chat.positions==null) return false;
        for(TdApi.ChatPosition position:chat.positions) {
            if(position!=null && position.list instanceof TdApi.ChatListMain && position.isPinned) return true;
        }
        return false;
    }
    private void loadAvatar(TdApi.Chat chat) {
        if(chat.photo==null || chat.photo.small==null || client==null) return;
        final int fileId=chat.photo.small.id;
        if(fileId<=0 || (currentAvatarIds.get(chat.id)!=null && currentAvatarIds.get(chat.id)==fileId))return;
        if(!avatarRequests.add(fileId))return;
        final long chatId=chat.id;
        client.send(new TdApi.DownloadFile(fileId,1,0,0,true),result -> {
            avatarRequests.remove(fileId);
            if(!(result instanceof TdApi.File))return;
            TdApi.File file=(TdApi.File)result;
            if(file.local==null || !file.local.isDownloadingCompleted || file.local.path==null)return;
            try {
                java.io.File localFile=new java.io.File(file.local.path);
                if(!localFile.isFile() || localFile.length()>150000 || localFile.length()<10)return;
                String encoded=Base64.encodeToString(Files.readAllBytes(localFile.toPath()),Base64.NO_WRAP);
                currentAvatarIds.put(chatId,fileId);
                JSONObject avatar=new JSONObject().put("type","avatar").put("chat",String.valueOf(chatId)).put("data","data:image/jpeg;base64,"+encoded);
                emit(avatar);
            } catch(Exception ignored) { }
        });
    }
    private void publishChats() {
        if(!ready) return;
        ArrayList<TdApi.Chat> snapshot;
        synchronized(chats){snapshot = new ArrayList<>(chats.values());}
        snapshot.sort((a,b) -> Long.compare(b.lastMessage==null?0:b.lastMessage.date, a.lastMessage==null?0:a.lastMessage.date));
        JSONArray arr=new JSONArray(); int count=0;
        for(TdApi.Chat c:snapshot) {
            if(count++>=300)break;
            loadAvatar(c);
            try {JSONObject item=new JSONObject(); item.put("id", String.valueOf(c.id)); item.put("title", c.title); item.put("last", c.lastMessage==null?"":messageText(c.lastMessage)); item.put("date", c.lastMessage==null?0:c.lastMessage.date); item.put("unread", c.unreadCount); item.put("mentions",c.unreadMentionCount); item.put("kind",kindOf(c)); item.put("pinned",isPinned(c)); item.put("markedUnread",c.isMarkedAsUnread); item.put("muted",c.notificationSettings!=null && c.notificationSettings.muteFor>0); arr.put(item);}catch(Exception ignored){}
        }
        try { emit(new JSONObject().put("type","chats").put("items",arr)); }catch(Exception ignored){}
    }
    private String messageText(TdApi.Message m) {
        if(m==null) return "";
        if(m.content instanceof TdApi.MessageText) return ((TdApi.MessageText)m.content).text.text;
        if(m.content instanceof TdApi.MessagePhoto) return "📷 Fotoğraf";
        if(m.content instanceof TdApi.MessageAnimation) return "🎞 GIF";
        if(m.content instanceof TdApi.MessageSticker) return "🎨 Çıkartma";
        if(m.content instanceof TdApi.MessageVideo) return "▶ Video";
        if(m.content instanceof TdApi.MessageVoiceNote) return "🎙 Sesli mesaj";
        if(m.content instanceof TdApi.MessageDocument) return "📎 Dosya";
        return "Mesaj";
    }
    private void publishSenderName(String key, String displayName) {
        try { emit(new JSONObject().put("type","sender").put("key",key).put("name",displayName)); }
        catch(Exception ignored){}
    }
    private String userName(TdApi.User user) {
        if(user==null) return "Katılımcı";
        String first=user.firstName==null?"":user.firstName.trim();
        String last=user.lastName==null?"":user.lastName.trim();
        String label=(first+" "+last).trim();
        return label.isEmpty()?"Katılımcı":label;
    }
    private void publishUser(TdApi.User user) {
        publishSenderName("u:"+user.id,userName(user));
        if(user.profilePhoto==null || user.profilePhoto.small==null || client==null) return;
        final long uid=user.id;
        final int fileId=user.profilePhoto.small.id;
        Integer old=senderPhotoCache.get(uid);
        if(fileId<=0 || (old!=null && old==fileId) || !senderPhotoDownloads.add(fileId)) return;
        client.send(new TdApi.DownloadFile(fileId,1,0,0,true),result -> {
            senderPhotoDownloads.remove(fileId);
            if(!(result instanceof TdApi.File))return;
            TdApi.File file=(TdApi.File)result;
            if(file.local==null || !file.local.isDownloadingCompleted || file.local.path==null)return;
            try {
                java.io.File local=new java.io.File(file.local.path);
                if(!local.isFile()||local.length()>150000||local.length()<10)return;
                String encoded=Base64.encodeToString(Files.readAllBytes(local.toPath()),Base64.NO_WRAP);
                senderPhotoCache.put(uid,fileId);
                emit(new JSONObject().put("type","senderAvatar").put("key","u:"+uid)
                    .put("data","data:image/jpeg;base64,"+encoded));
            }catch(Exception ignored){}
        });
    }
    private String resolveSender(TdApi.MessageSender sender, JSONObject json) throws Exception {
        if(sender instanceof TdApi.MessageSenderUser) {
            long uid=((TdApi.MessageSenderUser)sender).userId;
            String key="u:"+uid;
            json.put("senderKey",key);
            TdApi.User user=cachedSenders.get(uid);
            if(user!=null) return userName(user);
            if(pendingSenders.add(uid)) {
                send(new TdApi.GetUser(uid), result -> {
                    pendingSenders.remove(uid);
                    if(result instanceof TdApi.User) {
                        TdApi.User resolved=(TdApi.User)result;
                        cachedSenders.put(uid,resolved);
                        publishUser(resolved);
                    }
                });
            }
            return "Katılımcı";
        }
        if(sender instanceof TdApi.MessageSenderChat) {
            long chatId=((TdApi.MessageSenderChat)sender).chatId;
            json.put("senderKey","c:"+chatId);
            TdApi.Chat known=chats.get(chatId);
            if(known!=null) return known.title;
            if(pendingSenderChats.add(chatId)) {
                send(new TdApi.GetChat(chatId), result -> {
                    pendingSenderChats.remove(chatId);
                    if(result instanceof TdApi.Chat) {
                        TdApi.Chat c=(TdApi.Chat)result;
                        publishSenderName("c:"+chatId,c.title);
                    }
                });
            }
            return "Grup yöneticisi";
        }
        return "Katılımcı";
    }
    private JSONObject messageJson(TdApi.Message m) {
        JSONObject j=new JSONObject();
        try {
            j.put("id",String.valueOf(m.id));
            j.put("chat",String.valueOf(m.chatId));
            j.put("text",messageText(m));
            j.put("date",m.date);
            j.put("out",m.isOutgoing);
            if(m.senderId != null) j.put("sender",resolveSender(m.senderId,j));
        } catch(Exception ignored) {}
        return j;
    }
    void openChat(String chatId) {
        if(!ready)return;
        try {
            long id=Long.parseLong(chatId);
            send(new TdApi.OpenChat(id));
        }catch(Exception ex){emit("error","Sohbet açılamadı");}
    }
    void closeChat(String chatId) {
        try{send(new TdApi.CloseChat(Long.parseLong(chatId)));}catch(Exception ignored){}
    }
    void getHistory(String chatId) { getHistory(chatId,"0"); }
    void getHistory(String chatId,String fromId) {
        if(!ready)return;
        try {
            long chat=Long.parseLong(chatId),from=Long.parseLong(fromId);
            TdApi.GetChatHistory request=new TdApi.GetChatHistory(chat,from,0,80,false);
            send(request,result -> {
                if(result instanceof TdApi.Messages) {
                    TdApi.Message[] ms=((TdApi.Messages)result).messages;
                    JSONArray arr=new JSONArray();
                    for(int i=ms.length-1;i>=0;i--) arr.put(messageJson(ms[i]));
                    String oldest=ms.length>0 ? String.valueOf(ms[ms.length-1].id) : fromId;
                    try {
                        emit(new JSONObject().put("type",from==0?"history":"historyOlder")
                             .put("chat",chatId).put("items",arr).put("oldest",oldest)
                             .put("hasMore",ms.length>0));
                    }catch(Exception ignored){}
                    if(from==0 && ms.length>0) {
                        long[] visible=new long[ms.length];
                        for(int i=0;i<ms.length;i++) visible[i]=ms[i].id;
                        send(new TdApi.ViewMessages(chat,visible,null,false));
                        send(new TdApi.ReadAllChatMentions(chat));
                    }
                }
            });
        } catch(Exception ex) {emit("error","Sohbet geçmişi alınamadı");}
    }
    void readVisible(String chatId,String[] messageIds){
        if(!ready||messageIds==null||messageIds.length==0)return;
        try {
            long[] ids=new long[messageIds.length];
            for(int i=0;i<ids.length;i++)ids[i]=Long.parseLong(messageIds[i]);
            send(new TdApi.ViewMessages(Long.parseLong(chatId),ids,null,false));
        }catch(Exception ignored){}
    }
    void loadMediaPalette(String kind) {
        if(!ready)return;
        if("sticker".equals(kind)) {
            send(new TdApi.GetRecentStickers(false),r -> {
                if(r instanceof TdApi.Stickers) {
                    JSONArray a=new JSONArray();
                    for(TdApi.Sticker sticker:((TdApi.Stickers)r).stickers) {
                        try {
                            JSONObject j=new JSONObject().put("id",sticker.sticker.id)
                                .put("label",sticker.emoji==null?"🎨":sticker.emoji);
                            a.put(j);
                        }catch(Exception ignored){}
                    }
                    try{emit(new JSONObject().put("type","palette").put("kind","sticker").put("items",a));}catch(Exception ignored){}
                }
            });
        }else if("gif".equals(kind)){
            send(new TdApi.GetSavedAnimations(),r -> {
                if(r instanceof TdApi.Animations) {
                    JSONArray a=new JSONArray();
                    for(TdApi.Animation animation:((TdApi.Animations)r).animations) {
                        try{a.put(new JSONObject().put("id",animation.animation.id)
                               .put("label",animation.fileName==null?"GIF":animation.fileName));}
                        catch(Exception ignored){}
                    }
                    try{emit(new JSONObject().put("type","palette").put("kind","gif").put("items",a));}catch(Exception ignored){}
                }
            });
        }
    }
    void sendSavedMedia(String chatId,String kind,int fileId) {
        if(!ready)return;
        try{
            TdApi.InputMessageContent payload;
            TdApi.InputFileId file=new TdApi.InputFileId(fileId);
            if("sticker".equals(kind)) {
                TdApi.InputMessageSticker sticker=new TdApi.InputMessageSticker();
                sticker.sticker=new TdApi.InputSticker(file,null,0,0);
                payload=sticker;
            }else if("gif".equals(kind)){
                TdApi.InputMessageAnimation animation=new TdApi.InputMessageAnimation();
                animation.animation=new TdApi.InputAnimation(file,null,null,0,0,0);
                payload=animation;
            }else{return;}
            TdApi.SendMessage request=new TdApi.SendMessage();
            request.chatId=Long.parseLong(chatId);
            request.inputMessageContent=payload;
            send(request,r->{if(r instanceof TdApi.Message){
                JSONObject j=messageJson((TdApi.Message)r);
                try{j.put("type","message");emit(j);}catch(Exception ignored){}
            }});
        }catch(Exception e){emit("error","Medya gönderilemedi");}
    }
    void sendMedia(String chatId,String filePath,String kind,int ttlSeconds) {
        if(!ready)return;
        try {
            java.io.File file=new java.io.File(filePath);
            if(!file.isFile()||file.length()<1){emit("error","Dosya bulunamadı");return;}
            TdApi.InputFileLocal local=new TdApi.InputFileLocal(file.getAbsolutePath());
            TdApi.InputMessageContent payload;
            if("photo".equals(kind)){
                TdApi.InputMessagePhoto photo=new TdApi.InputMessagePhoto();
                photo.photo=new TdApi.InputPhoto(local,null,null,null,0,0);
                if(ttlSeconds!=0) photo.selfDestructType = ttlSeconds==-1
                    ? new TdApi.MessageSelfDestructTypeImmediately()
                    : new TdApi.MessageSelfDestructTypeTimer(ttlSeconds);
                payload=photo;
            }else if("video".equals(kind)){
                TdApi.InputMessageVideo video=new TdApi.InputMessageVideo();
                video.video=new TdApi.InputVideo(local,null,null,0,null,0,0,0,true);
                if(ttlSeconds!=0) video.selfDestructType = ttlSeconds==-1
                    ? new TdApi.MessageSelfDestructTypeImmediately()
                    : new TdApi.MessageSelfDestructTypeTimer(ttlSeconds);
                payload=video;
            }else if("gif".equals(kind)){
                if(ttlSeconds!=0){emit("error","Süreli medya yalnızca fotoğraf veya videoda desteklenir");return;}
                TdApi.InputMessageAnimation animation=new TdApi.InputMessageAnimation();
                animation.animation=new TdApi.InputAnimation(local,null,null,0,0,0);
                payload=animation;
            }else if("sticker".equals(kind)){
                TdApi.InputMessageSticker sticker=new TdApi.InputMessageSticker();
                sticker.sticker=new TdApi.InputSticker(local,null,0,0);
                payload=sticker;
            }else{
                TdApi.InputMessageDocument document=new TdApi.InputMessageDocument();
                document.document=new TdApi.InputDocument(local,null,false);
                payload=document;
            }
            TdApi.SendMessage req=new TdApi.SendMessage();
            req.chatId=Long.parseLong(chatId);
            req.inputMessageContent=payload;
            send(req,result -> {
                if(result instanceof TdApi.Message){
                    JSONObject j=messageJson((TdApi.Message)result);
                    try{j.put("type","message");emit(j);}catch(Exception ignored){}
                    emit("mediaSent","Dosya gönderiliyor");
                }
            });
        }catch(Exception ex){emit("error","Medya gönderme hatası: "+ex.getMessage());}
    }
    void sendText(String chatId,String text) {
        if(!ready || text==null || text.trim().isEmpty()) return;
        try {
            TdApi.InputMessageText content=new TdApi.InputMessageText();
            content.text=new TdApi.FormattedText(text,null);
            TdApi.SendMessage request=new TdApi.SendMessage();
            request.chatId=Long.parseLong(chatId);
            request.inputMessageContent=content;
            send(request);
        }catch(Exception e){emit("error","Mesaj gönderilemedi: "+e.getMessage());}
    }
    void logout() { if(ready) send(new TdApi.LogOut()); }
    private SecretKey secretKey() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(KEY_ALIAS)){
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry)store.getEntry(KEY_ALIAS,null)).getSecretKey();
    }
    private void persistCredentials(JSONObject object) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,secretKey());
        byte[] enc=cipher.doFinal(object.toString().getBytes(StandardCharsets.UTF_8));
        activity.getSharedPreferences("telegram_local",0).edit()
                .putString("api_iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
                .putString("api_data",Base64.encodeToString(enc,Base64.NO_WRAP)).apply();
    }
    private JSONObject readCredentials() throws Exception {
        String iv=activity.getSharedPreferences("telegram_local",0).getString("api_iv",null);
        String data=activity.getSharedPreferences("telegram_local",0).getString("api_data",null);
        if(iv==null||data==null)return null;
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,secretKey(),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
        return new JSONObject(new String(cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)),StandardCharsets.UTF_8));
    }
    void close() { if(client!=null) client.send(new TdApi.Close(),r -> {}); }
}