# ArmanUser Messenger: Graph Messenger-level feature roadmap

Goal: match the day-to-day functional breadth of advanced Telegram clients (Graph Messenger is a benchmark) with an original Liquid Glass interface and independently built code. Do not copy proprietary app assets, icons, names or private code.

## P0: Working messaging
- [x] TDLib sign-in, phone/code/password and international numbers
- [x] Separate chats, groups, channels, favorites, unread mentions; actual unread counts
- [x] Actual sent/received text and history pagination
- [x] Message sender identity and avatars in groups
- [x] Android image/gallery/file picker for media uploads (limited beta)
- [x] v0.13: Telegram photo/media metadata, thumbnail downloads and media viewer
- [ ] Device test on Android 16 for photo preview and video playback
- [ ] Full reply, edit, delete, forward, reactions, typing/read receipts and message search
- [ ] Audio, voice note, music, video note and all Telegram sticker/animation formats
- [ ] Secret and self-destructing media viewer respecting expiry and privacy; do not cache them in normal thumbnail route

## P1: Full Telegram client quality
- [ ] Background notifications, long-lived connection and push/FCM integration
- [ ] Android share intent and media download manager/gallery
- [ ] Voice/video calls, stories and group live stream support
- [ ] Multi-account, chat archive, folder rules, detailed chat settings
- [ ] Download/upload state, retry/cancel and media progress indicators
- [ ] Proper native mobile performance and accessibility on low-end Android

## P2: Advanced Graph Messenger-style options
- [ ] Custom folders, recent chats bar, favorites for messages/emojis/stickers
- [ ] Advanced search, timeline/channel feed, privacy lock
- [ ] Scheduled messages, drafts, custom notification profiles
- [ ] Extra group and channel management tools

## P3: ARMAN USERBOT PRO
- [ ] Build private HTTPS mobile API in backend
- [ ] Scoped authentication, signed tokens, per-user and per-group permissions
- [ ] Bot-managed groups tab showing *real* server data
- [ ] UI for tagging, smart moderation, AI chat, jobs and quota state
- [ ] Never embed bot tokens, Telethon sessions, database or backend admin secrets in distributed APK

## Implementation status
Compiled APK is not equivalent to on-device feature verification. The media viewer and download path require real account/device testing before being called production-ready.
