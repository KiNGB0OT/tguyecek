# ArmanUser Messenger v0.5 — Liquid Glass Categories

Android Telegram client beta using TDLib with original iOS-inspired Liquid Glass design.

## Implemented
- Phone, verification code, 2-step password and other TDLib authentication states
- Real Telegram private dialogs, group chats, channels and bot chats separated by chat type
- Local favorites and local pinned chats via long press / swipe, saved in WebView localStorage
- Unread mentions in group chats, based on TDLib unreadMentionCount (not a lifetime mention history)
- Telegram real chat photos via TDLib DownloadFile, with initials as fallback
- Real text conversation history and sending; theme, search, unread and pinned filters
- Mobile-friendly horizontal scroll category row and bottom navigation

## Not implemented
- Bot-managed groups: requires private, authenticated ARMAN USERBOT PRO mobile API
- ARMAN AI, media send/playback, full Telegram calls, notifications and official-grade client feature parity
- No physical-device login test; Gradle successful means builds, not functionality validation

## Build
GitHub Actions workflow `ArmanUser Messenger v0.5 APK`. Install debug APK extracted from artifacts. The API ID is prefilled from user-supplied project settings; API Hash is never committed and must be entered once locally.

## Security
Telegram sessions remain stored by TDLib in the app's private data. Never enter login codes or 2FA passwords anywhere other than your own local app. Avoid publicly distributing debug APK as production software.
