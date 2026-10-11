# Docker w androidagent 0.4.2

Aplikacja zawiera QEMU oraz obraz Alpine Linux z rzeczywistym Docker Engine. Silnik działa we własnym jądrze Linux w maszynie wirtualnej, uruchomionej z UID androidagent. Telefon nie wymaga roota, Termux ani Shizuku do tej funkcji. Termux pozostaje potrzebny do klientów Codex/Claude Code.

## Używanie

1. Otwórz **Docker** z ekranu głównego. Wybierz pamięć i naciśnij **Uruchom środowisko**. Pierwszy start rozpakowuje obraz; kolejny korzysta z zapisanego dysku. Powiadomienie pozwala zatrzymać środowisko.
2. Utwórz zadanie lub wybierz **Wypróbuj przykładową aplikację**. Demo zawiera Dockerfile, interaktywną stronę HTML i mały serwer statyczny korzystający z dołączonego `nc`. Działa jako `USER 1000:1000`. Obraz `alpine:3.23` jest dołączony, więc demo nie wymaga rejestru obrazów ani konta modelu.
3. W zadaniu zapisuj i wczytuj pliki, buduj obraz i uruchamiaj kontener. Dziennik pokazuje stan i wynik operacji. Opcjonalny port kontenera jest publikowany pod `http://127.0.0.1:18781` na telefonie; jednocześnie działa jeden podgląd na tym porcie. **Otwórz podgląd** wyświetla interaktywną stronę wewnątrz androidagent, bez osobnej przeglądarki.
4. Komendy wykonują się **w kontenerze tego zadania**, w `/workspace`. Zapisuj tam wyniki, np. `/workspace/out/app.apk`. Zwykły start obrazu zachowuje jego `WORKDIR`, `ENTRYPOINT` i `CMD`; katalog `/workspace` pozostaje dodatkowym montowaniem projektu.
5. **Zachowaj plik** eksportuje wynik poza maszynę wirtualną. Plik pojawia się na ekranie Docker i można przekazać go innej aplikacji przez systemowy podgląd lub udostępnianie.
6. **Posprzątaj i usuń zadanie** usuwa jego kontener, wygenerowane obrazy i pliki robocze. Zachowane wyniki i inne zadania pozostają dostępne. Wspólny cache pozostaje; jego usunięcie wymaga zatrzymania i resetu całego dysku.

Ponowne uruchomienie kontenera zastępuje wcześniejszy kontener tego samego zadania. Pliki w `/workspace` pozostają; zmiany tylko wewnątrz kontenera wymagają skopiowania do `/workspace` przed zastąpieniem lub sprzątaniem. Wersja 0.4.2 zmienia obraz bazowy, dodając utrwalenie pierwszego importu przed gotowością. Aktualizacja z 0.4.1 wymaga opisanej niżej procedury zachowania wyników i resetu. Reset całego dysku usuwa wszystkie zadania i cache po potwierdzeniu. Eksportowane pliki pozostają. Przed aktualizacją APK zmieniającą obraz bazowy zachowaj wyniki ze starego środowiska. Nowa wersja nie kasuje samoczynnie starego dysku, ale wymaga jego resetu przed uruchomieniem zmienionej bazy.

## Codex i Claude Code

Obaj klienci otrzymują tę samą listę MCP. Użytkownik uruchamia maszynę z aplikacji. Narzędzia nie instalują silnika na Androidzie i nie wymagają powłoki Termux do operacji w Dockerze.

