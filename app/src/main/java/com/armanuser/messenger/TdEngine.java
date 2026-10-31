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
        else emit("status", "Telegram bağlantısı hazırlanıyor…");
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
        p.applicationVersion = "0.6.0";
        emit("auth", "loading");
        send(p, result -> {
            if(result instanceof TdApi.Error) { awaitingParameters=true; emit("auth", "api"); }
        });
    }
    void submit(String mode, String value, String extra) {
        String v=value==null?"":value.trim();
        if(v.isEmpty()) { emit("error", "Alan boş bırakılamaz"); return; }
        switch(mode) {
            case "phone": { TdApi.SetAuthenticationPhoneNumber a=new TdApi.SetAuthenticationPhoneNumber(); a.phoneNumber=v; send(a); break; }
            case "code": send(new TdApi.CheckAuthenticationCode(v)); break;
            case "password": send(new TdApi.CheckAuthenticationPassword(value)); break;
            case "email": send(new TdApi.SetAuthenticationEmailAddress(v)); break;
            case "emailcode": send(new TdApi.CheckAuthenticationEmailCode(new TdApi.EmailAddressAuthenticationCode(v))); break;
            case "register": send(new TdApi.RegisterUser(v, extra==null?"":extra, false)); break;
        }
    }
    void listChats() {
        if(!ready) return;
        send(new TdApi.LoadChats(new TdApi.ChatListMain(), 80), result -> { publishChats(); });
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
            if(count++>=80)break;
            loadAvatar(c);
            try {JSONObject item=new JSONObject(); item.put("id", String.valueOf(c.id)); item.put("title", c.title); item.put("last", c.lastMessage==null?"":messageText(c.lastMessage)); item.put("date", c.lastMessage==null?0:c.lastMessage.date); item.put("unread", c.unreadCount); item.put("mentions",c.unreadMentionCount); item.put("kind",kindOf(c)); item.put("pinned",isPinned(c)); item.put("markedUnread",c.isMarkedAsUnread); item.put("muted",c.notificationSettings!=null && c.notificationSettings.muteFor>0); arr.put(item);}catch(Exception ignored){}
        }
        try { emit(new JSONObject().put("type","chats").put("items",arr)); }catch(Exception ignored){}
    }
    private String messageText(TdApi.Message m) {
        if(m==null) return "";
        if(m.content instanceof TdApi.MessageText) return ((TdApi.MessageText)m.content).text.text;
        if(m.content instanceof TdApi.MessagePhoto) return "📷 Fotoğraf";
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
    void getHistory(String chatId) {
        try {
            long id=Long.parseLong(chatId);
            TdApi.GetChatHistory request = new TdApi.GetChatHistory(); request.chatId=id;request.limit=50;
            send(request, result -> {
                if(result instanceof TdApi.Messages) {
                    JSONArray arr=new JSONArray();
                    TdApi.Message[] ms=((TdApi.Messages)result).messages;
                    for(int i=ms.length-1;i>=0;i--) arr.put(messageJson(ms[i]));
                    try {emit(new JSONObject().put("type","history").put("chat",chatId).put("items",arr));}catch(Exception ignored){}
                }
            });
        }catch(NumberFormatException ex){emit("error","Sohbet ID geçersiz");}
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