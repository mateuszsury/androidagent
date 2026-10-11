# Zgody i działanie w tle

Ekran **Uprawnienia** (`PermissionGuide.open(Context)`) pokazuje bieżący stan i otwiera odpowiednie okna Androida. Odczytuje stan ponownie po powrocie do aplikacji. Samo otwarcie ekranu nie wyświetla prośby o zgodę i nie zmienia ustawień. Aplikacja nie nadaje sobie zgód ani nie obchodzi potwierdzeń właściciela.

| Dostęp | Kiedy jest potrzebny | Sposób uzyskania |
| --- | --- | --- |
| Internet i stan sieci | Połączenie z oficjalnym klientem/modelami i lokalne narzędzia | Normalne deklaracje manifestu; bez dialogu runtime |
| Usługa pierwszoplanowa (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`) | Działające lokalne narzędzia i VM podczas zadań uruchomionych przez użytkownika | Manifest i odpowiedni typ usługi; start z widocznej aplikacji |
| `WAKE_LOCK` | Utrzymanie pracy CPU podczas uruchomionej VM | Normalna deklaracja manifestu; bez dialogu runtime |
| Powiadomienia (`POST_NOTIFICATIONS`, Android 13 / API 33+) | Widoczne zadanie i przycisk Stop w powiadomieniu | Opcjonalny dialog Androida lub ustawienia powiadomień tej aplikacji |
| Mikrofon (`RECORD_AUDIO`) | Dyktowanie wiadomości | Opcjonalny dialog Androida; wpisywanie tekstu działa bez tej zgody |
| Ułatwienia dostępu / Accessibility | Odczyt ekranu, gesty i klikanie w innych aplikacjach | Właściciel włącza usługę androidagent w ustawieniach ułatwień dostępu |
| Wyjątek optymalizacji baterii | Długie rozmowy, kompilacje i VM w tle | Opcjonalne systemowe potwierdzenie, osobno dla androidagent |
| USB | Dostęp do konkretnego urządzenia po USB OTG | `UsbManager.requestPermission()` dopiero dla wybranego urządzenia |
| Foldery i pliki poza przestrzenią aplikacji | Wybrany folder, nośnik USB lub załącznik | Systemowy wybór SAF; zakres zgody obejmuje wybrane URI |
| Instalowanie innych APK (`REQUEST_INSTALL_PACKAGES`) | Osobna funkcja instalacji zbudowanej aplikacji | Opcjonalna zgoda „Instalowanie nieznanych aplikacji” i systemowy instalator |

Odmowa powiadomień **nie jest przeszkodą dla uruchomienia usługi pierwszoplanowej**. Android nadal wymaga jej powiadomienia, lecz może pokazywać aktywną usługę jedynie we własnym panelu zadań. `POST_PROMOTED_NOTIFICATIONS` na Androidzie 16 dotyczy prezentacji powiadomień; nie jest warunkiem rozmowy ani wykonania komendy. Poszczególne kanały użytkownik kontroluje w systemowych ustawieniach powiadomień.

Stan ułatwień dostępu porównuje dokładny komponent własnej usługi, a nie sam fakt, że jakakolwiek usługa jest aktywna. Ekran rozróżnia usługę włączoną i rzeczywiście połączoną. Nie zapisuje `enabled_accessibility_services` ani `accessibility_enabled`. W szczególności nie wyłącza `app.flowmesh.alpha/app.flowmesh.platform.FlowMeshAccessibilityService` ani pozostałych usług właściciela. Brak Accessibility nie blokuje terminala, rozmów, USB lub Dockera. Narzędzia sterujące ekranem zwracają wtedy błąd wymaganej zgody.

MCP `run_command` korzysta z osobnej karty zgody we własnej rozmowie i działa bez Accessibility. Tryb programisty oraz zatwierdzenie pełnej, nieuciętej komendy są obowiązkowe. Prośba ma własne ID i jest związana z poświadczeniem konkretnego przebiegu; zgoda ze starego przebiegu ani kliknięcie zachowanego starego przycisku nie zatwierdzają nowego żądania. Stop oraz zakończenie przebiegu odwołują prośby i przerywają przypisane do niego komendy. Terminal otwarty ręcznie przez właściciela uruchamia wpisaną przez niego komendę bez karty MCP.

Po instalacji APK Android może zablokować włączenie Accessibility jako ustawienie z ograniczeniami. Ekran wyjaśnia, jak właściciel może użyć opcji **Zezwól na ustawienia z ograniczeniami** w Informacjach o aplikacji, jeśli urządzenie ją udostępnia. Aplikacja sama tej opcji nie włącza.

Wyjątek baterii poprawia szanse zakończenia długiego zadania; nie gwarantuje działania przy braku pamięci ani nie znosi ograniczeń producenta, Androida lub procesów potomnych. Przy problemach właściciel może sprawdzić systemowe ograniczenia działania androidagent w tle. Przywrócenie zwykłej optymalizacji pozostaje dostępne w ustawieniach Androida.

USB używa typu usługi `connectedDevice` na nowych Androidach i rozpoczyna pracę po zgodzie na urządzenie. Nie wymaga wspólnej zgody na wszystkie urządzenia. SAF zastępuje szeroki dostęp do pamięci: nie potrzeba `MANAGE_EXTERNAL_STORAGE` ani dawnych `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`. Kontrola istniejących grantów i cofnięcie dostępu do sesji należą do warstwy USB/plików.

Logowanie do Codexa lub Claude Code jest niezależne od powyższych zgód. Gotowe lokalne środowisko nie oznacza zalogowanego modelu; właściciel korzysta z oficjalnej metody logowania. Nie kopiujemy tokenów ani kont z innego komputera.

`PermissionRegressionChecks` sprawdza stan Androida, trasy natywnych ustawień, nieeksportowany ekran, odświeżanie po `onResume`, rozpoznawanie tylko własnej usługi oraz zachowanie istniejącej listy Accessibility. Test przycisku blokuje natywne okno przez `Instrumentation.ActivityMonitor`; nie nadaje ani nie odbiera zgód na fizycznym urządzeniu.
