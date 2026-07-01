# GhostProtocol: Android OS Persistence & Event Interception

> ### Development Note
> *This application was developed locally to research background execution limits on heavily modified Android ROMs (MIUI). It was migrated to this public GitHub repository on March 29, 2026, for academic review.*

## Overview
GhostProtocol is an experimental Android utility designed to test the boundaries of OS-level background execution and asynchronous event handling. 

Modern Android OEMs (specifically Xiaomi/Redmi MIUI) utilize highly aggressive task-killers to terminate background services, making continuous background event listening nearly impossible. This project successfully bypasses these OEM restrictions using a combination of `NotificationListenerService` APIs and a deliberate WindowManager persistence exploit.

## Technical Architecture

### 1. Adversarial Keep-Alive Mechanism (`KeepAliveService.java`)
* **WindowManager Exploit:** To prevent the OS from killing the background service, the app utilizes the `SYSTEM_ALERT_WINDOW` permission to continuously draw a transparent 1x1 pixel overlay (`TYPE_APPLICATION_OVERLAY`) on the screen.
* **Process Elevation:** Because the OS compositor believes the application is actively rendering UI to the user, the Android scheduler elevates the process priority, bypassing OEM battery optimization algorithms and strict sandboxing environments.

### 2. Asynchronous Event Interception (`GhostService.java`)
* Extends `NotificationListenerService` to intercept incoming OS-level notification payloads asynchronously.
* Capable of reading incoming messaging events and programmatically executing automated actions (like auto-replying) without requiring the user to open the application or interact with the GUI.
* Replies are dispatched by extracting the notification's `RemoteInput` action and firing its `PendingIntent` — including a fallback that recovers the reply action from the wearable (`WearableExtender`) channel when the inline one is absent.

### 3. Reply State Machine
* A per-sender finite state machine governs *what* is sent and *how often*, keyed on the notification title:
  * **First message in a window** → a random reply from the pool, or a keyword-matched reply if one fits.
  * **Repeat within the window** → a single spam warning, then silence (rate-limited by a cooldown).
  * **Unknown senders** → a distinct one-time "stranger" reply, after which they follow the normal track.
* **Anti-loop shields** prevent the bot from talking to itself or spamming duplicates:
  * *Mirror Shield* drops our own replies echoed back (scoped to a short window after each send).
  * *Photocopier Shield* drops Android's duplicate re-deliveries of the same text.
  * *Echo Shield* drops rapid bursts within a few seconds.

### 4. Reliable Foreground Detection
* The protocol stands down while the user is actively in WhatsApp. This uses `UsageStatsManager.queryEvents()` to reconstruct the current foreground app from the OS event stream (`MOVE_TO_FOREGROUND` / `MOVE_TO_BACKGROUND`), which is accurate even for long-open sessions — unlike the aggregated `queryUsageStats` snapshot.
* Requires **Usage Access** (`PACKAGE_USAGE_STATS`); the app detects the missing grant via `AppOpsManager` and guides the user to enable it. Results are cached briefly to keep the listener thread responsive.

### 5. Contact Resolution
* Distinguishes saved contacts from strangers by matching the notification title against the device contact list (`READ_CONTACTS`) — exact name match plus last-9-digit phone-number matching to normalize formats like `+94771234567` vs `0771234567`. Falls back to a number-only heuristic when permission is denied.

## Configuration & Persistence
* **User-editable behavior (no rebuild required):** the random reply pool, per-situation system messages (spam / call / stranger), keyword→reply rules, the contact whitelist, the reply window, and the spam cooldown are all editable from the in-app UI.
* **Whitelist / Focus Mode:** the whitelist normally lists contacts the protocol ignores. With **Focus Mode** enabled it inverts into an allow-list — the protocol replies *only* to those contacts and ignores everyone else.
* **Storage:** all settings persist in `SharedPreferences`, mirrored into an in-memory cache that is rebuilt lazily via an `OnSharedPreferenceChangeListener` so edits take effect immediately without per-notification disk reads.
* **Auto Backup:** `allowBackup` with scoped backup/data-extraction rules preserves all settings across app updates, reinstalls, and device transfers.

## Tech Stack
* **Language:** Java · **Min SDK:** 24 · **Target SDK:** 36
* **Core APIs:** `NotificationListenerService`, `RemoteInput` / `PendingIntent`, `UsageStatsManager`, `AppOpsManager`, `ContactsContract`, `WindowManager` overlays, foreground `Service`.
* **UI:** fully programmatic (no XML layouts), themed through a single centralized palette (`Theme.java`).

## Disclaimer
This project was built for educational and research purposes to understand the Android component lifecycle, memory management, and OEM process throttling. The 1x1 pixel overlay technique consumes additional battery by keeping the screen rendering pipeline active and should be used cautiously.
