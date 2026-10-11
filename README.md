# androidagent

Natywna aplikacja Android do pracy z Codexem i Claude Code na telefonie: rozmowy, narzędzia telefonu i lokalne komendy. Wersja **0.4.1**, lokalny fork [Buddy](https://github.com/ghorbelhamdi/buddy-android), Apache-2.0. Niezależny projekt, niezwiązany z OpenAI ani Anthropic.

Zmiany stabilności i interfejsu: [przegląd 0.4.1](docs/REVIEW_0.4.1.md). Wyniki oraz zakres weryfikacji: [raport testów](docs/TESTING.md).

## Uruchomienie

1. Zainstaluj `artifacts/androidagent-0.4.1.apk` na 64-bitowym Androidzie 11 lub nowszym (ARM64 albo x86_64).
2. Panel **Sprawdź telefon** i **Uruchom komendę** działają od razu. `/status` w rozmowie również działa bez modelu.
3. Dotknij selektora agenta u góry i wybierz **Codex** albo **Claude Code**. **Konfiguruj agentów** prowadzi przez Termux, zgodę `RUN_COMMAND`, instalację pomocnika i wybranych klientów. Accessibility jest opcjonalne i służy do sterowania ekranem. Można zainstalować obu agentów.
4. Zaloguj się przez oficjalny klient: `~/bin/codex login --device-auth` lub `~/bin/claude auth login`. Dane logowania pozostają w Termux. Modele wymagają internetu.
5. Wróć do androidagent i rozpocznij rozmowę. Sam USB/Docker może korzystać z opcji **Włącz dostęp agentów** w panelu USB; sterowanie ekranem wymaga Accessibility. W ustawieniach możesz włączyć tryb programisty i pływający przycisk. **Stop** odbiera agentowi sterowanie.

Termux jest potrzebny do uruchamiania Codex CLI i Claude Code. Nie jest potrzebny do podstawowej diagnostyki i terminala aplikacji. Telefon nie wymaga roota, komputera ani ADB do codziennego działania. Natywny profil USB ADB służy do pracy z podłączonym drugim telefonem; ADB gospodarza służy również do testów projektu. Shizuku nie jest jeszcze zintegrowane z aplikacją.

## Funkcje

- Ekran rozmowy inspirowany mobilnym ChatGPT, jasny i ciemny motyw, historia, wybór modelu, szkice.
- Prawdziwe metryki baterii, pamięci RAM, miejsca na dysku i sieci.
- Terminal Android w katalogu aplikacji: kod wyjścia, limit czasu 10 s, zatrzymanie, ograniczenie wyniku do 32 KB.
- Narzędzia MCP: `device_status`, `run_command` oraz odziedziczone od Buddy odczyt ekranu, tapnięcia, długie naciśnięcie, tekst, przewijanie, gesty, nawigacja systemowa, aplikacje, URL, zrzut ekranu, potwierdzenia i powiadomienia.
- Codex CLI i Claude Code w Termux: wybór domyślnego agenta, osobne historie i sesje, wznowienie, postęp narzędzi, wybór modelu i obsługa błędów.
- Claude Code: odpowiedzi na żywo przez `stream-json`, modele Default/Sonnet/Opus/Haiku i własny identyfikator, narzędzia kodowania w trybie programisty oraz przekazanie sesji do oficjalnego Remote Control. Dostępność modeli i Remote Control zależy od konta i klienta. [Instrukcja Claude Code](docs/CLAUDE.md).
- Lokalny Docker bez roota: Linux VM w APK, zadania, edycja plików, Dockerfile, kontenery, podgląd WWW, eksport wyników i sprzątanie. Te same 12 narzędzi MCP dla Codexa i Claude. [Instrukcja i wymagania](docs/DOCKER.md).
- USB Host: natywny terminal UART, flasher ESP, ADB shell/logcat/pliki/APK, katalogi SAF oraz przekazywanie USB do QEMU/Dockera. [Użycie i granice testów](docs/USB.md).
- Załączniki, zdjęcia, dyktowanie i odczyt odpowiedzi odziedziczone z Buddy; dostępność głosu zależy od zainstalowanego silnika Androida.
- Tekst zaznaczalny, pogrubienie, kod w tekście i bloki kodu z kopiowaniem.

## Granice dostępu

Moduł [USB](docs/USB.md) obejmuje bezpośredni UART/ESP, ADB do drugiego Androida oraz katalogi nośników przez SAF. Docker jest opcjonalny; obsługuje osobny przydział surowego USB do kontenera oraz most UART. Zgodność fizycznych urządzeń OTG wymaga testów sprzętowych.

| Warstwa | Dostęp |
| --- | --- |
| androidagent / lokalny terminal | UID aplikacji, własny katalog `files/workspace`; bez roota i bez automatycznego dostępu do prywatnych danych innych aplikacji |
| Accessibility | Ekran i gesty po świadomym włączeniu usługi przez właściciela telefonu |
| Termux / agenci | Zwykły tryb: powłoka Codexa wyłączona; Claude ma WebSearch/WebFetch/Read z dostępem do załączników oraz MCP telefonu. Tryb programisty: komendy i pliki dostępne dla UID Termux, z katalogiem startowym `~/.buddy/work` |
| Docker | Oddzielny Linux VM i kontener z katalogiem wyłącznie swojego zadania; bez katalogów telefonu i kont agentów |
| USB | Wybrane urządzenie po zgodzie Androida i przydziale do aktywnej rozmowy; osobne katalogi SAF z odczytem lub zapisem |
| Shizuku | Opcja następnego etapu, nieimplementowana w 0.4.1 |
| Model | Usługa zewnętrzna; lokalnie działają klienci Codex/Claude i narzędzia, nie wagi modelu |

Nowe narzędzie MCP `run_command` wymaga trybu programisty i zatwierdzenia dokładnej komendy. Terminal otwierany przez użytkownika wykonuje komendę po naciśnięciu przycisku. Odziedziczone narzędzia gestów opierają zgodę na instrukcjach agenta; nie jest to twarda blokada wszystkich możliwych operacji w innych aplikacjach. Ekrany chronione, ekran blokady i ograniczenia Androida nie są obchodzone.

Codex w trybie programisty używa `--sandbox danger-full-access` wewnątrz Termux: linuksowy sandbox desktopowy nie jest wspierany przez tę integrację na Androidzie. Komendy Codexa mają dostęp do całego środowiska Termux i wykonują się bez osobnych kart zgody. Android nadal ogranicza je do UID i nadanych uprawnień Termux. Ten dostęp jest osobny od terminala androidagent i jego narzędzia MCP `run_command`.

Claude Code w trybie programisty ma zatwierdzone narzędzia Bash/Read/Write/Edit/Glob/Grep/Skill/Agent i pokrewne narzędzia kodowania. Działają z uprawnieniami Termux, bez osobnej karty zgody na każdą operację. W zwykłym trybie narzędzia powłoki i zmiany plików są usunięte przez `--tools`. Pozostałe żądania uprawnień w trybie nieinteraktywnym są odrzucane; aplikacja nie wyłącza wszystkich kontroli uprawnień Claude. MCP `run_command` nadal wymaga osobnego zatwierdzenia.

MCP słucha wyłącznie na `127.0.0.1:8766` i wymaga losowego tokenu instalacji lub konkretnego przebiegu rozmowy. Upstream Buddy używa innego portu. Tokeny nie są zapisywane w repozytorium ani raportach testów. USB i Docker mogą korzystać z tego samego serwera bez Accessibility, po włączeniu dostępu agentów w panelu USB. Aktywne rozmowy mają osobne poświadczenia i przydziały urządzeń.

## Kompilacja bez Gradle

Najpierw przygotuj USB/JNI według [docs/USB.md](docs/USB.md) i oba środowiska Docker według [docs/DOCKER.md](docs/DOCKER.md); pełny APK zawiera binaria i obrazy generowane poza Git.

Wymagane: JDK 21, Python 3.9+, Android SDK `platforms;android-36`, `build-tools;35.0.0`, `platform-tools`.

```sh
export ANDROID_SDK_ROOT=/ścieżka/do/android-sdk
export JAVA_HOME=/ścieżka/do/jdk-21
./tools/build-local.sh
```

Powstaje `artifacts/androidagent-0.4.1.apk`. Skrypt tworzy lokalny klucz deweloperski w `~/.androidagent-keys`; zachowaj go do instalowania aktualizacji. Kluczy nie dodajemy do Git. Alternatywna konfiguracja Android Studio/Gradle pochodzi z upstream; zweryfikowana ścieżka tej iteracji to `aapt2 → javac → d8 → apksigner`.

## Testy na urządzeniu / emulatorze

```sh
export KEYSTORE="$HOME/.androidagent-keys/development.jks"
adb install -r artifacts/androidagent-0.4.1.apk
./tools/test-android.sh
```

Testy mają osobne APK, podpisane tym samym kluczem co aplikacja. Uruchamiaj wyłącznie na przeznaczonym do testów emulatorze: włączają Accessibility, tworzą pliki i rozmowy testowe oraz otwierają Ustawienia. Testowy APK eksportuje raport i zrzuty przez MediaStore do `Download/androidagent-tests/`; skrypt kopiuje je do `artifacts/emulator/`. Raport musi mieć identyfikator bieżącego przebiegu, aby starszy wynik nie został uznany za nowy. Wyniki bieżącego wydania, testy USB i granice weryfikacji zawiera [raport testów](docs/TESTING.md). Oficjalny Codex **0.162.1** i Claude Code **2.1.296** uruchomiono w Termux na emulatorze. Szczegóły oraz granice weryfikacji opisuje [raport testów](docs/TESTING.md). Bez KVM można uruchomić istniejący AVD przez `./tools/start-software-emulator.sh nazwa-avd`; emulowany zegar jest wolniejszy od zegara gospodarza, więc wyniki nie są pomiarem wydajności telefonu.

## Pochodzenie i kierunek

Porównanie projektów i decyzje: [architektura](docs/ARCHITECTURE.md). Oryginalna instrukcja Buddy: [UPSTREAM-README](docs/UPSTREAM-README.md). Zachowano licencję oraz [NOTICE](NOTICE). Wewnętrzne nazwy `buddy-run` i `~/.buddy` pozostają dla małego zakresu zmian względem upstream; uruchamianie jednocześnie obu helperów wymaga osobnego profilu Termux.
