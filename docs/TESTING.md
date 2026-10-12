# Testy androidagent 0.4.4

Wydanie 0.4.4 (`versionCode=404`, runtime Docker revision 3, `targetSdk=36`) poprawia nadzór rozruchu na wolnym ARM/TCG. Baza i kernel pozostają identyczne z 0.4.2 i 0.4.3; aktualizacja nie resetuje dysku, rozmów ani HOME.

## Rzeczywisty wynik fizycznej 0.4.3

Lenovo TB336FU, Android 16/API36, ARM64, strony 4 KiB: dziewięć kontroli UID, narzędzi i pierwszych uruchomień Codex 0.162.1 / Claude Code 2.1.296 przeszło. Sesja lokalna zgłosiła także rzeczywiste pobrania HTTPS; zapis JSON dziewięciu kontroli sam nie dokumentuje transportu pobrania. Docker z 1 GB RAM nie osiągnął gotowości. Containerd Version RPC był gotowy po 71,756 s; po rozpoczęciu etapu Dockera po 72,209 s nadzór zakończył go po 194,623 s z błędem limitu 120 s. Log z tego urządzenia ma 32044 bajty i SHA256 `4920d8d3acbb43990be42d13ac41ff0d162fd9ff3811fead4d25245bd4d7d9c6`.

Komunikat `xtables contention` dla udanej operacji trwającej 3,94 s nie dowodzi blokady. TERM oraz utrata połączeń containerd nastąpiły po błędzie nadzoru. Neutralny wpis Androida o śmierci procesu nie identyfikuje przyczyny zakończenia. Bieżący pomiar baterii 6%, wyłączonego Battery Saver i Thermal Status 0 nie opisuje stanu historycznego podczas nieudanego bootu. Nie zmieniamy globalnych zabezpieczeń Androida.

