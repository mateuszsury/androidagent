# Testy androidagent 0.4.0

Weryfikacja lokalna 10 października 2026, bez zalogowanych modeli, zgodnie z decyzją użytkownika. USB zostało wdrożone jako moduł bezpośredni oraz opcjonalny dostęp z Dockera. Końcowy APK przeszedł 114 sprawdzeń na emulatorze Androida; dodatkowo przeszło 40 testów hosta i 13 testów Linux ARM64 pod QEMU gospodarza. Wszystkie zestawy zakończyły się bez błędów.

| Zestaw | Wynik | Dowód |
| --- | --- | --- |
| USB: panel, MCP, UART, JNI, SAF, realny adbd | 26/26 | `artifacts/usb/results.json` |
| Kontrakty Codex/Claude, granice gościa, handler pyserial | 40/40 | `artifacts/host-tests.txt` |
| Linux ARM64 pod QEMU gospodarza: build, kontener, USB fixture | 13/13 | `artifacts/docker/arm-guest-host-clock-results.json` |
| Aplikacja i MCP, API 30 | 51/51 | `artifacts/emulator/results.json` |
| Aplikacja → Termux → helper 13 → MCP | 7/7 | `artifacts/termux/results.json` |
| Docker w Androidzie: QEMU, build, aplikacja, USB i UART | 26/26 | `artifacts/docker/results.json` |
| Interfejs Dockera: motywy, obrót i Stop podczas startu VM | 4/4 | `artifacts/docker-ui/results.json` |

Sumy raportów, identyfikatory przebiegów, SHA256 końcowego APK i manifesty obu obrazów gościa: [indeks wydania 0.4.0](../artifacts/release-0.4.0.json). Każdy raport Androida pochodzi z przebiegu wykonanego po instalacji tego APK.

## Zakres USB

Emulator AOSP API 30/x86_64, 720 × 1480, 300 dpi, bez KVM. Testy mają osobny APK i nie są pakowane w produkt. Wyniki zawierają identyfikator bieżącego przebiegu; skrypty odrzucają stare raporty.

APK: `artifacts/androidagent-0.4.0.apk`, 460913640 bajtów. SHA256: `b18b6020d0e8b98c5a3a3e7bde75e508a76d73c572b47bec2704e1b5ab68db18`. Podpis APK v3 i suma pliku zostały zweryfikowane.

- Panel w jasnym/ciemnym motywie i po obrocie. Wspólny serwer MCP działa bez Accessibility i bez VM. Odrzuca niewłaściwe tokeny i nie pozwala agentowi samodzielnie przydzielić urządzenia. Per-run credentials, jednorazowy przydział do następnego zadania oraz odebranie dostępu przy zakończeniu/Stop są sprawdzone. Inne wywołanie i token instalacji nie mogą użyć exec/plików/eksportu/cancel/cleanup zadania z przydzielonym USB.
- UART korzysta z jawnego portu fixture i rzeczywistego `SerialTransport`: bajty binarne, wiele obserwatorów, kursory, przepełnienie bufora, wyłączność, konfiguracja i linie sterujące. Nie jest to fizyczny loopback USB.
- JNI jest ładowane z rzeczywistego APK x86_64. Niewłaściwy descriptor USB kończy się błędem bez pozostawienia socketa. ESP testuje anulowanie i nieprawidłowe mapy segmentów przed zapisem, w tym zbyt duże offsety. Nie potwierdza fizycznego flashowania ani weryfikacji ROM na ESP.
- SAF używa testowego `DocumentsProvider`, chronionego `MANAGE_DOCUMENTS`, oraz rzeczywistych zgód URI nadawanych przez Activity testowego APK. Testuje binarny zapis/odczyt, właścicieli, readonly, przywrócenie zapisu, SHA256 importu, nieznany rozmiar, brak nośnika, jawne usunięcie i odwołanie drzewa. Reliable pipe odtwarza częściowy zapis. Kontrolny odczyt celu zapobiega zgłoszeniu sukcesu po zapisaniu tylko części danych. Produkcyjna aplikacja nie otrzymuje uprawnień systemowych.
- ADB używa rzeczywistego `adbd` emulatora przez testową trasę `adb reverse` do portu hosta. Potwierdzono shell_v2, stdout/stderr, kod 7, binarny push/pull 120000 bajtów z SHA256 oraz instalację i odinstalowanie minimalnego testowego APK. Osobno testowana jest struktura AUTH/RSA i odrzucenie uszkodzonego legacy CNXN. To nie jest test interfejsu USB ani akceptacji klucza na fizycznym drugim telefonie.
- Zadania USB sprawdzają kontrolę właściciela i anulowanie. Zakończenie odpowiedzi odbiera jednorazowy dostęp SAF, unieważnia także otwarty strumień, anuluje transfer i nie przywraca dostępu w kolejnym wywołaniu. Testy hosta korzystają z pyserial 3.5 i rzeczywistego klienta URL wobec HTTP fixture: bajty, timeout, baud, DTR/RTS/break, czyszczenie wejścia, zamknięcie i odmowa niewłaściwego hosta.

## Docker i regresje

