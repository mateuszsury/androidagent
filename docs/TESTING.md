# Testy androidagent 0.2.0

Weryfikacja lokalna 10 października 2026. Użytkownik wybrał testy bez zalogowanego modelu.

## Potwierdzone wyniki

| Zestaw | Wynik | Dowód |
| --- | --- | --- |
| Instrumentacja aplikacji, Android 11 / API 30, x86_64 | **51/51** | `artifacts/emulator/results.json` |
| Integracja aplikacja → Termux → pomocnik → MCP | **7/7** | `artifacts/termux/results.json` |
| Kontrakty zdarzeń i argumenty launcherów Codex/Claude/Remote Control | **22/22** | `artifacts/host-tests.txt` |
| Kompilacja i podpis APK | Przeszły | `tools/build-local.sh` |
| Oficjalne klienty Codex 0.162.1 i Claude Code 2.1.296 w Termux, UID `com.termux` | Uruchomione bez roota | `artifacts/termux/cli.txt` |

Testy aplikacji wykonują prawdziwe operacje na emulatorze: odczyt baterii/RAM/dysku, powłoka z UID aplikacji, pliki UTF-8, kody wyjścia, limit wyniku, timeout i anulowanie wraz z procesami potomnymi. Sprawdzają również rozmowy i szkice po odtworzeniu Activity, wybór modelu, załączniki, kopiowanie kodu z zachowaniem wcięć, jasny/ciemny motyw i konfigurację. Nowe przypadki Claude używają rzeczywistych przycisków selektora agenta i modelu: wybierają Claude na ekranie głównym, tworzą rozmowę Claude, wybierają Sonnet, sprawdzają lokalne `/status` i blokadę bez logowania oraz potwierdzają, że przełączenie na Codex zachowuje osobne historie i modele.

Przez rzeczywisty serwer MCP potwierdzono odrzucenie żądania bez tokenu, inicjalizację, listę 17 narzędzi, diagnostykę, odczyt ekranu, kliknięcie, wpisanie polskich znaków, przewijanie, listę aplikacji, otwarcie Ustawień i odczyt ich ekranu, zrzut JPEG oraz nawigację systemową. Komenda jest blokowana bez trybu programisty. Przyciski **Odrzuć** i **Zatwierdź** w natywnej rozmowie rzeczywiście decydują o jej wykonaniu, a **Stop** odbiera sterowanie. Testy sprawdzają także odrzucanie traversal w dostawcy załączników i usunięcie zapisanej rozmowy.

Testowy tekst renderera jest jawnym fixture. `/status` pobiera rzeczywiste dane urządzenia bez zapytania do modelu. Testy kontraktu strumienia korzystają z kontrolowanych zdarzeń i nie potwierdzają rozmowy z usługą modelu. Przypadki Claude oznaczone jako `protocol fixture` sprawdzają rzeczywisty parser i natywny interfejs: tekst UTF-8 przed zakończeniem odpowiedzi, jedną zapisaną odpowiedź końcową, osobne delty subagentów, tablice błędów, deduplikację błędu logowania w obrębie jednego wywołania oraz zachowanie przerwanej odpowiedzi. Zrzut strumienia zawiera jawny napis `TEST STREAMU`.

Kod testowy znajduje się w osobnym APK, podpisanym tym samym kluczem co aplikacja. Nie jest częścią produktu. Android przyznaje uprawnienia Accessibility i RUN_COMMAND wyłącznie w ramach konfiguracji emulatora testowego; aplikacja na telefonie prowadzi użytkownika przez zwykłe zgody systemowe. Raport ma UUID bieżącego przebiegu, co zapobiega uznaniu starszego raportu za nowy sukces.

## Emulator i odtwarzanie

AVD `androidagent_api30`, system AOSP x86_64, ekran 720 × 1480, gęstość 300 dpi. Gospodarz nie udostępnia `/dev/kvm`, dlatego uruchomiono emulator w TCG, z wolniejszym zegarem gościa. Testy potwierdzają zachowanie funkcji; czasy raportu nie są miarą wydajności fizycznego telefonu.

