# WebView i emulator bez KVM

Podgląd aplikacji korzysta z systemowego `android.webkit.WebView`. Docker jest opcjonalny; sam ekran podglądu testujemy również z lokalnym serwerem HTTP, bez silnika Docker i bez konta modelu.

## Przyczyna awarii na API 36

W Android Emulator 37.2.12-16428233 dla Linux x86_64 znaleźliśmy błąd software TCG w `helper_phaddd_xmm`. Dla instrukcji `PHADDD xmm0, xmm0` i wartości `[1, 2, 3, 4]` emulator zwracał `[3, 7, 10, 14]`, zamiast `[3, 7, 3, 7]`. Funkcja zapisywała część wyniku przed odczytaniem całego źródła, gdy źródło i cel wskazywały ten sam rejestr. [Publiczny kod QEMU](https://android.googlesource.com/platform/external/qemu/+/ae9d18d2b6261179fbd57fffec720a04f7bfb053/target/i386/ops_sse.h) pokazuje ten sam porządek operacji; potwierdziliśmy go również w instrukcjach konkretnego pliku silnika.

Chromium 133 używa tej instrukcji do obliczeń Adler-32 w zlib. Błędna suma powodowała odrzucenie poprawnego PNG należącego do providera WebView. Eksperymentalne włączenie innego dekodera PNG pozwoliło przejść ten etap, ale później renderer odrzucał poprawny snapshot V8. Wyłączenie Vulkana, PCLMUL oraz zmiana zegara TCG nie usuwały przyczyny. Żaden z tych eksperymentów nie jest potrzebny do poprawki.

Dla danych rozpakowanych z ikony 16×16 suma Adler-32 wynosi `905e2606`. Systemowy zlib i obliczenia skalarne Chromium dawały tę wartość, a jego ścieżka SIMD na wadliwym emulatorze — `7ea7bdda`. Po naprawie instrukcji domyślna ścieżka SIMD również daje `905e2606` i dekoduje PNG w całości.

## Lokalna poprawka środowiska

[Launcher](../tools/start-software-emulator.sh) automatycznie rozpoznaje dokładnie zweryfikowany, wadliwy silnik Linux x86_64 po SHA256 i przygotowuje poprawioną kopię w cache użytkownika. [Skrypt poprawki](../tools/fix-software-emulator.py) zastępuje wyłącznie funkcję `helper_phaddd_xmm` w osobnym silniku headless. Oba operandy są ładowane przed zapisem wyniku. [Kod asemblera](../tools/emulator/phaddd-fixed.S) i odpowiadająca mu [poprawka źródeł QEMU](../tools/emulator/phaddd-source.patch) są dostępne do przeglądu. Silnik w zainstalowanym SDK, obraz Androida i APK pozostają bez zmian.

Obsługiwany jest tylko dokładnie zweryfikowany silnik 37.2.12-16428233 na Linux x86_64. Skrypt wymaga zgodności SHA256 całego pliku i bajtów funkcji. Odmawia modyfikacji innego wydania oraz nadpisania nieznanego katalogu. Zasoby i informacje licencyjne SDK są zachowane. To lokalna poprawka środowiska testowego; nie jest dołączana do aplikacji ani stosowana na telefonach. Inne instrukcje TCG i inne wersje emulatora nie są objęte tą poprawką.

```bash
python3 tools/fix-software-emulator.py \
  --sdk-root "$ANDROID_SDK_ROOT" \
  --output "$HOME/.cache/androidagent/emulator-phaddd"
export ANDROIDAGENT_EMULATOR_ROOT="$HOME/.cache/androidagent/emulator-phaddd"
bash tools/start-software-emulator.sh androidagent_api36
```

Powyższy przykład wybiera własny katalog kopii; samo `bash tools/start-software-emulator.sh androidagent_api36` wystarcza dla rozpoznanego wydania. Przy KVM używaj zwykłego emulatora SDK. Aby odtworzyć błąd na oryginalnym silniku software, wyłącz emulator i ustaw `ANDROIDAGENT_EMULATOR_ROOT="$ANDROID_SDK_ROOT/emulator"`. Usunięcie tej zmiennej przywraca automatyczny wybór. Aktualizacja SDK wymaga ponownego testu instrukcji; launcher uruchamia nieznane wydania bez tej poprawki i nie przenosi jej na nowy silnik.

## Weryfikacja

[Próba instrukcji](../tools/emulator/phaddd-probe.c) porównuje 8192 wektory z niezależnym wynikiem arytmetycznym, w tym źródło równe celowi, rozłączne rejestry i przepełnienia 32-bitowe. Można ją skompilować zarówno na hoście, jak i dla Androida x86_64 przez NDK. Nie wymaga roota.

```bash
export ANDROID_SERIAL=emulator-5554
bash tools/test-webview.sh
```

Zestaw otwiera produkcyjny `DockerPreviewActivity`, testuje renderowanie HTTP, przycisk JavaScript, dekodowanie PNG i odczyt pikseli canvas, storage po odświeżeniu, odtworzenie Activity, stronę błędu, odzyskanie połączenia oraz zamknięcie i ponowne otwarcie. Sprawdza też, czy przyciski Wróć/Odśwież i tytuł mieszczą się na ekranie oraz zostały namalowane w rzeczywistej klatce; sama obecność widoków w hierarchii nie wystarcza. Wymaga skonfigurowanego SDK/JDK, klucza podpisującego testy i zainstalowanego APK, tak jak pozostałe zestawy instrumentation. Raport trafia do `artifacts/api<API>/webview/` z identyfikatorem przebiegu i rzeczywistą wersją providera.

Pełny zestaw `tools/test-docker.sh` sprawdza osobno prawdziwy build i interaktywny podgląd z kontenera. Wyniki ukończonych przebiegów oraz ograniczenia sprzętowe opisuje [raport testów](TESTING.md).
