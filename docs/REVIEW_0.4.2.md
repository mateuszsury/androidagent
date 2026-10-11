# Dodatkowy przegląd 0.4.2

Druga runda skupiła się na lukach pozostawionych przez wcześniejsze testy: nowszych zasadach Androida, błędach uruchamiania klientów, imporcie podczas zmiany konfiguracji, długich rozmowach i odłączaniu urządzeń. Cztery zakresy przejrzano równolegle; dodatkowy niezależny przegląd poprawki pomocnika wykrył przypadek katalogu pod docelową nazwą załącznika. Główna instancja integrowała zmiany i prowadziła testy emulatora.

| Problem | Poprawka |
| --- | --- |
| Brak prośby o zgodę na powiadomienia na Androidzie 13+ | Aplikacja pyta przy włączeniu funkcji. Odmowa nie blokuje usług ani narzędzi. Konfiguracja pokazuje stan i prowadzi do ustawień systemowych. |
| Utrata zgody USB przed startem usługi na Androidzie 14+ | Odmowa promocji do `connectedDevice` zamyka sesje danej generacji i zatrzymuje usługę, zamiast powodować nieobsłużony wyjątek. |
| Odłączenie USB lub sprawdzanie zgody blokowało UI | Odbiornik zdarzeń działa na osobnym wątku, a panel wykonuje sprawdzanie zgody w swojej kolejce operacji. |
| Zerwana odpowiedź legacy ADB wyglądała jak ukończona komenda | Transport odróżnia prawidłową ramkę `CLSE` od nagłego EOF. |
| Import załącznika gubił wynik po obrocie | Operacja należy do zachowanego szkicu. Wysyłanie czeka na import, a odtworzenie zachowuje zaznaczenie tekstu. Po utracie procesu aplikacja informuje o konieczności ponownego wybrania niedodanych plików. |
| Długie rozmowy bez ograniczenia zwiększały liczbę widoków kroków | Limit obejmuje logiczne wiadomości, także pojedyncze wiersze grup narzędzi i załączniki. Usuwane są najstarsze wiersze i puste nagłówki. |
| Problemy kompozytora i miniatur po odtworzeniu ekranu | Przywracana jest pozycja historii i kolejka miniatur szkicu. Niski ekran ma mniejszy kompozytor; klawiatura nie wymusza pełnoekranowego edytora. Długa nazwa modelu nie wypycha przycisku wyboru. |
| Klient kończył się błędem bez komunikatu | Pomocnik 15 pokazuje każdy niezerowy kod wyjścia, także przy pustym stderr. |
| Błąd wznowienia usuwał kontekst i ponawiał zadanie | Identyfikator rozmowy pozostaje zapisany; błąd jest jawny. Nie ma automatycznego wykonania zadania drugi raz. |
| Niekompletny załącznik trafiał do klienta | Pobranie używa pliku tymczasowego i atomowej podmiany. Błąd lub nieprawidłowe ID przerywa start klienta. Docelowy katalog jest odrzucany, a symlink nie przekierowuje zapisu. |
| Nieudany Stop pozostawiał czat jako stale pracujący | Lokalny stan kończy się, odebrany tekst zostaje zachowany, a komunikat wyjaśnia niedostarczenie Stop do Termux. |
| Pomocnik przeżywał restart aplikacji bez dostępnego Stop | Przy następnym wysłaniu aplikacja rozpoznaje aktywny identyfikator i wysyła Stop dokładnie do starego uruchomienia. Użytkownik dostaje instrukcję ponowienia wiadomości. |
| Gotowość pierwszego importu Dockera wyprzedzała utrwalenie danych | Po imporcie i oznaczeniu obrazu VM synchronizuje dysk przed publikacją gotowości. Nie naprawia to wcześniej uszkodzonych warstw ani przerwania samego importu. |
| Komunikat po zatrzymaniu bezwarunkowo obiecywał zapis zadań | Potwierdza zatrzymanie środowiska; nie obiecuje zapisu także w przypadku nieudanego łagodnego zamknięcia. |
| Odmowa uruchomienia lub wznowienia wyglądała jak sukces | Wykrywane jest `null` z uruchamiania usługi Termux; nieudane wznowienie nie dodaje komunikatu o powodzeniu. |

Po przerwaniu przez błąd natywny ART wykryto utratę danych rozpakowanej warstwy obrazu, mimo zachowania jej metadanych. Dodano synchronizację inicjalizacji i regresję wymuszonego zatrzymania oraz ponownego startu tego samego dysku przed buildem. Poprawiono również reset Accessibility w zestawie Dockera: czeka on na wyczyszczenie rzeczywistego stanu usługi, w tym „crashed”, przed ponownym włączeniem. To zmiana przygotowania testu, bez obchodzenia sprawdzeń produktu.

Testy regresji obejmują rzeczywisty systemowy dialog uprawnień, broadcast USB, ramki ADB przez sockety, opóźniony import pliku podczas odtwarzania Activity oraz setki kroków narzędzi. Testy klientów nie wymagają logowania. Wyniki dla konkretnego APK i granice weryfikacji zawiera [raport testów](TESTING.md).

Przegląd i zielone testy nie oznaczają gwarancji bezbłędności. Nadal potrzebne są testy fizycznego telefonu, zgodności urządzeń OTG oraz zadania z zalogowanym modelem. Emulator programowy nie służy do potwierdzania wydajności na telefonie.
