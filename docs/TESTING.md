# Testy androidagent 0.4.3 i historia 0.4.2

## Bieżące wydanie 0.4.3

Wersja 0.4.3 (`versionCode=403`, helper 16, `targetSdk=36`) dodaje środowisko terminalowe do samej aplikacji. Czysta instalacja nie wymaga `com.termux`, osobnej usługi `RUN_COMMAND` ani ręcznego `pkg`/`npm`. Wbudowany bootstrap i narzędzia są rozpakowywane automatycznie; oficjalne klienty Codex 0.162.1 i Claude Code 2.1.296 pobierają się z UI przez HTTPS z przypiętą długością i SHA256. Logowanie pozostaje osobnym działaniem właściciela przez oficjalną metodę klienta. `/status`, fixtures strumienia i uruchomienie `--version` nie są rozmową z zalogowanym modelem.

Ukończony zestaw [Linux ARM64: start i migracja](../artifacts/docker/arm-startup-migration-results.json) zaliczył **34/34 sprawdzenia**. Jest to gość aarch64 pod QEMU TCG 10.1.5 na gospodarzu Linux x86_64, **nie Android ARM64 ani fizyczny Lenovo**. Docker Engine 29.5.2 i containerd 2.2.0 działają z rzeczywistym zegarem gospodarza, bez `icount`, z 1024 MiB RAM i jednym vCPU. Nowe fazy ograniczają CPU gospodarza do 50%; nie stanowi to skalibrowanego benchmarku tabletu.

| Faza gościa ARM64 | Warunki | Gotowość od uruchomienia VM |
| --- | --- | ---: |
| Dane utworzone pod kodem 0.4.2 | Oryginalny initramfs, 1 CPU gospodarza | 37,16 s |
| Aktualizacja istniejącego dysku | Nowy nadzór, 0,5 CPU, celowe opóźnienie containerd 20 s | 70,497 s |
| Ponowny start tego samego dysku | 0,5 CPU, bez dodatkowego opóźnienia | 51,179 s |
| Czysty dysk i import obrazu | 0,5 CPU, celowe opóźnienie containerd 20 s | 86,78 s |

Raport potwierdza identyczne bajty bazy i kernela 0.4.2, zachowany inode dysku, projekty/obrazy/kontenery/logi po migracji i restarcie, rzeczywisty ping/info, import Alpine, build `RUN`/`COPY`, run, exec, UID 1000, limit cgroup 128 MiB, logi, eksport, anulowanie, sprzątanie wybranego zadania oraz zsynchronizowane zatrzymanie QEMU. Gotowość containerd jest weryfikowana rzeczywistym RPC przed startem dockerd z jawnym socketem. Serwer kontenera zwraca prawdziwy interaktywny HTML; wykonanie jego JavaScript wymaga osobnego testu Android WebView. Logi i identyfikator `8f904ef1-c107-4f96-8b5a-51403c7cc05d` są w raporcie.

Testy bieżącego APK wbudowanego środowiska, czystej instalacji bez Termux i aktualizacji z dokładnej opublikowanej APK 0.4.2 są w trakcie weryfikacji. Liczb z historycznych raportów 0.4.2 nie przypisujemy nowemu APK. Końcowy indeks wydania powinien wiązać ukończone przebiegi z SHA256, rozmiarem i certyfikatem nowej APK.

Zestaw `EmbeddedRuntimeTests` sprawdza pod UID niedebugowalnej aplikacji rzeczywisty bootstrap, Bash/Node/Python/Git/jq/curl, shebang i procesy potomne, zegar, anulowanie PRoot, PTY poprzez `script`, wejście z natywnego terminala, pliki w HOME, powtórne przygotowanie, zachowanie danych z 0.4.2, uszkodzone archiwum, oficjalne klienty i stan niezalogowanego konta. Raport zapisuje API, ABI, UID, rozmiar strony, `targetSdk`, wersje, identyfikator oraz czas każdego sprawdzenia. Zweryfikowane archiwa klientów w tym zestawie trafiają przez lokalny transport `adb reverse`; **nie potwierdza to pobierania przez HTTPS z Androida**. Pierwsze uruchomienie binariów jest rzeczywiste, a konto właściciela nie jest podmieniane ani kopiowane.