| Narzędzie | Działanie |
| --- | --- |
| `docker_status` | Stan VM, wersja silnika, architektura, miejsce i zadania |
| `docker_create` | Nowy katalog projektu i identyfikator zadania |
| `docker_write`, `docker_read`, `docker_files` | Pliki tekstowe UTF-8 i lista plików projektu |
| `docker_build` | Asynchroniczne budowanie Dockerfile |
| `docker_run` | Własny obraz lub wskazany obraz OCI, opcjonalny podgląd |
| `docker_exec` | Asynchroniczna komenda w kontenerze zadania |
| `docker_logs` | Stan, kod wyjścia i wynik zadania lub log kontenera |
| `docker_cancel` | Anulowanie operacji i zatrzymanie kontenera |
| `docker_export` | Zachowanie wyniku w pamięci androidagent i URI do udostępnienia |
| `docker_cleanup` | Sprzątanie wyłącznie wskazanego zadania |

`docker_build`, `docker_run` i `docker_exec` zwracają identyfikator operacji. Odczytuj go przez `docker_logs`; wynik początkowy nie oznacza zakończenia budowania. Każde zadanie dopuszcza jedną aktywną operację. Instrukcja obu agentów opisuje eksport przed sprzątaniem. Rozmowa z zalogowanym modelem pozostaje poza zakresem testów zgodnie z decyzją użytkownika.

## USB w kontenerach

W panelu **Urządzenia USB** otwórz profil Docker i przydziel urządzenie wybranemu zadaniu. Most libusb/usbredir przekazuje je do xHCI gościa, a kontener otrzymuje pojedynczy węzeł `/dev/bus/usb` przez `--device`. Obsługujemy do ośmiu urządzeń; dostęp nie wymaga trybu privileged ani montowania całego `/dev`.

Dla sesji UART wybierz most do zadania. Kontener dostaje `ANDROIDAGENT_SERIAL_URL` oraz moduł URL dla pyserial; pyserial trzeba zainstalować w obrazie. Odczyt, zapis i linie sterujące trafiają do natywnego transportu aplikacji. Surowy profil i natywny UART/ADB są wyłączne dla danego urządzenia. Pliki nośników obsługuj przez SAF.

Narzędzia `usb_docker_attach`, `usb_docker_serial` i `usb_docker_detach` wymagają przydziału sesji z panelu. Zadanie z przydzielonym USB wymaga tego samego właściciela także przy wywołaniach `docker_*`. Odłączenie, Stop, odebranie przydziału i sprzątanie zatrzymują używający urządzenia kontener. Restart VM nie przywraca przydziałów. Szczegóły, przykład Python i granice testów sprzętowych: [USB.md](USB.md). Docker pozostaje opcjonalny wobec bezpośrednich narzędzi USB.

## Dostęp i limity

