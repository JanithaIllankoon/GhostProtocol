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

## Disclaimer
This project was built for educational and research purposes to understand the Android component lifecycle, memory management, and OEM process throttling. The 1x1 pixel overlay technique consumes additional battery by keeping the screen rendering pipeline active and should be used cautiously.