Nowe regresje [uprawnień](PERMISSIONS.md) nie nadają zgód i nie zastępują listy istniejących usług Accessibility, w tym FlowMesh. Brak Accessibility, odmowa powiadomień i aktywna optymalizacja baterii nie blokują lokalnego środowiska ani rozmów po zalogowaniu. MCP `run_command` działa bez Accessibility; wymagane pozostają tryb programisty, pełna dokładna komenda w karcie zgody i ważne poświadczenie konkretnego przebiegu. Dodatkowe testy sprawdzają wykonanie dopiero po kliknięciu, odmowę, stare ID/przycisk po zastąpieniu prośby, odebranie poświadczeń, przerwanie aktywnej powłoki i anulowanie przed utworzeniem procesu.

Fizyczny Lenovo TB336FU (Android 16/API36, arm64-v8a, strony 4 KiB) miał osobną weryfikację **0.4.2**: diagnostyka i krótkie komendy działały, lecz Docker powtarzalnie zatrzymywał się przy 15-sekundowym limicie managed containerd przy 1 i 2 GB RAM. [Raport i log fizycznego testu są assetami v0.4.2](https://github.com/mateuszsury/androidagent/releases/tag/v0.4.2). To jest wynik poprzedniej APK, a nie ukończony test poprawki. Końcowa instalacja i weryfikacja 0.4.3 na tablecie pozostają osobną walidacją sprzętową.

### Odtwarzanie bieżącej wersji

Przygotuj JNI USB, QEMU/gościa Docker i [przypięte środowisko terminala](../terminal/README.md). Ustaw `JAVA_HOME`, `ANDROID_SDK_ROOT`, `KEYSTORE` oraz `ANDROID_SERIAL` na przeznaczony do testów emulator. Zachowaj certyfikat aktualizacji; nie zastępuj pliku 0.4.2 nowymi bajtami.

```sh
python3 tools/prepare-terminal.py
python3 tools/check-terminal-assets.py
bash tools/build-local.sh
bash tools/test-embedded-runtime.sh --clean artifacts/androidagent-0.4.3.apk
bash tools/test-embedded-runtime.sh --upgrade artifacts/androidagent-0.4.2.apk artifacts/androidagent-0.4.3.apk
bash tools/test-android.sh
bash tools/test-usb.sh
bash tools/test-docker.sh
bash tools/test-webview.sh
```

Tryby `--clean` i `--upgrade` usuwają instalację i dane testowe, a skrypt wymaga emulatora. Nie uruchamiaj pełnego zbiorczego zestawu na telefonie z potrzebnymi danymi: historyczne scenariusze Accessibility zmieniają jego ustawienia, a testy Dockera resetują dyski fixture. Sam ekran Uprawnień nie wykonuje takich zmian. Nowe raporty środowiska są eksportowane do dedykowanego `Download/androidagent-embedded-tests/` i kopiowane pod `artifacts/api<API>/embedded-runtime/{clean,upgrade}`; skrypt odrzuca stary `run_id`, niewłaściwą wersję i niekompletny zestaw. Test gościa ARM64 można odtworzyć przez `python3 tools/test-docker-startup.py`; korzysta z odrębnego dysku testowego gospodarza i oryginalnego initramfs 0.4.2.

## Historyczne wydanie 0.4.2

Poniższe zestawy i ich suma dotyczą dokładnej APK 0.4.2 oraz wcześniejszych emulatorów. Zachowujemy je jako historię; nie są potwierdzeniem nowego środowiska terminalowego ani aktualizacji 0.4.3.

Weryfikacja po [dodatkowym przeglądzie](REVIEW_0.4.2.md), bez logowania do modeli. Końcowy APK i obraz Linux ARM64 mają **392/392 zaliczonych sprawdzeń w ukończonych zestawach**. [Indeks wydania](../artifacts/release-0.4.2.json) wiąże wyniki z sumą konkretnego APK i identyfikatorami przebiegów. Przerwane i nieudane wcześniejsze próby opisano osobno poniżej; nie wchodzą do tej sumy.

| Zestaw | Android 11 / API 30 | Android 16 / API 36 |
| --- | ---: | ---: |
| Aplikacja i MCP | [74/74](../artifacts/emulator/results.json) | [75/75](../artifacts/api36/emulator/results.json) |
| USB | [40/40](../artifacts/usb/results.json) | [41/41](../artifacts/api36/usb/results.json) |
| Termux i oficjalne klienty | [7/7](../artifacts/termux/results.json) | Nie testowano |
| Panel Dockera i regresje | [8/8](../artifacts/docker-ui/results.json) | [8/8](../artifacts/api36/docker-ui/results.json) |
| Pełny Docker, USB i UART | [27/27](../artifacts/docker/results.json) | [27/27](../artifacts/api36/docker/results.json) |
| WebView bez silnika Docker | W ramach pełnego Dockera | [8/8](../artifacts/api36/webview/results.json) |

Poza Androidem: [64/64 testy hosta](../artifacts/host-tests.txt) oraz [13/13 dla aktualnego Linuxa ARM64 pod QEMU gospodarza](../artifacts/docker/arm-guest-host-clock-results.json). Suma obejmuje wykonania tych samych scenariuszy na różnych platformach; nie jest liczbą unikalnych funkcji.

## Zakres USB

Dwa emulatory AOSP x86_64: Android 11/API 30 z 4 GiB RAM oraz Android 16/API 36 z 2 GiB RAM dla aplikacji, USB i panelu Dockera. Ukończone pełny Docker oraz osobny WebView na API 36 używają 4 GiB i osobnej kopii emulatora z poprawką TCG. Pozostałe ukończone zestawy API 36 wykonano na oryginalnym silniku. Wcześniejsze próby przed poprawką trwałości używały 2 GiB na API 30 dla krótkich zestawów, 4 GiB dla pełnego Dockera oraz 4 GiB na API 36. Oba mają rozdzielczość 720 × 1480, 300 dpi i działają bez KVM. Testy mają osobny APK i nie są pakowane w produkt. Wyniki zawierają identyfikator bieżącego przebiegu; skrypty odrzucają stare raporty.

APK: `artifacts/androidagent-0.4.2.apk`, 460925925 bajty. SHA256: `5300a530810554f5f693e5757e34e4683734b92c647b192f247c7f747aad80a0`. Podpis APK v3 i suma pliku zostały zweryfikowane.

- Panel w jasnym/ciemnym motywie i po obrocie. Wspólny serwer MCP działa bez Accessibility i bez VM. Odrzuca niewłaściwe tokeny i nie pozwala agentowi samodzielnie przydzielić urządzenia. Per-run credentials, jednorazowy przydział do następnego zadania oraz odebranie dostępu przy zakończeniu/Stop są sprawdzone. Inne wywołanie i token instalacji nie mogą użyć exec/plików/eksportu/cancel/cleanup zadania z przydzielonym USB.
- UART korzysta z jawnego portu fixture i rzeczywistego `SerialTransport`: bajty binarne, wiele obserwatorów, kursory, przepełnienie bufora, wyłączność, konfiguracja i linie sterujące. Nie jest to fizyczny loopback USB.
- JNI jest ładowane z rzeczywistego APK x86_64. Niewłaściwy descriptor USB kończy się błędem bez pozostawienia socketa. ESP testuje anulowanie i nieprawidłowe mapy segmentów przed zapisem, w tym zbyt duże offsety. Nie potwierdza fizycznego flashowania ani weryfikacji ROM na ESP.
- SAF używa testowego `DocumentsProvider`, chronionego `MANAGE_DOCUMENTS`, oraz rzeczywistych zgód URI nadawanych przez Activity testowego APK. Testuje binarny zapis/odczyt, właścicieli, readonly, przywrócenie zapisu, SHA256 importu, nieznany rozmiar, brak nośnika, jawne usunięcie i odwołanie drzewa. Reliable pipe odtwarza częściowy zapis. Kontrolny odczyt celu zapobiega zgłoszeniu sukcesu po zapisaniu tylko części danych. Produkcyjna aplikacja nie otrzymuje uprawnień systemowych.
- ADB używa rzeczywistego `adbd` emulatora przez testową trasę `adb reverse` do portu hosta. Potwierdzono shell_v2, stdout/stderr, kod 7, binarny push/pull 120000 bajtów z SHA256 oraz instalację i odinstalowanie minimalnego testowego APK. Osobno testowana jest struktura AUTH/RSA i odrzucenie uszkodzonego legacy CNXN. To nie jest test interfejsu USB ani akceptacji klucza na fizycznym drugim telefonie.
- Zadania USB sprawdzają kontrolę właściciela i anulowanie. Zakończenie odpowiedzi odbiera jednorazowy dostęp SAF, unieważnia także otwarty strumień, anuluje transfer i nie przywraca dostępu w kolejnym wywołaniu. Testy hosta korzystają z pyserial 3.5 i rzeczywistego klienta URL wobec HTTP fixture: bajty, timeout, baud, DTR/RTS/break, czyszczenie wejścia, zamknięcie i odmowa niewłaściwego hosta.

## Docker i regresje

Przed wydaniem 0.4.0 test kontenera non-root wykrył nieczytelną bibliotekę UART: prywatne prawa 700/600 katalogów źródłowych trafiały do obrazu. Builder obu ABI nadaje od tego wydania bibliotece odczyt i przejście katalogów dla użytkowników kontenera, a moduł pozostaje montowany readonly. Historyczne zestawy Linux ARM64 i Androida potwierdziły odczyt biblioteki przez UID 1000 i rzeczywiste otwarcie wybranego węzła USB do odczytu/zapisu. Na Androidzie potwierdzono również odzyskanie listenera UART po zajętym porcie, ponowne otwarcie po odebraniu przydziału oraz pełną trasę UART z kontenera do aplikacji. Bieżący zestaw Androida potwierdził te scenariusze dla APK 0.4.2; aktualny obraz Linux ARM64 ponownie przeszedł 13 sprawdzeń na gospodarzu.

Testy Dockera uruchamiają rzeczywisty QEMU i Docker Engine w UID aplikacji Android. QMP sprawdza sterownik `usb-redir` i prywatny chardev Unix, a jawne wirtualne HID służy do sprawdzenia enumeracji Linuxa i mapowania pojedynczego węzła `/dev/bus/usb` do kontenera. Drugi task nie może przejąć tego samego portu, a stara generacja nie może odebrać nowego przydziału. UART fixture jest dostępny z prawdziwego kontenera przez QEMU `10.0.2.2`; sprawdzane są token, bajty i parametry. Nie potwierdza to fizycznego przekierowania descriptorów Android USB przez JNI.

Osobno poprawiono przygotowanie i sprzątanie fixture: QEMU 11 usuwa własny chardev wraz z `usb-redir`, więc test sprawdza pozostałe kanały zamiast usuwać go ponownie. Odrzucona próba exec po odłączeniu USB jest oczekiwana do zakończenia przed przydzieleniem UART. Test anulowania uruchamia własny kontener. Po przerwanym przebiegu test Accessibility czeka na faktyczne wyłączenie usługi przed ponownym włączeniem, aby wyczyścić stan `crashed` w Androidzie.

Aktualny obraz gościa Linux ARM64 przeszedł [13/13 sprawdzeń pod natywnym QEMU gospodarza](../artifacts/docker/arm-guest-host-clock-results.json): rzeczywisty build, podgląd HTTP, kontener non-root, limit pamięci, eksport, scoped cleanup i shutdown oraz wirtualne USB i odebranie przydziału. Gość używa 512 MiB RAM i zwykłego zegara gospodarza. Wynik zawiera manifest nowego obrazu z synchronizacją pierwszego importu. [Porównanie zawartości obu ABI](../artifacts/guest-bootstrap-durability.json) potwierdza zmianę wyłącznie `init.sh`. Ten zestaw nie jest testem Androida ARM64 ani fizycznego USB.

Regresje aplikacji obejmują metryki Androida, powłokę z UID aplikacji, timeout i procesy potomne, anulowanie, Unicode, trwałe pliki/rozmowy/szkice, renderer, oba motywy, konfigurację, prawdziwe Accessibility/MCP i sterowanie Androidowymi ekranami. Timeout terminala oczekuje teraz na zakończenie procesów po SIGKILL; test `/proc` rozpoznaje także proces znikający podczas odczytu. Fixture strumienia Claude nie zastępuje rozmowy z modelem. Zestaw Termux na API 30 sprawdza rzeczywiste oficjalne klienty i poprawne blokady bez logowania. Termux nie był testowany na API 36 w tym przebiegu. Pełny zestaw kontenerów przeszedł po 27/27 na API 30 i API 36; panel Dockera i jego regresje przeszły po 8/8 na obu wersjach.

## Naprawa WebView i powtórzenie pełnego Dockera na API 36

Wcześniejszy pełny zestaw wykonał 10 sprawdzeń, w tym rzeczywisty build obrazu, po czym otwarcie podglądu wywołało natywny `SIGTRAP` w systemowym WebView 133.0.6943.137. Następujący po nim ANR przerwał instrumentation. Tego przebiegu nie zaliczamy do ukończonych testów. Awarię zdiagnozowano na pakiecie sprzed zmiany synchronizacji pierwszego importu; podgląd i provider WebView pozostały bez zmian. [Zapis wcześniejszej próby](../artifacts/api36/webview-environment.json) zachowuje jej kontekst i odsyła do naprawy.

Błąd odtworzono również na `about:blank` w systemowym `org.chromium.webview_shell`, bez androidagent i bez Dockera. Ustalono przyczynę w software TCG emulatora 37.2.12: instrukcja `PHADDD xmm0, xmm0` dla `[1, 2, 3, 4]` dawała `[3, 7, 10, 14]`, zamiast `[3, 7, 3, 7]`. Uszkadzało to sumy Adler-32 obliczane przez SIMD Chromium, przez co provider odrzucał własny poprawny PNG oraz snapshot V8. Wyłączenie Vulkana, PCLMUL i zmiana zegara nie usuwały przyczyny; eksperymenty cofnięto.

Launcher przygotowuje osobny silnik z poprawioną funkcją TCG tylko dla dokładnie rozpoznanego SHA256. Oryginalny SDK, obraz Androida, systemowy WebView i APK 0.4.2 są bez zmian. Po naprawie 8192 wektory instrukcji oraz domyślny dekoder PNG Chromium dają poprawne wyniki. Wektory diagnostyczne nie powiększają liczby sprawdzeń aplikacji. [Opis i odtwarzanie poprawki](WEBVIEW.md) zawierają jej źródła i ograniczenia.

Końcowy APK przeszedł [8/8 osobnych testów WebView](../artifacts/api36/webview/results.json): HTML, JavaScript, PNG/canvas, storage po odświeżeniu, odtworzenie Activity, stronę błędu, powrót serwera oraz ponowne otwarcie. Sprawdzono także pełną widoczność i namalowanie natywnego tytułu oraz przycisków Wróć/Odśwież w [rzeczywistym zrzucie ekranu](../artifacts/api36/webview/preview.png). Nowy pełny przebieg na świeżym dysku przeszedł [27/27](../artifacts/api36/docker/results.json), w tym rzeczywisty build i interaktywny [podgląd kontenera](../artifacts/api36/docker/03-docker-web-preview.png), wirtualne USB, UART, restart i sprzątanie. Systemowy reproducer `about:blank` pozostał uruchomiony. Bufor awarii po obu ukończonych zestawach jest pusty. Nie zastosowano niestandardowych flag WebView ani roota dla aplikacji. [Raport rozwiązania](../artifacts/api36/webview-resolution.json) wiąże te wyniki z silnikiem, providerem i niezmienionym APK.

## Przerwany przebieg ART na API 30

Pierwsze pełne uruchomienie API 30 przerwał natywny `SIGTRAP/TRAP_TRACE` w `libart.so` podczas zwykłego `FileInputStream.read` w wątku listowania eksportów. Sześciu ukończonych sprawdzeń tego przebiegu nie wliczamy do wyniku końcowego. Odczytywane pliki są poprawnym JSON-em, nie było OOM, a biblioteki natywne aplikacji nie były załadowane. Instrukcja pod zapisanym PC nie jest pułapką ani assertion. Niezależny program na Androidzie wykonał 20 000 odczytów i parsowań tych samych plików bez błędu. Dokładna przyczyna wyjątku ART pozostaje nierozwiązana; nie ma potwierdzonego błędu produktu. [Zapis diagnostyczny](../artifacts/api30/art-runtime-interruption.json) zachowuje ten przypadek. Próba powtórzenia ujawniła też błąd przygotowania testu: publiczna lista usług jest pusta po awarii, mimo pozostawionego stanu „crashed”, więc szybkie wyłączenie i włączenie mogło zostać scalone przez system. Fixture czeka teraz na rzeczywiste wyczyszczenie pól Bound/Enabled/Binding/Crashed w stanie bieżącego użytkownika, a następnie na świeżą usługę i nasłuchujący MCP. Poprawka została potwierdzona przy rzeczywistym stanie „crashed”. Dalsza próba z `--reuse-cache` wykonała dziewięć sprawdzeń, lecz build zakończył się `exec /bin/sh: exec format error`. Kopia dysku, także po odtworzeniu dziennika ext4, zawierała pusty `busybox` w warstwie Dockera; oryginalny plik obrazu jest poprawnym ELF x86_64 (804616 bajtów), a baza VM na emulatorze ma sumę identyczną z APK. Zapis wcześniejszej awarii nie pozwala wskazać dokładnie utraconej operacji dyskowej. [Diagnostyka cache](../artifacts/api30/docker-cache-interruption.json) zachowuje wynik tej nieudanej próby. Nie zaliczamy jej do sumy i nie naprawiamy przez automatyczne kasowanie danych. Po tej diagnozie dodano synchronizację danych obrazu przed `images-ready` oraz synchronizację samego markera i przebudowano APK. Pełny zestaw przeszedł 27/27 na świeżym dysku. Nowy przypadek wymusił zatrzymanie po gotowości i ponownie uruchomił ten sam dysk; późniejszy rzeczywisty build RUN/COPY, uruchomienie kontenera i eksport wyniku również przeszły. Nagłe przerwanie VM nie gwarantuje trwałości niezapisanych danych; procedurę zachowania wyników i resetu opisuje [dokumentacja Dockera](DOCKER.md).

## Oczekiwanie na ekran Ustawień na API 36

Powtórzenie testu końcowego APK wykryło zbyt wczesne sprawdzenie drzewa Accessibility: Android wyświetlił Ustawienia po 1727 ms, a odziedziczony test odczytywał ekran po 1500 ms. Test czeka teraz na rzeczywisty ekran z limitem 5 s i zachowuje tę samą asercję. [Zapis diagnostyczny](../artifacts/api36/settings-test-timing.json) zawiera czas z ActivityTaskManager. Kod produktu pozostał bez zmian; powtórzony zestaw przeszedł 75/75. Po wyczyszczeniu danych aplikacji MediaStore nadawało nowym raportom nazwy z `(1)`, pozostawiając stare `results.json`. Kontrola identyfikatora przebiegu odrzuciła nieaktualny wynik. Przygotowanie testów usuwa teraz wyłącznie dedykowany katalog wyników przed nowym przebiegiem; powtórka potwierdziła prawidłowy eksport. Nieudana próba nie wchodzi do sumy końcowej.

## Regresje dodane w przeglądzie 0.4.1

- Opóźnione callbacki i Stop dotyczą konkretnego przebiegu; stare poświadczenia nie odzyskują ważności po ponownym uruchomieniu tej samej rozmowy, również po oczekiwaniu na blokadę zadania.
- Migracja historii zachowuje modele, wiadomości i załączniki. Retencja nie usuwa aktywnych rozmów, a zapis nie buduje ponownie JSON niezmienionych sesji; plik SharedPreferences nadal jest zapisywany w całości.
- Zamykanie serwera obejmuje aktywne i oczekujące sockety. Stop nie blokuje głównego wątku podczas zablokowanego sprzątania USB. Stare callbacki głosowe nie wznawiają mowy.
- UART ma ograniczenia wejścia, wyłączność i pełne przywracanie parametrów po anulowaniu ESP. Restart usługi zachowuje nowszą sesję, której start nadal oczekuje. Cofnięty grant SAF unieważnia strumienie także po ponownym przydziale, a zablokowany dostawca nie blokuje odebrania dostępu.
- Interfejs zachowuje tekst strumieniowanej odpowiedzi po powrocie, przewija wysokie panele, udostępnia stan przełączników i obsługuje drugą stronę katalogu z ponad 200 plikami. Log UART zachowuje tę samą treść przy bezczynnych odświeżeniach.
- Zrzuty `13-home-large-font.png` i `14-home-large-font-landscape.png` dokumentują skalę czcionki 1,5. W poziomie test sprawdza pełną widoczność przycisku wysłania, nagłówka oraz pierwszej karty działania; sama obecność widoku nie wystarcza.
- Cztery dodatkowe regresje Dockera odtwarzają spóźniony attach/rollback, kontrolę właściciela i puste ID sprzątania. Walidatory builda odrzucają niekompletne zasoby również przy `python -O`.

## Regresje dodane w przeglądzie 0.4.2

- API 36: rzeczywisty systemowy dialog powiadomień z odmową, działające narzędzia i usługa foreground po odmowie oraz brak ponownego pytania. Osobny przypadek USB potwierdza sprzątanie po odmowie `connectedDevice` przez system.
- Obie wersje Androida: zakończenie lokalnego stanu po niedostarczonym Stop, rozpoznanie pomocnika Termux pozostałego po restarcie aplikacji oraz zachowanie szkicu po odrzuconym starcie.
- Rzeczywisty opóźniony import załącznika trwa przez odtworzenie Activity, blokuje przedwczesne wysłanie i zachowuje plik. Sprawdzono też zaznaczenie tekstu, wprowadzanie przez `InputConnection`, długą nazwę modelu i 500 kroków narzędzi z ograniczoną liczbą widoków.
- Odbiornik USB działa poza głównym wątkiem. Testy socketów odróżniają nagły EOF legacy ADB od prawidłowego `CLSE`.
- Testy pomocnika obejmują niezerowy kod wyjścia bez stderr, zachowanie ID wznowienia, częściowe pobranie załącznika, katalog pod ścieżką docelową i symlink. Testy powłoki uruchamiają kontrolowane klienty fixture; nie zastępują zadania z modelem.

## Odtwarzanie

Przygotuj oba runtime'y QEMU, JNI USB i obrazy gościa według [USB.md](USB.md) i [DOCKER.md](DOCKER.md), a następnie:

```sh
bash tools/build-local.sh
export ANDROID_SERIAL=emulator-5554 # wybierz właściwy emulator
adb install -r artifacts/androidagent-0.4.2.apk
bash tools/test-android.sh
bash tools/test-usb.sh
bash tools/test-termux.sh
bash tools/test-docker.sh
bash tools/test-docker.sh --ui-only
bash tools/test-webview.sh
```

Ustaw `JAVA_HOME`, `ANDROID_SDK_ROOT` i `KEYSTORE` zgodnie z README. `ANDROID_SERIAL` wybiera emulator również przy kilku aktywnych urządzeniach. Zestaw USB oblicza port hostowego adbd z numeru emulatora; nietypowy port można podać w `ANDROIDAGENT_ADBD_PORT`. Na API 33+ zestaw aplikacji wymaga świeżej instalacji, aby sprawdzić systemowy dialog powiadomień. Przed powtórzeniem tego zestawu na przeznaczonym do testów emulatorze wyczyść dane androidagent przez `adb shell pm clear dev.mateuszsury.androidagent`; usuwa to jego rozmowy, ustawienia i zadania, więc nie wykonuj tego na urządzeniu z potrzebnymi danymi. Termux i klienty muszą być przygotowane na przeznaczonym do testów emulatorze. Testy tworzą i usuwają dane fixture, włączają Accessibility, instalują minimalny pakiet ADB oraz resetują dysk zadań VM. Testy hosta: `python3 -m unittest discover -s tests -p 'test_*.py' -v`, z zainstalowanym pyserial 3.5. Zegar emulowany nie daje miarodajnego porównania wydajności telefonu.

## Granice wydania

Nie zweryfikowano fizycznego telefonu ARM64, kabla/zasilania OTG, zgód dla rzeczywistego USB, zgodności adapterów CP210x/CH340/CDC, flashowania ESP, drugiego telefonu z AUTH ani nośników FAT32/exFAT. Nie zweryfikowano fizycznego USB przekazanego przez usbredirhost do kontenera. Macierz sprzętowa pozostaje do wykonania według [planu USB](USB_PLAN.md). Emulator i porty fixture nie certyfikują profili sprzętowych.

Brak zalogowanych modeli oznacza brak testu końcowego zadania z usługą Codexa/Claude. API 35 oraz fizyczne urządzenia Android z rozmiarem strony pamięci 16 KiB nie są potwierdzone tym przebiegiem. API 36 sprawdzono na emulatorze x86_64. GitHub Actions ma przygotowaną macierz API 30/35/36; wcześniejsze uruchomienia zatrzymały się przed jobami z powodu płatności/limitu konta, a nie błędu aplikacji. Podstawą wyników są lokalne raporty z emulatora.

Historia weryfikacji 0.4.0: [raport w poprzednim commicie](https://github.com/mateuszsury/androidagent/blob/710c05cda394adcabea1582ca33f700a3cec6bfd/docs/TESTING.md). Nie zastępuje testów aktualnego APK.

Historia weryfikacji 0.4.1: [raport poprzedniego wydania](https://github.com/mateuszsury/androidagent/blob/8a83d69412b265ede43f8316b41b9ff78ac02e69/docs/TESTING.md). Bieżąca runda rozszerza ten zakres o regresje 0.4.2 i osobny emulator API 36.
