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
| Set alarm (`SET_ALARM`) | MCP `set_alarm` / `set_timer` hand requests to the clock app | Normal manifest permission; no runtime dialog |
| Modify system settings (`WRITE_SETTINGS`) | MCP `set_brightness` only | Optional special access the owner grants in system settings; the tool returns an error until then |
| Package visibility (`<queries>`) | Naming the app that handles a link, dialer, SMS composer, share sheet, calendar editor or clock | Declared in the manifest; no `QUERY_ALL_PACKAGES` |
| Notification access | `notifications_list` / `notification_action` read and act on other apps' notifications | Enable androidagent under Android's Notification access |
| Location (approximate/precise) | `get_location` while the app is visible | Optional runtime consent from the Permissions screen |
| Photos (`READ_MEDIA_IMAGES`; `READ_EXTERNAL_STORAGE` up to Android 12) | `recent_photos` / `import_photo` | Optional runtime consent from the Permissions screen |

Denying `POST_NOTIFICATIONS` does not prevent a foreground service. Android still requires its notification and may display it only in the system active-apps panel. Android 16's promoted-notification permission affects presentation, not whether commands or conversations can run.

The guide lists the app's own consents first (notifications, microphone, screen control, battery) and the agent-tool consents after them (notification access, location, photos, installing apps, modifying system settings). Each card shows the live state and opens only androidagent's own entry in Android. Tools never show permission dialogs; when access is off they return an error that points the owner to this screen. `take_photo` needs no permission because the camera app takes the picture. Details are in [Phone access](PHONE-ACCESS.md).

## Screen control

The app checks the exact component of its own Accessibility service and distinguishes enabled from actually connected. It does not write the global Accessibility settings or disable other services, including FlowMesh. Terminal, chat, USB, Docker and the [phone tools below](#phone-tools-without-accessibility) do not require Accessibility; screen tools are not listed without it and return a consent error if called anyway.

Android may block Accessibility after sideloading. The guide explains the owner-operated **Allow restricted settings** option in App info where supported. The app does not bypass that confirmation, the lock screen or protected system screens.

## Phone tools without Accessibility

These MCP tools work with screen control off. The agent sees screen tools (`read_screen`, `tap`, `find_and_tap`, `type_text`, `ime_action`, `scroll`, `swipe`, `press`, `screenshot`) in its tool list only while the Accessibility service is connected.

| Group | Tools | Limits |
| --- | --- | --- |
| Approval and status | `confirm`, `notify`, `wait`, `wait_for_install` | `confirm` shows the chat approval card (mirrored in the bubble when it is shown, plus a notification when no chat is in front) and returns APPROVED, DENIED or TIMEOUT after 5 minutes. `notify` posts to the low-priority **Agent** channel; the notice is removed when the run ends |
| Apps and links | `open_app`, `list_apps`, `open_url`, `start_intent`, `share_text`, `open_settings` | `start_intent` refuses calls, SEND/SENDTO, install/uninstall/delete, resets, device admin and roles, androidagent's own screens, `file://` data and URI-permission flags |
| Phone, SMS, calendar, clock | `dial`, `compose_sms`, `add_calendar_event`, `set_alarm`, `set_timer` | Dialer, SMS composer and calendar editor only open pre-filled; the owner taps Call, Send or Save. Service codes (`*#…`) are refused |
| Device | `get_volume`, `set_volume`, `media_key`, `torch`, `vibrate`, `speak`, `clipboard_set`, `clipboard_get`, `set_brightness` | Android lets only the focused app read the clipboard; brightness needs **Modify system settings** |

Android blocks activity starts from the background. Without Accessibility, tools that open a screen work while an androidagent screen is in front; otherwise they return an error and post a tap-to-open notice in the **Agent · wymaga Twojej akcji** channel, which also carries approval reminders. The app does not request the "display over other apps" permission to work around this.

## Commands and Stop

MCP `run_command` requires developer mode and an approval card containing the complete command. Approval belongs to one request and run credential. Old buttons cannot approve replacement requests. Stop and run completion revoke pending approvals and cancel associated commands. A terminal opened by the owner executes their entered command directly.

The agents' own developer-mode coding tools have broader app-UID access as described in [Claude Code](CLAUDE.md). They are not subject to the MCP command card for every operation.

## Background and storage

Battery exemptions improve the chance of finishing long tasks, but do not remove Android/OEM memory or child-process limits. They are reversible through Android settings. The app does not globally disable Android protections.

USB uses the `connectedDevice` service type where required and starts only after device consent. SAF avoids broad storage permissions: no `MANAGE_EXTERNAL_STORAGE` is required. Device permission and conversation assignment are distinct; choosing a folder grants only the requested read or read/write access.

Provider login is separate from system permissions. An initialized environment is not an authenticated model account. Credentials remain in app-private storage and are not copied from another computer.

Permission instrumentation checks native settings routes, refresh behavior, exact service recognition and preservation of existing Accessibility state. Emulator instrumentation can change its own test settings; it is not evidence that the application can grant itself permissions.