- QEMU wykonuje się jako aplikacja Android; nie używa roota, `/dev/kvm`, ADB, usług systemowych Shizuku ani serwera zdalnego. Izolację zapewnia oddzielne jądro VM i standardowy Docker. Nie jest to gwarancja odporności na błędy jądra/QEMU.
- Procesy agentów nie dostają socketu Dockera, powłoki administratora VM ani katalogów kont innych aplikacji. Kontener dostaje katalog własnego zadania, bez trybu privileged i bez socketu Dockera. Przy przydziale USB dochodzi wybrany węzeł urządzenia, a przy UART — moduł protokołu montowany tylko do odczytu.
- Obraz zachowuje deklarację `USER`. Katalog projektu i pliki utworzone przez kontroler są zapisywalne dla użytkownika kontenera, także bez UID 0. Ten katalog jest montowany tylko w kontenerze własnego zadania.
- API VM słucha przez przekierowanie QEMU na `127.0.0.1:18780`; wymaga osobnego losowego tokenu. Token jest przekazywany przez dodatkowy dysk tylko do odczytu, poza linią komend jądra i urządzeniami kontenera. MCP telefonu zachowuje osobny token i port 8766.
- Podgląd WebView obsługuje JavaScript, ale nie udostępnia mostu Java, plików aplikacji ani URI `content://`. Nawigacja poza lokalny podgląd trafia do zewnętrznej przeglądarki, jeśli jest dostępna.
- Dostęp do plików sprawdza każdą składową ścieżki przez `openat` z `O_NOFOLLOW`. Ścieżki bezwzględne, `..`, katalogi, urządzenia i linki symboliczne nie są obsługiwane przez odczyt/zapis/eksport.
- Plik tekstowy: 256 KiB. Kontekst budowania: 512 MiB, bez linków symbolicznych. Zachowany plik: 128 MiB. Wynik operacji: ostatnie 64 tys. znaków. Limit czasu operacji: 15 minut zegara Linuxa gościa. Domyślny kontener: 512 MB RAM, 1 CPU, 256 PID; limit RAM można zmienić do 2 GB. Log Dockera rotuje 2 × 5 MB.
- VM: 512 MB, 1 GB lub 2 GB RAM; jeden CPU w interfejsie. Dysk ma 8 GB pojemności wirtualnej, zajmuje miejsce stopniowo. Rzeczywisty limit zależy także od wolnego miejsca telefonu. Pierwsze rozpakowanie wymaga co najmniej 1 GB wolnego miejsca poza instalacją APK. Duże projekty wymagają więcej.
- Środowisko działa w usłudze pierwszoplanowej z powiadomieniem. Android i producent telefonu mogą zakończyć proces; stan operacji po nieoczekiwanym restarcie otrzymuje `interrupted`. Zapisany dysk pozostaje. Globalny **Stop** odbiera agentowi kontrolę i kończy QEMU; zwykłe zatrzymanie z panelu najpierw synchronizuje dysk.
- Nagłe zakończenie VM może utracić niezapisane dane i uszkodzić cache obrazów. Podczas testów po awarii ART znaleziono pusty plik wykonywalny w warstwie Dockera, mimo poprawnego obrazu bazowego. Przed resetem zachowaj dostępne pliki projektów i eksporty; reset usuwa wszystkie zadania. Aplikacja nie resetuje dysku automatycznie po takim błędzie.

## Możliwości i ograniczenia

Obsługiwane są ARM64 i x86_64. Używany jest QEMU TCG; dłuższe buildy obciążają baterię i mogą być znacznie wolniejsze od komputera. Nie wykorzystujemy jeszcze Android Virtualization Framework. APK zawiera obrazy dla obu architektur i jest duży; nie pobiera VM przy pierwszym uruchomieniu.

Na fizycznym telefonie VM korzysta ze zwykłego zegara i ma pięć minut na start. Na emulatorach Androida `ranchu`/`goldfish` używa zegara opartego na liczbie instrukcji (`icount shift=3`) i dziesięciu minut czasu Androida na start: emulacja wewnątrz emulacji wymaga tej korekty. Czasy testów na emulatorze bez KVM nie określają wydajności telefonu.

Obrazy OCI i narzędzia kompilacji muszą obsługiwać architekturę Linux gościa. Nie ma emulacji innych architektur kontenerów przez binfmt. Dotyczy to także Android SDK: część oficjalnych narzędzi Linux jest tylko x86_64, więc dowolnego desktopowego obrazu do budowania APK nie można automatycznie obiecać na ARM64. Można budować projekty z odpowiednim zestawem narzędzi; eksport APK jest obsługiwany, a instalację wykonuje zwykły instalator Androida.

Dockerfile może pobierać zależności przez internet. Stan sieci, DNS, dostępność rejestru i TLS zależą od telefonu. Test offline korzysta z rzeczywistego dołączonego obrazu Alpine, bez pozorowanego silnika. Docker CLI, Buildx i Compose są w VM; interfejs/MCP w tej wersji obsługuje zadania z jednym kontenerem, a nie dowolny plik Compose lub funkcje Docker Desktop/Kubernetes.

## Budowanie środowiska

Wymagane na gospodarzu Linux: działający Docker, Python 3.9+, `dpkg-deb`, `readelf`, `patchelf` i moduł Python `pyelftools`. Pełny APK 0.4.2 wymaga też JNI USB z Android NDK 29.0.14206865. Oprócz standardowego SDK/JDK z README:

