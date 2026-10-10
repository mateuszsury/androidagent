# USB w androidagent 0.4.0

USB jest modułem aplikacji: panel oraz narzędzia MCP dla Codexa i Claude Code działają bez Dockera, roota, Shizuku i Termux. Uruchomienie samych klientów Codex/Claude nadal wymaga skonfigurowanego Termux. Testy tej wersji nie używają zalogowanych modeli.

## Użycie

1. Otwórz **Urządzenia USB** na ekranie głównym. Podłącz urządzenie kablem OTG i przyznaj dostęp w systemowym dialogu Androida.
2. Otwórz jeden profil: terminal UART/ESP, drugi Android przez ADB albo USB do Dockera. Adapter wieloportowy wymaga wybrania numeru portu od zera. Całe urządzenie ma jednego właściciela.
3. Działaj z panelu albo włącz dostęp agentów, rozpocznij rozmowę i przydziel połączenie aktywnej odpowiedzi albo jednorazowo następnemu zadaniu tej rozmowy. Narzędzia `usb_status`/`usb_open` pokażą przydzieloną sesję. Przydział wygasa po zakończeniu odpowiedzi, zatrzymaniu rozmowy, odłączeniu lub globalnym Stop. Następna odpowiedź wymaga nowego przydziału.
4. Dla plików wybierz katalog przez systemowy picker. Nadaj rozmowie odczyt albo odczyt i zapis. Systemowy dostęp USB i dostęp do katalogu są odrębnymi zgodami.

Włączenie dostępu agentów uruchamia widoczną usługę pierwszoplanową. Accessibility jest potrzebne do gestów i sterowania ekranem gospodarza, lecz nie do USB i Dockera. Jeden `McpHost` obsługuje wszystkie usługi na porcie 8766; uruchomienia rozmów dostają osobne, odwoływalne poświadczenia. Wspólny token instalacji nie przydziela dostępu do urządzeń. Nie przekazujemy tego tokenu kontenerom. Przydziały kontrolują routing MCP. Klienty w Termux dzielą ten sam UID; tryb programisty nie izoluje wzajemnie ich procesów ani danych.

## UART i ESP

Biblioteka `usb-serial-for-android` 3.11.0 dostarcza sterowniki FTDI, CP210x, CH34x, PL2303 i CDC ACM. Kompatybilność konkretnego modelu wymaga testu sprzętowego. Panel ma tekst/hex, LF/CRLF, parametry UART, DTR/RTS/break, licznik i zapis logu. Bufor ma 64 KiB i absolutne kursory; kolejni czytelnicy dostają kopie. Utracone starsze bajty są zgłaszane. Błąd zapisu raportuje timeout i znaną liczbę wysłanych bajtów. Otwarcie sterownika może zmienić linie kontrolne; podgląd logu nie wykonuje automatycznego resetu ESP.

JNI `EspFlasher` korzysta z przypiętej biblioteki Espressif. `esp_info` oraz `esp_flash` są zadaniami z postępem i anulowaniem. Wybierz ręczny BOOT/RESET albo jawnie włącz reset przez DTR/RTS. Flash wymaga numeru układu z identyfikacji, artefaktów z SHA256 i uporządkowanych segmentów z offsetami co 4 KiB. Limit: 16 segmentów, do 32 MiB oraz rzeczywistej pojemności flash. Biblioteka wykonuje weryfikację MD5. Nie udostępniamy operacji eFuse.

Zapis jest odmawiany przy niezgodnym układzie, secure boot, szyfrowaniu, secure-download lub braku dostępnej kontroli zabezpieczeń. Starsze warianty ROM bez tej kontroli mogą być identyfikowane, ale nie są dopuszczane do flashowania. Anulowanie/odłączenie może pozostawić częściowo zapisany firmware; nie oznacza rollbacku. Fizyczne programowanie ESP nie zostało jeszcze zweryfikowane w tym środowisku.

## Drugi Android

