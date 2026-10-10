# Claude Code w androidagent 0.2.0

Claude Code jest drugim pełnym backendem rozmów. Selektor na ekranie głównym wybiera agenta nowych rozmów. Istniejąca rozmowa zachowuje swój backend, model i historię; sesja Codexa nie jest przekazywana do Claude. Oba środowiska można zainstalować w tym samym Termux.

## Instalacja i logowanie

1. Zainstaluj APK 0.2.0 i Termux. W konfiguracji włącz Accessibility, RUN_COMMAND oraz pomocnika w wersji **11**.
2. Wybierz Claude Code lub obu agentów w przewodniku. Skopiuj polecenie instalacji i wklej je do Termux.
3. Instalator z zasobów APK instaluje pakiety Termux, oficjalny `@anthropic-ai/claude-code-linux-{arm64,x64}-musl@2.1.296` oraz musl `1.2.5-r12` z Alpine. Musl jest sprawdzany przez SHA256, npm sprawdza integralność pakietu klienta. Wrapper znajduje się w `~/bin/claude`. Root i patchowanie ELF nie są potrzebne.
4. Zaloguj się przez oficjalne `~/bin/claude auth login`. W razie powrotu do przeglądarki postępuj zgodnie z instrukcją CLI. Nie wpisuj danych konta do rozmowy androidagent.
5. Wróć do aplikacji. Pomocnik używa `claude auth status` do sprawdzenia logowania; do Androida przekazuje wyłącznie status, bez poświadczeń. Rozmowa nie rozpoczyna się, jeżeli wybrany backend nie jest skonfigurowany i zalogowany. `/status` działa niezależnie od modelu.

Przycisk **Napraw lub zaktualizuj instalację** ponownie uruchamia instalator, zachowując konfigurację konta. Aktualizator desktopowy Claude jest wyłączony w wrapperze, ponieważ jego binaria nie mają mapowań potrzebnych na Androidzie. Wersja jest przypięta; aktualizacje klienta wymagają zmiany przypiętej wersji w projekcie i ponownej instalacji runtime.

## Rozmowy i narzędzia

- Wyjście `stream-json --include-partial-messages`: tekst na żywo w rozmowie i pływającym panelu. Zakończona odpowiedź jest renderowana i zapisywana raz. Zdarzenia subagentów nie mieszają się ze strumieniem głównej odpowiedzi.
- Model Default, aliasy **Sonnet**, **Opus**, **Haiku** oraz własny identyfikator. Aliasy rozstrzyga oficjalny klient; interfejs nie obiecuje konkretnej wersji ani dostępu z danego konta.
- Wznowienie przez prawdziwy identyfikator `session_id`, osobny dla każdej rozmowy; załączniki pobierane z uwierzytelnionego serwera telefonu do katalogu sesji.
- Wszystkie 17 narzędzi MCP telefonu, z tokenem instalacji i `--strict-mcp-config`. Potwierdzenia oraz Stop współdzielą ten sam interfejs, co Codex.
- Zwykły tryb udostępnia WebSearch/WebFetch/Read oraz narzędzia MCP; odczyt załączników jest zatwierdzony regułą ich katalogu. Powłoka i edycja plików są usunięte z zestawu narzędzi.
- Tryb programisty dodaje Bash/Read/Write/Edit/Glob/Grep/Skill/Agent i narzędzia kodowania z uprawnieniami UID Termux. Mogą odczytywać i zmieniać całe środowisko Termux. Nie jest to sandbox projektu ani dostęp root. Preautoryzowane operacje kodowania wykonują się bez osobnych kart zgody; inne żądania uprawnień w trybie nieinteraktywnym są odrzucane. MCP `run_command` zawsze wymaga zatwierdzenia dokładnej komendy.
- Stop zatrzymuje drzewo procesów pomocnika, również procesy ignorujące SIGTERM. Odbiera także sterowanie telefonem. Nie usuwa historii rozmowy.
- Przycisk przekazania rozmowy otwiera widoczną sesję Termux z Remote Control. Przekazuje model, identyfikator rozmowy, uwierzytelniony MCP i ograniczenia wybranego trybu. Dostępność połączenia z oficjalną aplikacją Claude zależy od konta i usługi.

## Weryfikacja

[Raport testów](TESTING.md) rozdziela testy prawdziwego CLI/MCP i interfejsu od kontrolowanych zdarzeń protokołu. Zgodnie z decyzją użytkownika testy odbywają się bez konta modelu. Nie potwierdzają wykonania zadania przez zalogowanego Claude, działania Remote Control z kontem, telefonu ARM64 ani niezawodności długich zadań w tle.

Referencje: [oficjalne CLI](https://code.claude.com/docs/en/cli-reference), [tryb headless i streaming](https://code.claude.com/docs/en/headless), [oficjalny pakiet npm](https://www.npmjs.com/package/@anthropic-ai/claude-code), [musl](https://musl.libc.org/).
