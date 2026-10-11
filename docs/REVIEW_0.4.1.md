# Przegląd kodu i interfejsu 0.4.1

Przegląd wykonano w sześciu równoległych zakresach: integracje Codex/Claude, USB/ADB/ESP/SAF, Docker, interfejs, MCP/usługi/załączniki/głos oraz pakowanie. Główna instancja zintegrowała poprawki, wykonała dodatkowy przegląd granic między modułami i uruchomiła testy. Zakres obejmował własny kod aplikacji, skrypty klientów, kod gościa, źródła JNI, manifest, zasoby i narzędzia budowania. Przypiętych bibliotek upstream nie przepisywano; ich integralność i pakowanie sprawdzają walidatory.

## Naprawione problemy

| Obszar | Potwierdzony problem i zmiana |
| --- | --- |
| Uruchomienia agentów | Callback i opóźniony Stop poprzedniej odpowiedzi mogły wpływać na nowe zadanie. Pomocnik 14 i aplikacja przekazują identyfikator konkretnego uruchomienia; stare zdarzenia są odrzucane. |
| Anulowanie | PID był zapisywany dopiero po pobraniu załączników. Pomocnik rejestruje proces wcześniej, obsługuje Stop dostarczony przed `ask` i sprawdza czas utworzenia PID. |
| Historia | Limit rozmów usuwał również aktywne zadania. Zachowywane są aktywne rozmowy, a usunięcie nieaktywnej zwalnia jej zasoby. Zapis aktualizuje JSON zmienionej rozmowy i indeks, zamiast ponownie budować JSON wszystkich rozmów. SharedPreferences nadal zapisuje cały plik preferencji na dysku. Migracja zachowuje wiadomości i metadane; uszkodzony zapis źródłowy pozostaje jako kopia. |
| Protokoły klientów | Codex gubił ostatnie zdarzenie JSONL bez końcowego LF. Początkowy blok Claude omijał limit strumienia, a końcowa wiadomość podagenta trafiała do głównej odpowiedzi. Dodano odpowiednie granice i rozdzielenie zdarzeń. |
| Aktualizacja pomocnika | Instalacja mogła nadpisać wykonywany skrypt. Pomocnik i launchery są teraz podmieniane przez plik tymczasowy i `mv`. |
| Czat i zdjęcia | Powrót do czatu pozostawiał strumień w odłączonym widoku. Naprawiono odbudowę i przewijanie bez odrywania użytkownika od czytanej historii. Miniatury są dekodowane poza UI, z ograniczeniem dłuższego boku także dla panoram. |
| Wygląd i dostępność | Systemowy ciemny motyw nie pasował do jasnych widoków. Motyw bazowy dialogów odpowiada teraz palecie aplikacji. Poprawiono kontrast logów, odstępy, przewijanie wysokich paneli i formularzy oraz stan przełączników dla czytnika ekranu. Niski ekran, w tym poziomy przy większej czcionce, korzysta z kompaktowego nagłówka bez dużej ikony dekoracyjnej; pierwsza akcja pozostaje widoczna. |
| Konfiguracja | Ekrany nadal wymagały Accessibility dla funkcji samodzielnego hosta MCP. Konfiguracja i monitor sesji korzystają z `AgentService`; Accessibility pozostaje opcją do sterowania ekranem. Poprawiono gest Wstecz i zachowanie stanu konfiguratora. |
| Odświeżanie Dockera | Wyjście z ekranu mogło pozostawić `polling=true` na stałe. Rozdzielono stan widoczności i trwającego żądania. Odczyt eksportów i reset dysku działają poza głównym wątkiem. |
| Stop i MCP | Zatrzymanie mogło czekać na USB/SAF, a kolejka serwera pozostawiała sockety. Poświadczenia są cofane od razu, sprzątanie działa w tle, a zbyt wczesna próba nowego uruchomienia dostaje informację o konieczności ponowienia po zakończeniu sprzątania. Żądanie ponownie sprawdza dokładny token po odebraniu treści i po oczekiwaniu na blokadę zadania; nowy przebieg tego samego czatu nie przywraca uprawnień staremu. Serwer zamyka też aktywne i oczekujące połączenia oraz odrzuca niepełne ramki HTTP i nadmierne nagłówki. |
| Głos i pliki | Stare callbacki rozpoznawania i oczekujący TTS mogły odezwać się po Stop. Dodano generacje rozpoznawania i czyszczenie oczekującej mowy. Poprawiono nazwy importowanych plików i projekcje `FilesProvider`. |
| Lokalna powłoka | Polecenie kończące się po uruchomieniu `&` pozostawiało proces potomny. Zwykłe procesy w tle pozostają objęte limitem terminala i zatrzymaniem. |
| UART/ESP | Błąd otwarcia portu lub FGS pozostawiał zasoby, a zapis mógł ścigać się z rezerwacją ESP/Docker. Naprawiono rollback, wyłączność i przywracanie pełnych parametrów UART po ESP. |
| ADB | Stały napływ danych omijał timeout/anulowanie, a przerwany upload APK mógł pozostawić plik. Naprawiono sprawdzanie granic i sprzątanie. Filtry logcat są przekazywane jako oddzielne argumenty. |
| SAF i panel USB | Ponowny grant ożywiał stare strumienie. Generacja grantu unieważnia wcześniej otwarte strumienie. Zapytania dostawcy nie blokują globalnej blokady; wynik jest ponownie walidowany. Panel obsługuje kolejne strony katalogów, zachowuje zaznaczenie niezmienionego logu i ogranicza kolejkę operacji. |
| USB FGS | Systemowe `onDestroy` zamykało połączenia na UI. Sprzątanie w tle obejmuje tylko generację zaakceptowaną przez daną instancję usługi. Pozostawia nowe połączenia także wtedy, gdy powstały przed `onDestroy`, lecz ich start nie dotarł jeszcze do usługi. |
| USB w Dockerze | Attach blokował globalną sesję podczas QMP/HTTP; spóźniony wynik lub rollback mógł odtworzyć albo cofnąć nowy grant. Skrócono blokady i dodano kontrolę generacji oraz właściciela zadania. Puste ID cancel/cleanup nie zamykają niepowiązanych połączeń. |
| VM | Stary heartbeat/shutdown mógł zmienić stan nowszego uruchomienia. Odpowiedzi są sprawdzane względem generacji; restart czeka na zakończenie wcześniejszych shutdown, a przygotowanie dysku jest serializowane. |
| Pakowanie | Gradle nie sprawdzał kompletności Dockera, a Python `-O` wyłączał walidację opartą na `assert`. Obie ścieżki budowania sprawdzają wymagane zasoby i ABI, także przy optymalizacji Pythona. Dodano sumę dystrybucji Gradle i zgodną obsługę plików `.gz`. |

## Dowody i granice

Nowe regresje znajdują się w `tests/android/*RegressionChecks.java`, rozszerzonych zestawach USB/Docker oraz testach hosta. Testy urządzenia używają osobnego APK; nie trafiają do produktu. Scenariusze obejmują opóźnione zdarzenia, blokowane I/O, ponowne nadanie dostępu, migrację starszej historii i duże katalogi. Kontrola wyglądu obejmuje zrzuty ekranu w obu motywach, większą czcionkę, obrót i przewijanie.

Końcowe wyniki, sumę APK i ograniczenia środowiska zawiera [raport testów](TESTING.md). Testy emulatora nie zastępują rozmowy z zalogowanym modelem ani testów fizycznego OTG, ESP i Androida ARM64. Emulacja bez KVM nie daje miarodajnego pomiaru płynności na telefonie.