Natywny klient obsługuje USB bulk ADB, ramki AOSP, lokalny klucz RSA, AUTH, `shell_v2`, sync push/pull i instalację APK. Włącz debugowanie USB na drugim telefonie i zaakceptuj na nim klucz RSA. `adb_exec` pozwala też użyć `am`, `input`, `uiautomator`, `getprop` i innych dostępnych poleceń Android shell. `adb_logcat` pobiera ograniczony snapshot. Operacje są związane ze wskazaną sesją; nie wykonują komend na gospodarzu przez pomylenie domyślnego targetu.

Maksymalny shell timeout wynosi 120 s, AUTH 90 s, transfer 10 minut/512 MiB. Wyniki shell przechowują ostatnie 64 KiB każdego strumienia. Nowoczesny target zwraca stdout, stderr i kod wyjścia; starszy shell ma jawnie niedostępny kod wyjścia. Klucz RSA nie opuszcza aplikacji. Fastboot, odblokowanie bootloadera oraz odrębne narzędzie ADB forward nie należą do tej wersji; surowy profil Docker pozwala korzystać z pełnego klienta `adb` zainstalowanego w kontenerze.

## Nośniki i artefakty

`UsbStorage` używa SAF, identyfikatorów katalogów/dokumentów i rzeczywistych flag providera. Obsługuje stronicowanie, tekst/hex/base64 do 64 KiB, tworzenie, kopiowanie, import, eksport i usunięcie wybranego dokumentu. Transfery mają limit 512 MiB, postęp i anulowanie. Import oblicza SHA256. Zapis, kopia i eksport zamykają strumień, ponownie odczytują cel i porównują SHA256 oraz rozmiar przed zgłoszeniem sukcesu. Provider, który nie udostępni spójnego odczytu po zapisie, zwraca błąd weryfikacji zamiast potwierdzenia. Artefakty USB i zachowane wyniki Docker mogą być eksportowane do wybranego dokumentu. Zgoda tylko na odczyt nie może zostać podniesiona do zapisu przez agenta.

Po restarcie aplikacji przydziały katalogów wracają do panelu. Niedostępny nośnik pozostaje widoczny z `media_unavailable`. SAF nie gwarantuje atomowego zapisu: błąd, brak miejsca lub odłączenie może pozostawić częściowy plik. System plików i obsługa exFAT/FAT32 zależą od telefonu. Nie formatujemy dysków ani nie przejmujemy surowego interfejsu mass-storage zamontowanego przez Androida.

## USB do Dockera

**Surowe USB**: otwórz profil Docker, uruchom VM i przydziel połączenie konkretnemu zadaniu. `libusb_wrap_sys_device` wykorzystuje descriptor uzyskany przez `UsbManager`. `usbredirhost` wysyła protokół prywatnym socketem Unix do QEMU. QMP dodaje `usb-redir` na kontrolerze xHCI. Kontroler gościa weryfikuje port oraz VID/PID i udostępnia kontenerowi wyłącznie wybrany `/dev/bus/usb/BBB/DDD` przez `--device`. Nie używa `--privileged` ani montowania całego `/dev`. Obsługujemy do ośmiu przydzielonych urządzeń. Po zmianie przydziału uruchom kontener ponownie.

Ten profil udostępnia urządzenie narzędziom libusb, np. pełnemu `adb`. Obraz Linuxa nie ma sterowników tty dla każdego adaptera; `/dev/ttyUSB0` nie jest automatycznie dostępny. Native UART/ADB i profil raw Docker nie mogą jednocześnie używać tego samego urządzenia. Surowe mass-storage jest celowo odmawiane: użyj kopii plików SAF.

Zadanie z aktywnym przydziałem USB/UART wymaga tego samego właściciela także dla operacji MCP Dockera, w tym exec, plików, logów, eksportu i cleanup. Inna rozmowa oraz token instalacji nie mogą wykonać tych operacji na tym zadaniu. Panel aplikacji pozostaje dostępny właścicielowi telefonu. Ograniczenie nie wprowadza izolacji procesów w UID Termux opisanej wyżej.

