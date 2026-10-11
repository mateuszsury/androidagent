# Testy androidagent 0.4.2

Weryfikacja po [dodatkowym przeglądzie](REVIEW_0.4.2.md), bez logowania do modeli. Końcowy APK i obraz Linux ARM64 mają **357/357 zaliczonych sprawdzeń w ukończonych zestawach**. [Indeks wydania](../artifacts/release-0.4.2.json) wiąże wyniki z sumą konkretnego APK i identyfikatorami przebiegów. Przerwane i nieudane wcześniejsze próby opisano osobno poniżej; nie wchodzą do tej sumy.

| Zestaw | Android 11 / API 30 | Android 16 / API 36 |
| --- | ---: | ---: |
| Aplikacja i MCP | [74/74](../artifacts/emulator/results.json) | [75/75](../artifacts/api36/emulator/results.json) |
| USB | [40/40](../artifacts/usb/results.json) | [41/41](../artifacts/api36/usb/results.json) |
| Termux i oficjalne klienty | [7/7](../artifacts/termux/results.json) | Nie testowano |
| Panel Dockera i regresje | [8/8](../artifacts/docker-ui/results.json) | [8/8](../artifacts/api36/docker-ui/results.json) |
| Pełny Docker, USB i UART | [27/27](../artifacts/docker/results.json) | Blokada systemowego WebView |

Poza Androidem: [64/64 testy hosta](../artifacts/host-tests.txt) oraz [13/13 dla aktualnego Linuxa ARM64 pod QEMU gospodarza](../artifacts/docker/arm-guest-host-clock-results.json). Suma obejmuje wykonania tych samych scenariuszy na różnych platformach; nie jest liczbą unikalnych funkcji.

## Zakres USB

Dwa emulatory AOSP x86_64: Android 11/API 30 z 4 GiB RAM oraz Android 16/API 36 z 2 GiB RAM. Wcześniejsze próby przed poprawką trwałości używały 2 GiB na API 30 dla krótkich zestawów, 4 GiB dla pełnego Dockera oraz 4 GiB na API 36. Oba mają rozdzielczość 720 × 1480, 300 dpi i działają bez KVM. Testy mają osobny APK i nie są pakowane w produkt. Wyniki zawierają identyfikator bieżącego przebiegu; skrypty odrzucają stare raporty.

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

Regresje aplikacji obejmują metryki Androida, powłokę z UID aplikacji, timeout i procesy potomne, anulowanie, Unicode, trwałe pliki/rozmowy/szkice, renderer, oba motywy, konfigurację, prawdziwe Accessibility/MCP i sterowanie Androidowymi ekranami. Timeout terminala oczekuje teraz na zakończenie procesów po SIGKILL; test `/proc` rozpoznaje także proces znikający podczas odczytu. Fixture strumienia Claude nie zastępuje rozmowy z modelem. Zestaw Termux na API 30 sprawdza rzeczywiste oficjalne klienty i poprawne blokady bez logowania. Termux nie był testowany na API 36 w tym przebiegu. Pełny zestaw kontenerów przeszedł 27/27 na API 30; panel Dockera i jego regresje przeszły po 8/8 na obu wersjach.

## Przerwany przebieg Docker na API 36

Pełny zestaw wykonał 10 sprawdzeń, w tym rzeczywisty build obrazu, po czym otwarcie podglądu wywołało natywny `SIGTRAP` w systemowym WebView 133.0.6943.137. Następujący po nim ANR przerwał instrumentation. Tego przebiegu nie zaliczamy do ukończonych testów.

Ten sam błąd odtworzono na `about:blank` w systemowym `org.chromium.webview_shell`, bez androidagent i bez Dockera; wyłączenie Vulkana nie pomogło. Ślad prowadzi do `ResourceBundle::LoadBitmap`: dekoder providera odrzuca własny PNG 16×16, którego CRC i dekodowanie na hoście są poprawne. To potwierdza problem środowiska WebView, lecz dokładna przyczyna w dekoderze pozostaje nierozwiązana. Nie dodano obejścia w produkcie ani pominięcia testu. [Zapis diagnostyczny](../artifacts/api36/webview-environment.json) oddziela ten wynik od ukończonych zestawów. Podgląd i pełny cykl Dockera na API 36 pozostają niepotwierdzone. Awarię zdiagnozowano na pakiecie sprzed zmiany synchronizacji pierwszego importu; podgląd i provider WebView pozostały bez zmian. Pełny cykl Dockera aktualnego APK przeszedł na API 30.

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
```

Ustaw `JAVA_HOME`, `ANDROID_SDK_ROOT` i `KEYSTORE` zgodnie z README. `ANDROID_SERIAL` wybiera emulator również przy kilku aktywnych urządzeniach. Zestaw USB oblicza port hostowego adbd z numeru emulatora; nietypowy port można podać w `ANDROIDAGENT_ADBD_PORT`. Na API 33+ zestaw aplikacji wymaga świeżej instalacji, aby sprawdzić systemowy dialog powiadomień. Przed powtórzeniem tego zestawu na przeznaczonym do testów emulatorze wyczyść dane androidagent przez `adb shell pm clear dev.mateuszsury.androidagent`; usuwa to jego rozmowy, ustawienia i zadania, więc nie wykonuj tego na urządzeniu z potrzebnymi danymi. Termux i klienty muszą być przygotowane na przeznaczonym do testów emulatorze. Testy tworzą i usuwają dane fixture, włączają Accessibility, instalują minimalny pakiet ADB oraz resetują dysk zadań VM. Testy hosta: `python3 -m unittest discover -s tests -p 'test_*.py' -v`, z zainstalowanym pyserial 3.5. Zegar emulowany nie daje miarodajnego porównania wydajności telefonu.

## Granice wydania

Nie zweryfikowano fizycznego telefonu ARM64, kabla/zasilania OTG, zgód dla rzeczywistego USB, zgodności adapterów CP210x/CH340/CDC, flashowania ESP, drugiego telefonu z AUTH ani nośników FAT32/exFAT. Nie zweryfikowano fizycznego USB przekazanego przez usbredirhost do kontenera. Macierz sprzętowa pozostaje do wykonania według [planu USB](USB_PLAN.md). Emulator i porty fixture nie certyfikują profili sprzętowych.

Brak zalogowanych modeli oznacza brak testu końcowego zadania z usługą Codexa/Claude. API 35 oraz fizyczne urządzenia Android z rozmiarem strony pamięci 16 KiB nie są potwierdzone tym przebiegiem. API 36 sprawdzono na emulatorze x86_64. GitHub Actions ma przygotowaną macierz API 30/35/36; wcześniejsze uruchomienia zatrzymały się przed jobami z powodu płatności/limitu konta, a nie błędu aplikacji. Podstawą wyników są lokalne raporty z emulatora.

Historia weryfikacji 0.4.0: [raport w poprzednim commicie](https://github.com/mateuszsury/androidagent/blob/710c05cda394adcabea1582ca33f700a3cec6bfd/docs/TESTING.md). Nie zastępuje testów aktualnego APK.

Historia weryfikacji 0.4.1: [raport poprzedniego wydania](https://github.com/mateuszsury/androidagent/blob/8a83d69412b265ede43f8316b41b9ff78ac02e69/docs/TESTING.md). Bieżąca runda rozszerza ten zakres o regresje 0.4.2 i osobny emulator API 36.
