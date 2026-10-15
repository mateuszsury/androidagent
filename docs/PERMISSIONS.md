# Permissions and background operation

The Permissions screen reads the current state and opens Android's own consent dialogs/settings. It refreshes after returning to the app. Opening the guide alone neither requests permission nor changes settings. The app cannot grant itself system consent.

| Access | Purpose | Owner action |
| --- | --- | --- |
| Internet/network state | Official clients and model connections | Normal manifest permissions; no runtime dialog |
| Foreground service / special-use service | User-started tools and VM tasks | Started from the visible app, with the required notification |
| Wake lock | Keep CPU work active during VM operation | Normal manifest permission |
| Notifications, API 33+ | Visible task status and notification Stop | Optional Android permission or app notification settings |
| Microphone | Dictation | Optional runtime consent; typing remains available |
| Accessibility | Screen reading and gestures in other apps | Enable androidagent's service in system Accessibility settings |
| Battery optimization exception | Long conversations/builds and background VM | Optional system confirmation for this app |
| USB | One selected OTG device | Android USB permission dialog for that device |
| External files/folders | Attachments, chosen storage and USB media | SAF picker grants for selected URIs |
| Install unknown apps | Installing an exported APK or one built by an agent (`install_apk`) | Optional install-source setting plus Android's package installer; the owner taps Install |
| Notification access | `notifications_list` / `notification_action` read and act on other apps' notifications | Enable androidagent under Android's Notification access |
| Location (approximate/precise) | `get_location` while the app is visible | Optional runtime consent from the Permissions screen |
| Photos (`READ_MEDIA_IMAGES`; `READ_EXTERNAL_STORAGE` up to Android 12) | `recent_photos` / `import_photo` | Optional runtime consent from the Permissions screen |
| Modify system settings | Tools that change settings such as brightness | Optional per-app system setting |

Denying `POST_NOTIFICATIONS` does not prevent a foreground service. Android still requires its notification and may display it only in the system active-apps panel. Android 16's promoted-notification permission affects presentation, not whether commands or conversations can run.

The guide lists the app's own consents first (notifications, microphone, screen control, battery) and the agent-tool consents after them (notification access, location, photos, installing apps, modifying system settings). Each card shows the live state and opens only androidagent's own entry in Android. Tools never show permission dialogs; when access is off they return an error that points the owner to this screen. `take_photo` needs no permission because the camera app takes the picture. Details are in [Phone access](PHONE-ACCESS.md).

## Screen control

The app checks the exact component of its own Accessibility service and distinguishes enabled from actually connected. It does not write the global Accessibility settings or disable other services, including FlowMesh. Terminal, chat, USB and Docker do not require Accessibility; screen tools return a consent error without it.

Android may block Accessibility after sideloading. The guide explains the owner-operated **Allow restricted settings** option in App info where supported. The app does not bypass that confirmation, the lock screen or protected system screens.

## Commands and Stop

MCP `run_command` requires developer mode and an approval card containing the complete command. Approval belongs to one request and run credential. Old buttons cannot approve replacement requests. Stop and run completion revoke pending approvals and cancel associated commands. A terminal opened by the owner executes their entered command directly.

The agents' own developer-mode coding tools have broader app-UID access as described in [Claude Code](CLAUDE.md). They are not subject to the MCP command card for every operation.

## Background and storage

Battery exemptions improve the chance of finishing long tasks, but do not remove Android/OEM memory or child-process limits. They are reversible through Android settings. The app does not globally disable Android protections.

USB uses the `connectedDevice` service type where required and starts only after device consent. SAF avoids broad storage permissions: no `MANAGE_EXTERNAL_STORAGE` is required. Device permission and conversation assignment are distinct; choosing a folder grants only the requested read or read/write access.

Provider login is separate from system permissions. An initialized environment is not an authenticated model account. Credentials remain in app-private storage and are not copied from another computer.

Permission instrumentation checks native settings routes, refresh behavior, exact service recognition and preservation of existing Accessibility state. Emulator instrumentation can change its own test settings; it is not evidence that the application can grant itself permissions.