```sh
python3 tools/prepare-qemu.py
ANDROID_NDK_ROOT=/ścieżka/do/ndk/29.0.14206865 bash tools/build-usb-native.sh
bash tools/build-docker-guest.sh x86_64
bash tools/build-docker-guest.sh aarch64
bash tools/build-local.sh
```

Pakiety Termux są przypięte URL-em, wersją i SHA256 w `docker/qemu-lock.json`. Nie uruchamiaj `--update-lock` w zwykłym buildzie. Skrypt wybiera faktyczną zależność ELF, zmienia nazwy bibliotek na format akceptowany przez instalator APK i spójnie poprawia SONAME, DT_NEEDED oraz wersjonowane zależności. Biblioteki są ładowane z katalogu instalacji Androida, a nie wykonywalnego katalogu zapisywalnego aplikacji.

Baza Alpine jest przypięta digestem dla każdej architektury. Pakiety są instalowane z gałęzi 3.23; pełne faktyczne wersje i licencje znajdują się w `docker/guest-packages-*.json`. Repozytoria Alpine aktualizują pakiety, więc ponowny build nie jest obietnicą identycznego obrazu bajt w bajt. Manifest zasobów przypina SHA256 obrazu/kernela/initramfs dla konkretnego APK. `check-docker-assets.py` odrzuca brakujące lub niespójne zasoby przed pakowaniem. Binaria i obrazy są artefaktami generowanymi, nie plikami Git.

Obraz ARM64 jest przygotowywany przez natywny cross-install APK i mkinitfs; nie wymaga działającego binfmt na gospodarzu. Obraz ext4 powstaje bez montowania urządzeń przez `mkfs.ext4 -d`, a zapis użytkownika trafia do oddzielnego QCOW2. Certyfikat proxy środowiska budowania, jeśli jest potrzebny, jest montowany tylko do kontenera budującego i nie trafia do VM ani aplikacji.

Pochodzenie i licencje: [docker/SOURCES.md](../docker/SOURCES.md), inwentarze pakietów, teksty w `docker/licenses`. Kod kontrolera VM, integracji MCP i interfejsu jest własną implementacją androidagent. Linxr/Pockr pomogły ocenić architekturę, ale nie kopiujemy ich aplikacji ani gotowych obrazów.

## Testowanie

```sh
adb install -r artifacts/androidagent-0.4.2.apk
bash tools/test-docker.sh
```

Uruchamiaj wyłącznie na dedykowanym emulatorze: zestaw **resetuje dysk Dockera**, tworzy i usuwa projekty, włącza testową usługę Accessibility. Nie loguje modelu i nie zastępuje Dockera atrapą. Raport oraz zrzuty trafiają przez MediaStore do `Download/androidagent-docker-tests/`, następnie do `artifacts/docker/`. Skrypt sprawdza identyfikator bieżącego przebiegu i kończy się błędem przy niepowodzeniu lub braku aktualnego raportu.

`--reuse-cache` pomija początkowy reset przy ponawianiu przebiegu z tą samą bazą VM; końcowy przypadek nadal resetuje dysk. Nie używaj tej opcji do końcowej weryfikacji po nagłej awarii VM: zachowany cache może być uszkodzony. `--ui-only` sprawdza motywy, obrót i zatrzymanie w trakcie rozruchu, również na dedykowanym emulatorze z resetem. Dodatkowe `start-arm-guest-test.sh` i `test-arm-guest.py` sprawdzają prawdziwego gościa ARM64 pod QEMU gospodarza Linux. Opcja `--host-clock` w obu skryptach wybiera zwykły zegar, zamiast trybu instrukcji. Nie są to testy telefonu Android ARM64.

Wyniki i faktyczne granice weryfikacji opisuje [TESTING.md](TESTING.md).