```sh
./tools/start-software-emulator.sh androidagent_api30
adb install -r artifacts/androidagent-0.2.0.apk
./tools/test-android.sh
```

Wymagane zmienne SDK/JDK/KEYSTORE opisuje README. Raport i 12 zrzutów są eksportowane przez MediaStore, zgodnie ze scoped storage Androida, do `Download/androidagent-tests/` i kopiowane do `artifacts/emulator/`. Zrzuty pozostają lokalnymi artefaktami, raport JSON trafia do Git.

## Runtime Termux

Na tym samym emulatorze zainstalowano oficjalny debug APK Termux 0.118.3 (x86_64). Aplikacja Termux samodzielnie zainstalowała swój bootstrap. Dodatkowe pakiety Termux pobrano, sprawdzono ich SHA256 względem metadanych repozytorium i skopiowano do środowiska testowego przez `run-as`, bez roota. Jest to konfiguracja fixture testowego, a nie potwierdzenie całej interaktywnej instalacji przez `pkg`/npm.

Rzeczywisty Node `v24.18.0`, oficjalny Codex `0.162.1` oraz oficjalny Claude Code `2.1.296` uruchomiły się pod UID `com.termux`, przez wrappery z proot. Wrappery wykryły x86_64 i odnalazły oficjalne pakiety binarne npm. Claude używa musl 1.2.5-r12 pobranego z Alpine i sprawdzonego przez SHA256. Pakiet npm Claude sprawdzono przez SHA512 zgodnie z `dist.integrity`. Nie emulowano wyjścia tych klientów. Na urządzeniu ARM64 używana jest druga ścieżka wrappera, której ten przebieg nie sprawdza.

Dodatkowy zestaw `./tools/test-termux.sh` wymaga Termux z jq/curl, Codexem, Claude Code i włączonym `allow-external-apps`. Instaluje osobne APK testowe, przyznaje testową zgodę RUN_COMMAND, uruchamia instalację pomocnika zawartego w zasobach aplikacji i sprawdza odpowiedź na `ping`. Nie loguje konta modelu. Raport jest eksportowany do `Download/androidagent-runtime-tests/` i kopiowany do `artifacts/termux/`.

Wszystkie **7 testów** przeszły: RUN_COMMAND, ponowne połączenie Accessibility/MCP, instalacja pomocnika z APK, uwierzytelniony callback oraz wykrycie rzeczywistego braku logowania Claude. Następnie rzeczywisty `claude -p` połączył się z serwerem telefonu, wykrył **17 narzędzi MCP** i zakończył z błędem braku logowania. W zwykłym trybie nie udostępniał Bash/Edit. Drugie wywołanie wznowiło **ten sam identyfikator sesji Claude**, udostępniło Bash/Edit w trybie programisty i ponownie poprawnie zakończyło z błędem logowania. Błąd nie był pokazywany jako odpowiedź modelu, a rozmowa nie pozostała w stanie działania.

Testy runtime używają APK 0.2.0 i pomocnika w wersji 11. Suma kontrolna finalnego APK znajduje się w `artifacts/androidagent-0.2.0.sha256`.

## Granice weryfikacji

- Rozmowa i wykonanie zadania przez zalogowany model Codex/Claude, edycja plików przez model oraz Remote Control z kontem: nieprzetestowane, zgodnie z decyzją użytkownika o testowaniu bez logowania. Prawdziwy klient Claude/MCP i wznowienie sesji sprawdzono w stanie bez konta.
- Telefon ARM64, Android API 35/36 i ograniczenia producentów: nieprzetestowane. CI ma macierz API 30/35, lecz GitHub zablokował uruchomienie z powodu limitu wydatków/problemu z rozliczeniem konta. Brak uruchomionych zadań CI nie jest wynikiem testu aplikacji.
- Fizyczna kamera, rozpoznawanie mowy, TTS i niezawodność długich zadań w tle: nieprzetestowane. Otwarcie panelu załączników potwierdzono.
- Shizuku nie jest jeszcze zaimplementowane w wersji 0.2.0.