Test kontenera non-root wykrył nieczytelną bibliotekę UART: prywatne prawa 700/600 katalogów źródłowych trafiały do obrazu. Builder obu ABI nadaje teraz bibliotece odczyt i przejście katalogów dla użytkowników kontenera, a moduł pozostaje montowany readonly. Zestawy ARM64 i Androida potwierdzają odczyt biblioteki przez UID 1000 i rzeczywiste otwarcie wybranego węzła USB do odczytu/zapisu. Na Androidzie potwierdzono również odzyskanie listenera UART po zajętym porcie, ponowne otwarcie po odebraniu przydziału oraz pełną trasę UART z kontenera do aplikacji.

Nowe testy Dockera uruchamiają rzeczywisty QEMU i Docker Engine w UID aplikacji Android. QMP sprawdza sterownik `usb-redir` i prywatny chardev Unix, a jawne wirtualne HID służy do sprawdzenia enumeracji Linuxa i mapowania pojedynczego węzła `/dev/bus/usb` do kontenera. Drugi task nie może przejąć tego samego portu, a stara generacja nie może odebrać nowego przydziału. UART fixture jest dostępny z prawdziwego kontenera przez QEMU `10.0.2.2`; sprawdzane są token, bajty i parametry. Nie potwierdza to fizycznego przekierowania descriptorów Android USB przez JNI.

Osobno poprawiono przygotowanie i sprzątanie fixture: QEMU 11 usuwa własny chardev wraz z `usb-redir`, więc test sprawdza pozostałe kanały zamiast usuwać go ponownie. Odrzucona próba exec po odłączeniu USB jest oczekiwana do zakończenia przed przydzieleniem UART. Test anulowania uruchamia własny kontener. Po przerwanym przebiegu test Accessibility czeka na faktyczne wyłączenie usługi przed ponownym włączeniem, aby wyczyścić stan `crashed` w Androidzie.

Linux ARM64 sprawdzono dodatkowo pod natywnym QEMU na gospodarzu: rzeczywisty build, podgląd HTTP, kontener non-root, limit pamięci, eksport, scoped cleanup i shutdown, rozszerzone o wirtualne USB oraz odebranie przydziału. Ten zestaw nie jest testem Androida ARM64.

Regresje aplikacji obejmują metryki Androida, powłokę z UID aplikacji, timeout i procesy potomne, anulowanie, Unicode, trwałe pliki/rozmowy/szkice, renderer, oba motywy, konfigurację, prawdziwe Accessibility/MCP i sterowanie Androidowymi ekranami. Timeout terminala oczekuje teraz na zakończenie procesów po SIGKILL; test `/proc` rozpoznaje także proces znikający podczas odczytu. Fixture strumienia Claude nie zastępuje rozmowy z modelem. Termux sprawdza rzeczywiste oficjalne klienty i poprawne blokady bez logowania.

## Odtwarzanie

Przygotuj oba runtime'y QEMU, JNI USB i obrazy gościa według [USB.md](USB.md) i [DOCKER.md](DOCKER.md), a następnie:

```sh
bash tools/build-local.sh
adb install -r artifacts/androidagent-0.4.0.apk
bash tools/test-usb.sh
bash tools/test-android.sh
bash tools/test-termux.sh
bash tools/test-docker.sh
bash tools/test-docker.sh --ui-only
```

Ustaw `JAVA_HOME`, `ANDROID_SDK_ROOT` i `KEYSTORE` zgodnie z README. Termux i klienty muszą być przygotowane na przeznaczonym do testów emulatorze. Testy tworzą i usuwają dane fixture, włączają Accessibility, instalują minimalny pakiet ADB oraz resetują dysk zadań VM. Testy hosta: `python3 -m unittest discover -s tests -p 'test_*.py' -v`, z zainstalowanym pyserial 3.5. Zegar emulowany nie daje miarodajnego porównania wydajności telefonu.

## Granice wydania

Nie zweryfikowano fizycznego telefonu ARM64, kabla/zasilania OTG, zgód dla rzeczywistego USB, zgodności adapterów CP210x/CH340/CDC, flashowania ESP, drugiego telefonu z AUTH ani nośników FAT32/exFAT. Nie zweryfikowano fizycznego USB przekazanego przez usbredirhost do kontenera. Macierz sprzętowa pozostaje do wykonania według [planu USB](USB_PLAN.md). Emulator i porty fixture nie certyfikują profili sprzętowych.

Brak zalogowanych modeli oznacza brak testu końcowego zadania z usługą Codexa/Claude. API 35/36 oraz Androidowe urządzenia z 16 KiB pages nie są potwierdzone tym przebiegiem. GitHub Actions ma przygotowaną macierz API 30/35; wcześniejsze uruchomienia zatrzymały się przed jobami z powodu płatności/limitu konta, a nie błędu aplikacji. Podstawą wyników są lokalne raporty z emulatora.

Historia weryfikacji 0.3.0: [raport w poprzednim commicie](https://github.com/mateuszsury/androidagent/blob/e46a00e36ac7c97bce942b8cb1d81692910f2bdd/docs/TESTING.md). Nie zastępuje testów aktualnego APK.