[Analiza](../artifacts/0.4.4/lenovo-0.4.3-startup-analysis.json), [log](../artifacts/0.4.4/lenovo-0.4.3-startup.log) i [raport urządzenia](../artifacts/0.4.4/lenovo-0.4.3-device-results.json) zachowują faktyczny wynik. Oryginalne assety są w [wydaniu v0.4.3](https://github.com/mateuszsury/androidagent/releases/tag/v0.4.3).

## Weryfikacja nowej wersji

Podpisana APK ma **653558148 bajtów**, SHA256 `c939d53f01be01eb32be96b306c7cdf4bc97854b69de7c994d13af689bd37e8a`, pakiet `dev.mateuszsury.androidagent`, versionCode **404**. Certyfikat aktualizacji SHA256 `b0fe60ab6fb41c33051f1fe9c16b2d24e476fdf66be225870db831cd7e2fb66b` jest zgodny z poprzednimi wydaniami. Kod produktu pochodzi z `bc73465374bf6b5df91ec00726d1ff58d38b9567`; późniejszy `d514f3883f76b976fff1f0a542428eafbf195bc2` zmienia wyłącznie diagnostykę testów i narzędzie transferu, bez przebudowy produktu.

[Indeks wydania](../artifacts/release-0.4.4.json) wiąże każdą serię testów z UUID, SHA256 raportu, dokładną APK, osobną APK instrumentacji oraz czasem hosta. Instrumentacja V1 ma SHA256 `b774e8a0b1b9f7db2aa94d877c74258d2145f2e9c591b572f8e5baeb65958c22`, V2 `817a88ede5890440ed370613bf40015d7da42ae807fec32e93aed3959c828442`; nie są częścią wydania produktu.

| Zestaw | Wynik | Zakres |
| --- | --- | --- |
| Host Python | 117/117, bez pominięć | Regresje runtime, protokołów, terminów i sprzątania procesów |
| Transfer connectora | 8/8 | Osobne testy offline rozmiaru JSON, journala i wznowienia GET |
| ARM Linux / QEMU TCG | 45/45 | Faktyczny Docker Engine, wolny rozruch, migracja i pełny cykl zadań |
| API30 aplikacja / migracja / USB / Docker UI / WebView | 145/145 | 83 + 4 + 40 + 10 + 8 |
| API36 aplikacja / migracja / USB / Docker UI / WebView | 147/147 | 84 + 4 + 41 + 10 + 8 |
| Aktualizacja 0.4.3 → 0.4.4, API30 | 15/15 | 3 kontrole seed na starej APK + 7 zachowania danych + 5 diagnostyki na nowej |
| Aktualizacja 0.4.3 → 0.4.4, API36 | 15/15 | Taka sama procedura rzeczywistej aktualizacji bez odinstalowania |
| Czysta 0.4.4 bez com.termux, API36 | 28/28 | Bootstrap, prawdziwe narzędzia, PTY, procesy, klienci i UI |
| Dodatkowa diagnostyka nawigacji API30 | 10/10 | Oryginalny global Back i natychmiastowe otwarcie DeviceActivity |
| Dodatkowa diagnostyka Stop API36 | 1/1 | Jeden właściciel, rzeczywisty proces QEMU, usługa i worker kończą się przed deadline |

Zestaw 51 testów nadzoru Dockera jest podzbiorem 117 testów hosta i nie jest liczony ponownie. Łączna liczba kanonicznych kontroli Androida to 350: 344 na APK 0.4.4 oraz 6 przygotowujących stan na 0.4.3. Dodatkowe 11 kontroli diagnostycznych ma osobne raporty i nie zastępuje zestawów kanonicznych. ARM45 obejmuje 3 kontrole niezmienionych plików bazowych, 5 baseline 0.4.3 i 37 kontroli runtime 0.4.4. Przerwane lub wcześniejsze nieudane próby nie wchodzą do powyższych wyników.

## ARM, rzeczywisty Docker i migracja

Raport [45 kontroli ARM](../artifacts/0.4.4/docker-arm/arm-startup-migration-results.json) i [weryfikacja dowodów](../artifacts/0.4.4/docker-arm/arm-final-verification.json) obejmują Linux 6.18.55 aarch64, Docker 29.5.2, containerd 2.2.0, overlay2 i QEMU 10.1.5. Host to x86_64 Linux, zegar guest `host`, bez `icount`.

| Faza | RAM / vCPU / quota hosta | Opóźnienie testowe | Czas hosta do ready |
| --- | --- | --- | --- |
| Stara 0.4.3, utworzenie danych | 1 GB / 1 / 1 CPU | Brak | 38,952 s |
| Aktualizacja tego samego dysku | 1 GB / 1 / 0,25 CPU | containerd 20 s, dockerd 160 s | 311,192 s |
| Stop i ponowny start | 1 GB / 1 / 0,5 CPU | Brak | 67,307 s |
| Cold boot i import obrazu | 1 GB / 2 / 0,5 CPU | containerd 20 s | 134,916 s |

W spowolnionej aktualizacji rzeczywiste Docker `_ping` i `/info` stały się gotowe po **206,287 s etapu Dockera**, ponad dawnym limitem 120 s. Nowa granica 360 s nie zwraca fikcyjnego ready. Gotowość wymaga też żywego supervisora i poprawnego obrazu. Zmniejszona quota i wstrzyknięte opóźnienia sprawdzają konkretną ścieżkę; nie są skalibrowanym benchmarkiem Lenovo.

Przeszły build z RUN/COPY, run jako UID1000, exec z WORKDIR i limitem pamięci cgroup, logi, rzeczywisty przekierowany HTTP z interaktywnym HTML, eksport pliku, anulowanie, sprzątanie wybranego zadania, sync/stop i restart. Ten sam dysk zachował poprzedni projekt, obraz, kontener, logi, inode oraz backing SHA. Baza i kernel nie zmieniły się. W testach aktualizacji Androida sprawdzono także bajty, inode, mtime, HOME, prefix runtime, rozmowę i eksport; overlay na Androidzie był rzeczywistym pustym QCOW2, bez danych uruchomionego guest. Migrację istniejących danych guest potwierdza osobny test ARM.

## Android, narzędzia i wygląd

Emulatory x86_64 API30 (3072 MB RAM) i API36 (4096 MB), po 2 vCPU, 4 KiB, 720×1480/dpi300, używają programowego TCG bez KVM i izolowanej poprawki PHADDD w emulatorze 37.2.12. Zegary emulatorów istotnie odbiegają od hosta; raporty zapisują oba pomiary, a porównania wydajności z fizycznym tabletem są niewłaściwe.

Czysta instalacja 0.4.4 na API36 rzeczywiście inicjalizuje środowisko pod UID aplikacji bez `com.termux`, uruchamia bash, Node 24.18.0, Python 3.14.6, curl, jq, git, shebang i PTY, sprawdza procesy/cancel oraz Codex **0.162.1** i Claude Code **2.1.296**. Klienci podają rzeczywisty stan niezalogowany. Pobrano oryginalne zweryfikowane archiwa przez lokalny fixture adb reverse; nie jest to test pobrania HTTPS na Androidzie. HTTPS ARM na Lenovo zgłoszone przez sesję lokalną dotyczy 0.4.3, a nie nowego testu 0.4.4.

WebView sprawdza faktyczne JS/DOM, canvas PNG, storage, recreation, błąd i powrót do działania. To osobny zestaw od rzeczywistego HTTP kontenera ARM; nie deklarujemy połączenia tych etapów w jeden test fizycznego Androida. Obejrzano świeże ekrany Dockera w obu motywach oraz runtime i terminal po czystej instalacji: tekst i przyciski są czytelne, terminal ma rzeczywisty exit0. Zestaw aplikacji sprawdza też klawiaturę, długi model, duże czcionki i orientację poziomą.

Po 200 cyklach diagnostyki bez wymuszania GC liczba otwartych FD wynosiła API30 **48 → 48**, API36 **77 → 77**. Zapis/odczyt/retencja/export sprawdzają symlinki, granice, UTF8 i redakcję tokenu. USB używa rzeczywistego JNI i połączenia do emulatorowego adbd oraz jawnych fixture UART/ESP/nośników; fizyczne urządzenia OTG nie były podłączone.

## Wcześniejsze błędy i granice wyniku

Pierwszy zestaw API30 został przerwany przez `keyDispatchingTimedOut` po 45 poprawnych kontrolach. Nie było dostępnego śladu `/data/anr` ani Dropbox; przyczyna pozostaje nieustalona. Dziesięć powtórzeń oryginalnej nawigacji i końcowy normalny zestaw 83/83 przeszły na tej samej APK. [Dowody pierwszej próby](../artifacts/0.4.4/prototypes/api30-app-anr/interruption.json) pozostają zachowane. Kontroler diagnostyki początkowo czytał niewłaściwy katalog exportu; poprawiono wyłącznie jego raport na podstawie już zapisanych wyników z właściwym UUID, bez nowej próby aplikacji.

Pierwszy test Stop API36 sprawdzał cztery warunki, ale czekał tylko na trzy. [Wynik 9/10](../artifacts/0.4.4/prototypes/stop-race-harness-v1/api36/docker-ui/results.json) zachowano. V2 czeka na wszystkie pierwotne warunki w tym samym limicie 30 s i zapisuje wątki/zegary; pełny zestaw 10/10 oraz wąski 1/1 przeszły bez zmiany produktu. Nie ustalono, który warunek był fałszywy w starej próbce, więc nie przypisujemy jej potwierdzonej przyczyny. Wcześniejsza przerwana próba ARM oraz zakończenie starego emulatora API30 przed rozpoczęciem testów 0.4.4 są osobnymi dowodami infrastruktury.

**Fizyczna 0.4.4 na Lenovo nie została jeszcze zweryfikowana.** Lokalna sesja pobiera dokładne bajty i sprawdza podpis, publikuje Release, aktualizuje bez czyszczenia danych i wykonuje cold boot oraz pełny cykl. Nie zmieniono globalnych zabezpieczeń Androida ani istniejącej zgody FlowMesh. Logowanie właściciela pozostaje osobnym oficjalnym krokiem; lokalne `/status`, fixture strumieni oraz `--version` klienta nie potwierdzają rozmowy z zalogowanym modelem.

## Dostarczenie pliku

Całe 653558148 bajtów przesłano do prywatnego `mateuszsury/androidagent` jako **78 części po 8 MiB** (ostatnia 7635332 bajty), z SHA256 części i weryfikacją dokładnych bajtów każdego obiektu przez GET. Pierwszy gh POST 16 MiB zwrócił 401, przy działających małych zapisach i Git push; przyczyny 401 nie dowiedziono. Connector jawnie ogranicza JSON do 16 MiB, dlatego base64 z 8 MiB mieści się w jego limicie. Części 0–51 przesłał istniejący connector, 52–77 istniejący gh API. Nie zmieniono sekretów, uwierzytelnienia, proxy ani CA. [Dowód weryfikacji](../artifacts/0.4.4/apk-parts-remote-verification.json) i [diagnoza kanału](../artifacts/0.4.4/transfer-channel-diagnostics.json) są zapisane.

[Końcowy manifest](../artifacts/0.4.4/transfer/manifest.json) ma Git SHA **`9018e07265efe829f50a62acae4aad237e1c71b1`** i dołącza niezmienny indeks wydania. [Zakończenie transferu](../artifacts/0.4.4/transfer/completion.json) i [journal](../artifacts/0.4.4/transfer/journal.json) są osobnymi plikami, aby uniknąć cyklicznego SHA raportu. Raport i manifest także sprawdzono przez GET. Lokalny odbiorca pobiera manifest przez `gh api repos/mateuszsury/androidagent/git/blobs/9018e07265efe829f50a62acae4aad237e1c71b1`, dekoduje `content` base64, następnie łączy uporządkowane `parts` po sprawdzeniu ich hashy i pełnej SHA256 APK. APK nie trafia do historii main, Release publikuje lokalna sesja.

[Historia testów 0.4.3 i 0.4.2](TESTING_0.4.3.md) pozostaje odrębnym dowodem. [Nadzór i diagnostyka Dockera](DOCKER.md) opisują rzeczywiste limity i zachowanie danych.
