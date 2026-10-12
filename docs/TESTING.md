# Testy androidagent 0.4.4

Wydanie 0.4.4 (`versionCode=404`, runtime Docker revision 3, `targetSdk=36`) poprawia nadzór rozruchu na wolnym ARM/TCG. Baza i kernel pozostają identyczne z 0.4.2 i 0.4.3; aktualizacja nie resetuje dysku, rozmów ani HOME.

## Rzeczywisty wynik fizycznej 0.4.3

Lenovo TB336FU, Android 16/API36, ARM64, strony 4 KiB: dziewięć kontroli UID, narzędzi, rzeczywistych pobrań HTTPS i pierwszych uruchomień Codex 0.162.1 / Claude Code 2.1.296 przeszło. Docker z 1 GB RAM nie osiągnął gotowości. Containerd Version RPC był gotowy po 71,756 s; po rozpoczęciu etapu Dockera po 72,209 s nadzór zakończył go po 194,623 s z błędem limitu 120 s. Log z tego urządzenia ma 32044 bajty i SHA256 `4920d8d3acbb43990be42d13ac41ff0d162fd9ff3811fead4d25245bd4d7d9c6`.

Komunikat `xtables contention` dla udanej operacji trwającej 3,94 s nie dowodzi blokady. TERM oraz utrata połączeń containerd nastąpiły po błędzie nadzoru. Neutralny wpis Androida o śmierci procesu nie identyfikuje przyczyny zakończenia. Bieżący pomiar baterii 6%, wyłączonego Battery Saver i Thermal Status 0 nie opisuje stanu historycznego podczas nieudanego bootu. Nie zmieniamy globalnych zabezpieczeń Androida.

[Analiza](../artifacts/0.4.4/lenovo-0.4.3-startup-analysis.json), [log](../artifacts/0.4.4/lenovo-0.4.3-startup.log) i [raport urządzenia](../artifacts/0.4.4/lenovo-0.4.3-device-results.json) zachowują faktyczny wynik. Oryginalne assety są w [wydaniu v0.4.3](https://github.com/mateuszsury/androidagent/releases/tag/v0.4.3).

## Weryfikacja nowej wersji

Trwa budowanie oraz testy podpisanej APK 0.4.4. Końcowe wyniki zostaną związane z dokładnymi bajtami APK w indeksie wydania. Test ARM Linux/QEMU nie jest fizycznym Androidem ani skalibrowanym benchmarkiem Lenovo. Lokalne /status, fixture strumieni oraz --version klienta nie potwierdzają rozmowy z zalogowanym modelem.

[Historia testów 0.4.3 i 0.4.2](TESTING_0.4.3.md) pozostaje odrębnym dowodem. [Nadzór i diagnostyka Dockera](DOCKER.md) opisują rzeczywiste limity i zachowanie danych.
