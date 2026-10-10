# Testy androidagent 0.3.0

Weryfikacja lokalna 10 października 2026. Użytkownik wybrał testy bez zalogowanego modelu.

## Potwierdzone wyniki

| Zestaw | Wynik | Dowód |
| --- | --- | --- |
| Docker VM/MCP/build/HTTP/eksport/restart, API 30 | **21/21** | `artifacts/docker/results.json` |
| Docker UI: oba motywy, obrót, zatrzymanie rozruchu | **4/4** | `artifacts/docker-ui/results.json` |
| Linux ARM64 pod QEMU gospodarza, dwa profile zegara | **9/9 + 9/9** | `artifacts/docker/arm-guest*-results.json` |
| Instrumentacja aplikacji, Android 11 / API 30, x86_64 | **51/51** | `artifacts/emulator/results.json` |
| Integracja aplikacja → Termux → pomocnik → MCP | **7/7** | `artifacts/termux/results.json` |
| Kontrakty agentów oraz pliki i procesy kontrolera Docker VM | **31/31** | `artifacts/host-tests.txt` |
| Kompilacja i podpis APK | Przeszły | `tools/build-local.sh` |
| Oficjalne klienty Codex 0.162.1 i Claude Code 2.1.296 w Termux, UID `com.termux` | Uruchomione bez roota | `artifacts/termux/cli.txt` |

Testy aplikacji wykonują prawdziwe operacje na emulatorze: odczyt baterii/RAM/dysku, powłoka z UID aplikacji, pliki UTF-8, kody wyjścia, limit wyniku, timeout i anulowanie wraz z procesami potomnymi. Sprawdzają również rozmowy i szkice po odtworzeniu Activity, wybór modelu, załączniki, kopiowanie kodu z zachowaniem wcięć, jasny/ciemny motyw i konfigurację. Nowe przypadki Claude używają rzeczywistych przycisków selektora agenta i modelu: wybierają Claude na ekranie głównym, tworzą rozmowę Claude, wybierają Sonnet, sprawdzają lokalne `/status` i blokadę bez logowania oraz potwierdzają, że przełączenie na Codex zachowuje osobne historie i modele.

Przez rzeczywisty serwer MCP potwierdzono odrzucenie żądania bez tokenu, inicjalizację, listę 29 narzędzi, diagnostykę, odczyt ekranu, kliknięcie, wpisanie polskich znaków, przewijanie, listę aplikacji, otwarcie Ustawień i odczyt ich ekranu, zrzut JPEG oraz nawigację systemową. Komenda jest blokowana bez trybu programisty. Przyciski **Odrzuć** i **Zatwierdź** w natywnej rozmowie rzeczywiście decydują o jej wykonaniu, a **Stop** odbiera sterowanie. Testy sprawdzają także odrzucanie traversal w dostawcy załączników i usunięcie zapisanej rozmowy.

Testowy tekst renderera jest jawnym fixture. `/status` pobiera rzeczywiste dane urządzenia bez zapytania do modelu. Testy kontraktu strumienia korzystają z kontrolowanych zdarzeń i nie potwierdzają rozmowy z usługą modelu. Przypadki Claude oznaczone jako `protocol fixture` sprawdzają rzeczywisty parser i natywny interfejs: tekst UTF-8 przed zakończeniem odpowiedzi, jedną zapisaną odpowiedź końcową, osobne delty subagentów, tablice błędów, deduplikację błędu logowania w obrębie jednego wywołania oraz zachowanie przerwanej odpowiedzi. Zrzut strumienia zawiera jawny napis `TEST STREAMU`.

Kod testowy znajduje się w osobnym APK, podpisanym tym samym kluczem co aplikacja. Nie jest częścią produktu. Android przyznaje uprawnienia Accessibility i RUN_COMMAND wyłącznie w ramach konfiguracji emulatora testowego; aplikacja na telefonie prowadzi użytkownika przez zwykłe zgody systemowe. Raport ma UUID bieżącego przebiegu, co zapobiega uznaniu starszego raportu za nowy sukces.

## Emulator i odtwarzanie

AVD `androidagent_api30`, system AOSP x86_64, ekran 720 × 1480, gęstość 300 dpi. Gospodarz nie udostępnia `/dev/kvm`, dlatego uruchomiono emulator w TCG, z wolniejszym zegarem gościa. Testy potwierdzają zachowanie funkcji; czasy raportu nie są miarą wydajności fizycznego telefonu.

```sh
./tools/start-software-emulator.sh androidagent_api30
adb install -r artifacts/androidagent-0.3.0.apk
./tools/test-android.sh
```

Wymagane zmienne SDK/JDK/KEYSTORE opisuje README. Raport i 12 zrzutów są eksportowane przez MediaStore, zgodnie ze scoped storage Androida, do `Download/androidagent-tests/` i kopiowane do `artifacts/emulator/`. Zrzuty pozostają lokalnymi artefaktami, raport JSON trafia do Git.

## Runtime Termux

