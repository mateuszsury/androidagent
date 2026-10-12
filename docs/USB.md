# USB devices

The USB panel and MCP tools work directly without Docker, root, Shizuku or a separate Termux application. Codex/Claude use androidagent's embedded environment. Physical device, cable, power and firmware compatibility still require hardware testing.

## Connect and assign

1. Open **USB devices** (`Urządzenia USB`), connect an OTG accessory and grant access in Android's device dialog.
2. Select UART/ESP, ADB to another Android, or Docker passthrough. Multiport adapters require a zero-based port selection. The whole device has one owner.
3. Use the panel directly or enable agent access and assign a connection to an active conversation run, or once to that conversation's next run. `usb_status`/`usb_open` show its session. Grants expire when the response ends, the conversation stops, the device disconnects or global Stop is used. A later response needs a new assignment.
4. Select storage through Android's SAF picker and grant read or read/write separately. USB device permission is not folder permission.

One loopback MCP host serves agent, USB and Accessibility services. Each conversation run receives revocable credentials; the installation token alone cannot assign devices. Accessibility is only needed for host-screen gestures. Agents in developer mode share the app UID, so MCP ownership is not process isolation between those agents.

## UART and ESP

`usb-serial-for-android` 3.11.0 supplies FTDI, CP210x, CH34x, PL2303 and CDC ACM drivers. The panel supports text/hex, LF/CRLF, UART parameters, DTR/RTS/break, counters and log saving. A 64 KiB buffer uses absolute cursors and reports overwritten older bytes. Opening a driver can change control lines; reading the log does not automatically reset an ESP.

The pinned Espressif JNI implements `esp_info` and cancellable `esp_flash`. Choose manual BOOT/RESET or explicitly enable DTR/RTS reset. Flash requires the detected chip identity, SHA256-identified artifacts and ordered4 KiB-aligned segments: at most 16 segments/32 MiB and no more than real chip capacity. The library verifies MD5. No eFuse operations are exposed.

Flash is refused for mismatched chips, secure boot, encryption, secure-download or unavailable security checks. Older ROMs lacking those checks can be identified but not flashed. Cancellation/disconnection may leave partial firmware and is not rollback. Physical ESP flashing is not yet validated.

## A second Android device

The native ADB client implements USB bulk/AOSP frames, local RSA AUTH, `shell_v2`, sync push/pull, logcat snapshots and APK installation. Enable USB debugging on the target and accept its RSA prompt. `adb_exec` can run available shell commands such as `am`, `input`, `uiautomator` and `getprop`. Operations target the explicit session rather than the host phone.

Maximum shell timeout is 120 s, AUTH90 s and file transfer10 minutes/512 MiB. Shell retains the last 64 KiB of each stream; modern targets return stdout/stderr/exit status, while legacy shell marks exit status unavailable. The private key stays in app storage; only its public key is sent to the target. Fastboot, bootloader unlocking and a separate native ADB-forward interface are not implemented. Raw Docker passthrough permits a full container-installed `adb`.

## Storage and exports

SAF supports paging, text/hex/base64 up to 64 KiB, create/copy/import/export/delete and 512 MiB cancellable transfers. Imports compute SHA256; writes/copies/exports reopen the target and verify SHA256 and length before reporting success. Providers without consistent read-after-write produce a verification error. An agent cannot promote a read-only grant to write.

After restart, selected directories return to the panel. Unavailable media remains visible as `media_unavailable`. SAF is not atomic: errors, full storage or removal can leave partial files. FAT32/exFAT support depends on Android/OEM. The app does not format disks or claim raw mass-storage interfaces mounted by Android.

## Docker passthrough

**Raw USB:** open the Docker profile, start the VM and assign the connection to a task. `libusb_wrap_sys_device` uses Android's granted descriptor; usbredirhost passes it through an app-private Unix socket to QEMU xHCI via QMP. The guest verifies port and VID/PID, then grants only `/dev/bus/usb/BBB/DDD` through `--device`. Up to 8 devices are supported, without privileged mode or a whole `/dev` mount. Restart the container after changing assignment.

This is useful for libusb tools such as full `adb`; it does not promise `/dev/ttyUSB0` for every adapter. Native UART/ADB and raw Docker cannot simultaneously own one device. Raw mass storage is refused; use SAF copies.

**UART bridge:** `usb_docker_serial` assigns a serial session to one task. The container gets `ANDROIDAGENT_SERIAL_URL`, `PYTHONPATH` and the read-only URL handler; install pyserial in the image. The authenticated HTTP bridge supports read/write, baud/data/stop/parity and DTR/RTS/break on phone loopback 18782 through guest alias `10.0.2.2`. Its task token does not authorize MCP or ADB and is omitted from normal status.

```python
import os
import serial

port = serial.serial_for_url(os.environ['ANDROIDAGENT_SERIAL_URL'],
                             baudrate=115200, timeout=1)
port.write(b'version\n')
print(port.readline())
port.dtr = True
port.rts = False
port.close()
```

Tools using `serial.serial_for_url` work through this handler; tools requiring only `/dev/tty*` need adaptation. Closing the Python handle does not revoke the hardware assignment. Panel revoke, `usb_docker_detach`, Stop and task cleanup do; revocation stops the using container. Grants/commands are not automatically restored after reconnect or VM restart.

Every MCP operation on a USB-assigned Docker task, including exec/files/logs/export/cleanup, requires the same owner. Other conversations and the installation token cannot operate that task. The native panel remains available to the phone owner.

## Build and evidence

Pinned origin: [usb/sources.json](../usb/sources.json); adapted file hashes: [source manifest](../usb/source-manifest.json); JNI hashes: [native manifest](../usb/native-manifest.json). Java adaptations remove AndroidX IntDef and supply local DEBUG=false. Espressif sources are vendored; eFuse APIs are not linked. NDK29.0.14206865 builds API 30 JNI with 16 KiB alignment. Licensing and source details: [USB README](../usb/README.md) and [third-party notices](../THIRD_PARTY_NOTICES.md).

`tools/test-usb.sh` exercises real JNI, emulator adbd through test-only `adb reverse`, shell/sync/APK operations, Android SAF URI grants and synthetic UART. Docker tests use a virtual HID device and real container→QEMU→app UART routing. These do not validate physical usbredir, OTG power/cables, ESP or storage adapters. [Testing](TESTING.md) records current evidence. Physical CP210x/CH340, ESP, second-phone RSA, FAT32/exFAT and container passthrough remain required hardware coverage.