**UART**: `usb_docker_serial` przydziela istniejącą sesję serial jednemu zadaniu. Kontener dostaje `ANDROIDAGENT_SERIAL_URL`, `PYTHONPATH` i moduł protokołu. Most HTTP obsługuje odczyt/zapis, baud, data/stop/parity oraz DTR/RTS/break, uwierzytelniony osobnym losowym tokenem zadania. Aplikacja nasłuchuje tylko na loopback 18782; gość korzysta z aliasu QEMU `10.0.2.2`. Token nie daje dostępu do MCP ani klucza ADB i nie jest ujawniany w zwykłym statusie zadania.

```python
# Zainstaluj pyserial w obrazie kontenera.
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

Połączenie API wykorzystujące handler pyserial obsługuje narzędzia Python importujące `serial.serial_for_url`; narzędzia zakładające wyłącznie systemowe `/dev/tty*` wymagają dostosowania. Moduł zamknięcia portu kończy lokalny uchwyt; przydział sprzętu odbiera panel, `usb_docker_detach`, Stop lub cleanup zadania. Revoke zatrzymuje korzystający z urządzenia kontener. Nie wznawiamy przydziałów po restarcie VM i nie przywracamy komend po ponownym podłączeniu sprzętu.

## Build i licencje

Źródła/commity: `usb/sources.json`; sprawdzone pliki: `usb/source-manifest.json`; oba JNI: `usb/native-manifest.json`. Licencje USB znajdują się w `usb/licenses`, a libusb/usbredir wraz z pozostałą zależnością QEMU w `docker/licenses`. Java ma dwie opisane adaptacje: usunięcie zależności od AndroidX IntDef i lokalny BuildConfig DEBUG=false. Kod Espressif jest vendored; operacje eFuse nie są linkowane do naszego JNI.

Po przygotowaniu QEMU (`python3 tools/prepare-qemu.py`) zbuduj JNI z Android NDK **29.0.14206865** na Linux x86_64:

```sh
ANDROID_NDK_ROOT=/ścieżka/do/ndk/29.0.14206865 bash tools/build-usb-native.sh
bash tools/build-docker-guest.sh x86_64
bash tools/build-docker-guest.sh aarch64
python3 tools/check-usb-assets.py
bash tools/build-local.sh
```

Gradle i lokalny builder wymagają zgodnych sum źródeł/JNI. JNI wykorzystuje Android API 30 i alignment 16 KiB. Biblioteki QEMU pozostają objęte własnymi wymaganiami zgodności opisanymi w `docs/DOCKER.md`. Nowy obraz gościa wymaga resetu wcześniejszego dysku VM; aplikacja odmawia podmiany bazy istniejącego overlay. Najpierw zachowaj wyniki starych zadań.

## Testy

`tools/test-usb.sh` uruchamia testy urządzenia i rzeczywistego `adbd` emulatora przez testową trasę `adb reverse` do portu gospodarza. SAF korzysta z testowego DocumentsProvider chronionego wymaganym Androidowym `MANAGE_DOCUMENTS`; Activity testowego APK przekazuje aplikacji rzeczywiste zgody URI na drzewa. Aplikacja produkcyjna nie przyjmuje uprawnień systemowych. UART jest syntetycznym portem z prawdziwą warstwą SerialTransport. Testy Docker dodają przez QMP wirtualne urządzenie HID, sprawdzają jego prawdziwy węzeł w kontenerze oraz rzeczywistą trasę kontener–QEMU–aplikacja dla UART fixture. Testy hosta korzystają z pyserial 3.5.

Emulator nie potwierdza zgodności fizycznego USB, kabla, zasilania ani transportu JNI usbredirhost z prawdziwym urządzeniem. Profile sprzętowe nie są jeszcze certyfikowane. Pozostają testy telefonu ARM64: CP210x/CH340 loopback, ESP (adapter i CDC), drugi Android z akceptacją/odmową RSA, FAT32/exFAT oraz fizyczne USB przekierowane do kontenera. Dostępność i zasilanie OTG zależą od gospodarza. Wyniki emulatora i ograniczenia wydania zapisujemy w `artifacts/usb` i raporcie testów 0.4.0.