Na tym samym emulatorze zainstalowano oficjalny debug APK Termux 0.118.3 (x86_64). Aplikacja Termux samodzielnie zainstalowała swój bootstrap. Dodatkowe pakiety Termux pobrano, sprawdzono ich SHA256 względem metadanych repozytorium i skopiowano do środowiska testowego przez `run-as`, bez roota. Jest to konfiguracja fixture testowego, a nie potwierdzenie całej interaktywnej instalacji przez `pkg`/npm.

Rzeczywisty Node `v24.18.0`, oficjalny Codex `0.162.1` oraz oficjalny Claude Code `2.1.296` uruchomiły się pod UID `com.termux`, przez wrappery z proot. Wrappery wykryły x86_64 i odnalazły oficjalne pakiety binarne npm. Claude używa musl 1.2.5-r12 pobranego z Alpine i sprawdzonego przez SHA256. Pakiet npm Claude sprawdzono przez SHA512 zgodnie z `dist.integrity`. Nie emulowano wyjścia tych klientów. Na urządzeniu ARM64 używana jest druga ścieżka wrappera, której ten przebieg nie sprawdza.

Dodatkowy zestaw `./tools/test-termux.sh` wymaga Termux z jq/curl, Codexem, Claude Code i włączonym `allow-external-apps`. Instaluje osobne APK testowe, przyznaje testową zgodę RUN_COMMAND, uruchamia instalację pomocnika zawartego w zasobach aplikacji i sprawdza odpowiedź na `ping`. Nie loguje konta modelu. Raport jest eksportowany do `Download/androidagent-runtime-tests/` i kopiowany do `artifacts/termux/`.

Wszystkie **7 testów** przeszły: RUN_COMMAND, ponowne połączenie Accessibility/MCP, instalacja pomocnika z APK, uwierzytelniony callback oraz wykrycie rzeczywistego braku logowania Claude. Następnie rzeczywisty `claude -p` połączył się z serwerem telefonu, wykrył **29 narzędzi MCP** i zakończył z błędem braku logowania. W zwykłym trybie nie udostępniał Bash/Edit. Drugie wywołanie wznowiło **ten sam identyfikator sesji Claude**, udostępniło Bash/Edit w trybie programisty i ponownie poprawnie zakończyło z błędem logowania. Błąd nie był pokazywany jako odpowiedź modelu, a rozmowa nie pozostała w stanie działania.

Testy runtime używają APK 0.3.0 i pomocnika w wersji 12. Suma kontrolna finalnego APK znajduje się w `artifacts/androidagent-0.3.0.sha256`.

## Docker

Na gospodarzu przeszło **31/31** testów: 22 kontraktu agentów oraz 9 rzeczywistych kontroli plików i procesów kontrolera VM. Gość Linux ARM64 przeszedł **9/9** przypadków ze zwykłym zegarem oraz **9/9** z zegarem opartym na instrukcjach. Użyto obrazu dołączonego do APK, Docker Engine 29.5.2 i `overlay2`: rzeczywisty Dockerfile `RUN`, kontener z `USER 1000`, zachowane `WORKDIR`, limit pamięci, podgląd HTTP, zapis i eksport wyniku, sprzątanie oraz wyłączenie. Dowody: `artifacts/docker/arm-guest-results.json` i `arm-guest-host-clock-results.json`. To QEMU na gospodarzu Linux, a nie telefon Android ARM64.

Zestaw Androida dla Docker 0.3.0 przeszedł **21/21** kontroli na świeżym dysku. Sprawdzono prawdziwy QEMU z APK, MCP, Dockerfile RUN/COPY, HTTP i JavaScript we wbudowanym podglądzie, wynik przez dostawcę Androida, kontener bez UID 0, anulowanie, pozostawienie drugiego kontenera po sprzątaniu, restart, globalny Stop i reset. Nie korzysta z atrapy silnika ani zalogowanego modelu. Raport: `artifacts/docker/results.json`. SHA256 APK, kodu integracji i zasobów zapisano w `artifacts/docker/runtime-input.json`. Osobny przebieg interfejsu przeszedł **4/4**: czytelny wybór RAM i faktycznie wyrenderowana ikona powrotu w obu motywach, zachowanie wyboru po odtworzeniu Activity oraz zatrzymanie prawdziwego procesu QEMU w czasie rozruchu. Dowód: `artifacts/docker-ui/results.json`.

## Granice weryfikacji

- Rozmowa i wykonanie zadania przez zalogowany model Codex/Claude, edycja plików przez model oraz Remote Control z kontem: nieprzetestowane, zgodnie z decyzją użytkownika o testowaniu bez logowania. Prawdziwy klient Claude/MCP i wznowienie sesji sprawdzono w stanie bez konta.
- Telefon ARM64, Android API 35/36 i ograniczenia producentów: nieprzetestowane. CI ma macierz API 30/35, lecz GitHub zablokował uruchomienie z powodu limitu wydatków/problemu z rozliczeniem konta. Brak uruchomionych zadań CI nie jest wynikiem testu aplikacji.
- Fizyczna kamera, rozpoznawanie mowy, TTS i niezawodność długich zadań w tle: nieprzetestowane. Otwarcie panelu załączników potwierdzono.
- Shizuku nie jest jeszcze zaimplementowane w wersji 0.3.0.
