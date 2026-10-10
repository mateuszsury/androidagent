# androidagent

Natywna aplikacja Android do pracy z Codexem na telefonie: rozmowy, narzędzia telefonu i lokalne komendy. Wersja **0.1.0**, lokalny fork [Buddy](https://github.com/ghorbelhamdi/buddy-android), Apache-2.0. Niezależny projekt, niezwiązany z OpenAI.

## Uruchomienie

1. Zainstaluj `artifacts/androidagent-0.1.0.apk` na Androidzie 11 lub nowszym.
2. Panel **Sprawdź telefon** i **Uruchom komendę** działają od razu. `/status` w rozmowie również działa bez modelu.
3. Wybierz **Połącz Codexa**. Przewodnik prowadzi przez instalację Termux, włączenie usługi Accessibility, zgodę `RUN_COMMAND`, instalację pomocnika i Codexa oraz logowanie `codex login --device-auth`.
4. Zaloguj się swoim kontem uprawnionym do Codexa. Dane logowania pozostają w Termux. Model wymaga internetu.
5. Wróć do androidagent i rozpocznij rozmowę. W ustawieniach możesz włączyć tryb programisty i pływający przycisk. **Stop** odbiera agentowi sterowanie.

Termux jest potrzebny do uruchamiania Codex CLI. Nie jest potrzebny do podstawowej diagnostyki i terminala aplikacji. Telefon nie wymaga roota, komputera ani ADB do codziennego działania. ADB służy w tym repozytorium do testów. Shizuku nie jest jeszcze zintegrowane z aplikacją.

## Funkcje

- Ekran rozmowy inspirowany mobilnym ChatGPT, jasny i ciemny motyw, historia, wybór modelu, szkice.
- Prawdziwe metryki baterii, pamięci RAM, miejsca na dysku i sieci.
- Terminal Android w katalogu aplikacji: kod wyjścia, limit czasu 10 s, zatrzymanie, ograniczenie wyniku do 32 KB.
- Narzędzia MCP: `device_status`, `run_command` oraz odziedziczone od Buddy odczyt ekranu, tapnięcia, długie naciśnięcie, tekst, przewijanie, gesty, nawigacja systemowa, aplikacje, URL, zrzut ekranu, potwierdzenia i powiadomienia.
- Codex CLI w Termux: osobne sesje, wznowienie, postęp narzędzi, wybór modelu, obsługa błędów. Modele są ustalane przez konto i wersję CLI.
- Załączniki, zdjęcia, dyktowanie i odczyt odpowiedzi odziedziczone z Buddy; dostępność głosu zależy od zainstalowanego silnika Androida.
- Tekst zaznaczalny, pogrubienie, kod w tekście i bloki kodu z kopiowaniem.

## Granice dostępu

| Warstwa | Dostęp |
| --- | --- |
| androidagent / lokalny terminal | UID aplikacji, własny katalog `files/workspace`; bez roota i bez automatycznego dostępu do prywatnych danych innych aplikacji |
| Accessibility | Ekran i gesty po świadomym włączeniu usługi przez właściciela telefonu |
| Termux / Codex | Zwykły tryb: powłoka Codexa wyłączona. Tryb programisty: komendy i pliki dostępne dla UID Termux, z katalogiem startowym `~/.buddy/work` |
| Shizuku | Opcja następnego etapu, nieimplementowana w 0.1.0 |
| Model | Usługa zewnętrzna; lokalnie działa klient Codex i narzędzia, nie wagi modelu |

Nowe narzędzie MCP `run_command` wymaga trybu programisty i zatwierdzenia dokładnej komendy. Terminal otwierany przez użytkownika wykonuje komendę po naciśnięciu przycisku. Odziedziczone narzędzia gestów opierają zgodę na instrukcjach agenta; nie jest to twarda blokada wszystkich możliwych operacji w innych aplikacjach. Ekrany chronione, ekran blokady i ograniczenia Androida nie są obchodzone.

Codex w trybie programisty używa `--sandbox danger-full-access` wewnątrz Termux: linuksowy sandbox desktopowy nie jest wspierany przez tę integrację na Androidzie. Komendy Codexa mają dostęp do całego środowiska Termux i wykonują się bez osobnych kart zgody. Android nadal ogranicza je do UID i nadanych uprawnień Termux. Ten dostęp jest osobny od terminala androidagent i jego narzędzia MCP `run_command`.

MCP słucha wyłącznie na `127.0.0.1:8766` i wymaga losowego tokenu instalacji. Upstream Buddy używa innego portu. Token nie jest zapisywany w repozytorium ani raportach testów. Wyłączenie Accessibility zatrzymuje serwer.

## Kompilacja bez Gradle

Wymagane: JDK 21, Python 3, Android SDK `platforms;android-36`, `build-tools;35.0.0`, `platform-tools`.

```sh
export ANDROID_SDK_ROOT=/ścieżka/do/android-sdk
export JAVA_HOME=/ścieżka/do/jdk-21
./tools/build-local.sh
```

Powstaje `artifacts/androidagent-0.1.0.apk`. Skrypt tworzy lokalny klucz deweloperski w `~/.androidagent-keys`; zachowaj go do instalowania aktualizacji. Kluczy nie dodajemy do Git. Alternatywna konfiguracja Android Studio/Gradle pochodzi z upstream; zweryfikowana ścieżka tej iteracji to `aapt2 → javac → d8 → apksigner`.

## Testy na urządzeniu / emulatorze

```sh
export KEYSTORE="$HOME/.androidagent-keys/development.jks"
adb install -r artifacts/androidagent-0.1.0.apk
./tools/test-android.sh
```

Testy mają osobne APK, podpisane tym samym kluczem co aplikacja. Uruchamiaj wyłącznie na przeznaczonym do testów emulatorze: włączają Accessibility, tworzą pliki i rozmowy testowe oraz otwierają Ustawienia. Testowy APK eksportuje raport i zrzuty przez MediaStore do `Download/androidagent-tests/`; skrypt kopiuje je do `artifacts/emulator/`. Raport musi mieć identyfikator bieżącego przebiegu, aby starszy wynik nie został uznany za nowy. Potwierdzono **41/41** testów aplikacji na API 30, **4/4** integracji Termux i **11/11** kontraktu protokołu. Oficjalny Codex **0.162.1** uruchomiono w Termux na emulatorze. Szczegóły oraz granice weryfikacji opisuje [raport testów](docs/TESTING.md). Bez KVM można uruchomić istniejący AVD przez `./tools/start-software-emulator.sh nazwa-avd`; emulowany zegar jest wolniejszy od zegara gospodarza, więc wyniki nie są pomiarem wydajności telefonu.

## Pochodzenie i kierunek

Porównanie projektów i decyzje: [architektura](docs/ARCHITECTURE.md). Oryginalna instrukcja Buddy: [UPSTREAM-README](docs/UPSTREAM-README.md). Zachowano licencję oraz [NOTICE](NOTICE). Wewnętrzne nazwy `buddy-run` i `~/.buddy` pozostają dla małego zakresu zmian względem upstream; uruchamianie jednocześnie obu helperów wymaga osobnego profilu Termux.
