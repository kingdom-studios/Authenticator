# Authenticator — plan architektury i wdrożenia

> Plugin autoryzacyjny dla sieci Velocity + Paper 26.2: funkcjonalny klon `mineLogin`, przepisany od zera.
> Paper oparty wyłącznie na MineAcademy Foundation, MariaDB jako źródło prawdy, Redis jako bridge proxy ⇄ backend.

Status: **plan zatwierdzony. Etapy 1 i 2 zrealizowane** (szczegóły w sekcji „Status realizacji” na końcu), etapy 3–5 przed nami.

---

## 0. Źródła analizy

- **mineLogin** — w `libs/` są wyłącznie skompilowane JAR-y (`mineLogin-velocity-1.10.0.jar`,
  `mineLogin-bridge-1.10.0.jar`), bez źródeł. Zostały zdekompilowane (Vineflower) wyłącznie na potrzeby
  analizy: ok. 46 tys. linii proxy i 3 tys. linii bridge'a. mineLogin jest kodem zamkniętym (zaciemniony
  moduł licencji `secure/*`), dlatego **przepisujemy od zera** — przenosimy zachowanie, nie kod.
- **Infrastruktura sieci** (na podstawie JustBans, gałąź `dev`): Velocity 3.4, Paper 26.2, Java 25,
  Foundation 6.10.2, MariaDB przez HikariCP, Redis przez Jedis z podpisem HMAC, układ modułów
  `common` / `velocity` / `paper`. **Redis jest już w sieci, więc bridge idzie przez Redisa, a nie PluginMessaging.**
- **Obecny szablon Authenticator** — literówka w pakiecie (`pl.kingdomcraft.autenticator`, brak „h”),
  Java 1.8, Paper 1.21.1. Do poprawy w etapie 1.

### 0.1 Błędy mineLogin, które naprawiamy

| # | Problem | Gdzie |
|---|---|---|
| 1 | **Krytyczny** (wniosek z lektury kodu, nie testowane na żywo): w trybie PMC nikt nie sprawdza źródła wiadomości. Listener na proxy nie sprawdza `getSource()` i nie oznacza wiadomości jako obsłużonej, a Velocity przekazuje do backendu wiadomości klienta na kanałach, których proxy nie zarejestrowało. Zmodyfikowany klient może wysłać `ml:sa-<serwer>` i bridge go „zaloguje”. | `VelocityPacketCommunicationListener`, `SpigotCommunicationListener` |
| 2 | PMC wysyła przez pierwszego lepszego gracza na serwerze; na pustym serwerze nie wysyła nic. Każde `request()` rejestruje nowy kanał i listener o nazwie UUID, których nikt nie wyrejestrowuje (wyciek). Po timeoucie zwraca `null`. | `ProxyPluginMessageBroker`, `SpigotPluginMessageBroker` |
| 3 | Odmrożenie gracza ma tylko 10 prób co 1 tick — jeśli gracz dojdzie później, zostaje zamrożony na stałe. Sygnał `ml:sc` idzie ze sztywnym opóźnieniem 20 ticków. | `SuccessAuthenticationPacketListener`, `PlayerJoinListener` |
| 4 | Przy wejściu na serwer logowania proxy wysyła „SuccessAuthentication” także graczowi niezalogowanemu. | `ServerTransferAuthorizationFlow` |
| 5 | Jeden globalny `lastConnect` na całe proxy zamiast limitu per IP: każde wejście blokuje wszystkich na `betweenConnections` (łatwa blokada botami), pole nie jest bezpieczne wątkowo. | `PreLoginValidationFlow` |
| 6 | Czat i komendy blokowane na proxy przez `ChatResult.denied()` — klienci 1.19.1+ podpisują wiadomości, Velocity nie może ich odrzucić i rozłącza gracza. | `VelocityListeners.onChat/onCommand` |
| 7 | Schemat: PK na `username`, ale zapytania `UPPER(username)=UPPER(?)` — indeks nie działa, każde logowanie skanuje tabelę. `uuid` bez indeksu, czasy w typie `LONG` (w MariaDB = MEDIUMTEXT), `paid` jako VARCHAR `'true'`, zapis przez `REPLACE INTO` (nadpisuje cały wiersz, równoległe zapisy gubią się). | `JdbcQueries` |
| 8 | Cache Caffeine bez TTL i limitu rozmiaru (mimo `cache.expire-after-leave` w configu) — pamięć rośnie bez końca; mutowalny `User` współdzielony między wątkami. | `UserService` |
| 9 | Konto powstaje już w PreLogin, przed uwierzytelnieniem — każdy nick bota to nowy wiersz w bazie. | `PreLoginAccountFlow.createNewUser` |
| 10 | Premium sprawdzane przez jedno losowe API (`ApiType.RANDOM`), bez API zapasowych i cache. Gdy to API padnie, nowi gracze nie wejdą. | `AuthenticationService` |
| 11 | Niepełna blokada na Paperze (brak blokady obrażeń, podnoszenia, interakcji z bytami, głodu, komend), teleport przy każdym `PlayerMoveEvent`, gracz identyfikowany po nicku zamiast UUID, `BridgeUserService` to zwykła `ArrayList` bez synchronizacji. | `bridge/core/listener/*` |

### 0.2 Co wycinamy

- **SMTP i e-mail (wymaganie):** `mailing/*`, `MailingSection`, `EmailCommand`, `RecoveryCommand`,
  `RecoveryPasswordEmail.txt`, kolumna `email`, pole e-mail w pakiecie informacji o graczu, komunikaty
  „email warning”, licznik prób odzyskiwania, zależności jakarta.mail / angus-mail / simplejavamail.
- **Proponowane do wycięcia (do potwierdzenia):** panel webowy (`panel/*`, ok. 60 klas), NATS, backendy
  Mongo i YAML, geolokacja (MaxMind), importy z AuthMe / LimboAuth / JPremium itd. (zostaje tylko import
  z mineLogin), MOTD, ping, domyślny skin, „welcome picture”, statystyki i audyt w obecnej formie.

---

## Etap 1: Inicjalizacja projektu i środowiska

1. Przebudować projekt na 3 moduły Maven (jak JustBans):
   - `authenticator-common` — bez zależności od platformy: domena, repozytoria SQL, migracje, hashowanie,
     protokół bridge'a, klient Redisa.
   - `authenticator-velocity` — Velocity API, konfiguracja w Configurate YAML, shade bibliotek z `common`.
   - `authenticator-paper` — Foundation 6.10.2 i Paper API 26.2.
2. `release 25`, pakiet `pl.kingdomcraft.authenticator`, relokacje shade (`…authenticator.libs`, Foundation
   w `…authenticator.lib`). Adventure jako `provided` (w JustBans konflikt Adventure już raz zepsuł build Papera).
3. Sprawdzone klasy z JustBans (`Database`, `DatabaseConfig`, `Migrator`, `RedisBus`, `HmacSigner`,
   `Envelope`) skopiować do `common` i dostosować. **Decyzja:** kopia vs wydzielona biblioteka
   `kingdom-commons` — rekomendacja: na teraz kopia, żeby nie wiązać cykli wydań.
4. Testy: JUnit 5. Testy integracyjne działają na prawdziwej MariaDB i Redisie podanych z zewnątrz (zmienna środowiskowa,
   `redis-server` w `PATH`), bez Testcontainers — wymaga ono demona Dockera, którego nie ma na każdej maszynie budującej.
   Bez tych usług testy są pomijane, a build przechodzi. Patrz [TESTING.md](TESTING.md).

## Etap 2: Baza danych (MariaDB 10.11+)

### Rozdzielenie połączeń publicznego i prywatnego

Wspólna sekcja w obu configach:

```yaml
database:
  use: public            # Velocity domyślnie "public", Paper domyślnie "private"
  fallback: []           # np. [public] dla Papera, jeśli sieć prywatna padnie
  endpoints:
    public:  { host: db.example.pl, port: 3306, user: auth_proxy,   password: "", ssl-mode: verify-full, properties: {} }
    private: { host: 10.0.0.5,      port: 3306, user: auth_backend, password: "", ssl-mode: disable,     properties: {} }
  pool-size: 6
  connection-timeout-ms: 10000
```

- Osobny użytkownik MariaDB na endpoint, przypięty do hosta: `'auth_proxy'@'<IP proxy>' REQUIRE SSL`,
  `'auth_backend'@'10.0.0.%'`.
- Łącze publiczne: TLS obowiązkowy, `keepaliveTime` Hikari dostrojony pod opóźnienia WAN.
- Endpointy zapasowe (`fallback`) realizuje aplikacja, nie sterownik: tryb `sequential` sterownika zakłada jednego użytkownika i
  hasło dla wszystkich hostów, a nasze dwa endpointy mają **różnych** użytkowników. Wybór następuje raz, przy starcie
  (`Database.connect`); powrót do `use` po naprawie sieci wymaga restartu. Redis (`RedisBus`) przełącza się w locie i sam wraca
  do głównego endpointu, gdy ten znów odpowiada.
- **Redis dostaje identyczny podział `public` / `private`** — proxy jest na zewnątrz, więc jego połączenie
  z Redisem też idzie przez sieć publiczną: TLS + ACL per rola.

### Schemat

Migracje wersjonowane przez `Migrator`; tylko celowane `UPDATE`, bez `REPLACE INTO`.

| Tabela | Najważniejsze kolumny |
|---|---|
| `auth_accounts` | `id` BIGINT PK; `uuid` UUID UNIQUE (stały UUID w grze); `username`; `username_lower` (kolumna generowana) UNIQUE; `premium` BOOL; `premium_uuid` UUID UNIQUE NULL; `password_hash`, `password_salt` (sól importowana z mineLogin), `hash_algo`; `registered_at`, `registered_ip` INET6; `last_login_at`, `last_login_ip` INET6 (indeks); `version` INT (blokowanie optymistyczne) |
| `auth_sessions` | `account_id` PK/FK, `ip` INET6, `expires_at` — automatyczne logowanie non-premium z tego samego IP |
| `auth_presence` | `uuid` PK, `account_id`, `state`, `method` (PREMIUM/PASSWORD/SESSION/FORCED), `epoch`, `proxy_id`, `updated_at` — trwały stan „kto jest zalogowany”, fallback dla Papera bez Redisa |
| `auth_ip_blocks` | `ip` PK, `until`, `reason` |
| `auth_premium_cache` | `username_lower` PK, `premium_uuid` NULL, `checked_at` — cache odpowiedzi Mojanga z TTL |
| `auth_audit` (opcjonalnie) | logowania, błędne hasła, akcje adminów; z okresem przechowywania |

- **Hashowanie:** nowe hasła domyślnie BCrypt (opcjonalnie Argon2id w configu) — dzięki temu przy awaryjnym rollbacku do
  mineLogin nowe konta nie zostaną zablokowane. Weryfikacja starych hashy z mineLogin: BCRYPT, SHA256 i ARGON2. mineLogin trzyma
  hash i sól w osobnych kolumnach (sól jest doklejana do hasła przed hashowaniem), dlatego schemat ma `password_salt`. Po udanym
  logowaniu rehash na nowy algorytm.
- **Import z mineLogin:** `/authadmin migrate minelogin` — odczyt tabeli `mineLogin` tylko do odczytu,
  mapowanie `paid` → `premium` (`premium_uuid` = `uuid`), hashe i sól, IP, czasy (w mineLogin są to milisekundy w typie `LONG`);
  tryb dry-run z raportem; idempotentny. Adresy IP mogą być zahashowane (opcja `address-hashing` w mineLogin) — wtedy nie da się ich
  odzyskać i kolumny zostają puste. Importer powstanie razem z logiką logowania (etap 3).

## Etap 3: Velocity (proxy = jedyne źródło decyzji)

1. **`PreLoginEvent` w `EventTask.async`:**
   - Walidacja: nick (regex, długość), limit połączeń per IP (token bucket w Caffeine) zamiast globalnego
     `lastConnect`, blokada IP, „nick już online”.
   - Decyzja:
     - konto premium → `forceOnlineMode()` (Mojang potwierdza własność konta);
     - konto non-premium → `forceOfflineMode()` (logowanie hasłem);
     - nowy nick → `PremiumResolver`: Caffeine → `auth_premium_cache` → API Mojanga → mirrory z configu,
       każde z timeoutem. Nick istnieje w Mojangu → tryb online.
   - Wszystkie API niedostępne + nowy nick → odmowa „spróbuj za chwilę” (fail-closed). Istniejące konta
     nie potrzebują API.
   - Polityka nicków premium w configu: `PROTECT` (domyślna) lub `ALLOW_CLAIM_AUTO` (jak w mineLogin:
     nieudane logowanie online → przy kolejnym połączeniu konto offline).
2. **`GameProfileRequestEvent`:** ustawia stały `uuid` konta (żeby `/premium` nie kasował danych na
   serwerach). **Wiersz konta premium powstaje dopiero po udanej autoryzacji w Mojangu**, konto non-premium
   dopiero przy `/register` (naprawa błędu #9).
3. **Sesja w pamięci:** maszyna stanów `PENDING_LOGIN | PENDING_REGISTER → AUTHENTICATED`; premium i ważna
   sesja IP od razu `AUTHENTICATED`.
4. **Routing:**
   - `PlayerChooseInitialServerEvent`: niezalogowani → pula serwerów logowania (najmniej graczy),
     zalogowani → lobby / forced host.
   - `ServerPreConnectEvent`: niezalogowany nie wejdzie na serwer spoza puli logowania.
5. **Komendy logowania na proxy** (`SimpleCommand`, bez Brigadiera: argument `word()` psuł hasła ze znakami specjalnymi):
   `/register`, `/login`, `/changepassword`, `/unregister`, `/premium` (z potwierdzeniem), `/cracked`.
   - Hasło nigdy nie trafia na Paper ani do jego logów. W `velocity.toml` `log-command-executions` musi być `false`.
   - Hashowanie na osobnej, ograniczonej puli wątków; pełna kolejka to „spróbuj za chwilę”, nie zawieszony login.
   - Limit prób per IP i per konto, czasowa blokada IP.
   - Czatu i komend z podpisem **nie blokujemy na proxy** — robi to Paper (błąd #6).
6. **Po każdej zmianie stanu gracza:** zapis `auth_presence` (DB, z rosnącym `epoch`) → wiadomość `AUTH_STATE` do serwera, na którym
   gracz jest (patrz „Bridge” w etapie 4) → przeniesienie na właściwy serwer (zalogowany z poczekalni do lobby, wylogowany do poczekalni).
7. Scheduler (co sekundę) wyrzuca po przekroczeniu czasu na logowanie, przypomina o logowaniu co 10 s i sprząta sesje połączeń, po których
   nie przyszedł `DisconnectEvent`. `DisconnectEvent`: zamknięcie sesji i usunięcie wiersza `auth_presence`.
8. Dodatkowe zabezpieczenie: proxy nie przekazuje i nie czyta wiadomości PMC od klientów w naszej przestrzeni nazw (patrz „Bridge”).
9. `velocity.toml`: `online-mode=true`, `player-info-forwarding-mode=modern` z sekretem. Porty Paperów
   otwarte w firewallu tylko dla IP proxy — inaczej da się wejść na backend z pominięciem proxy.
10. **Zmiany względem pierwotnego opisu** (z powodów w sekcji „Odstępstwa”):
    - sesja otwiera się w `LoginEvent` (`PostOrder.LATE`), nie w `PostLoginEvent`: można odmówić wejścia, zanim gracz w ogóle trafi na serwer,
      a login odrzucony wcześniej przez inny plugin (np. ban) nie dostaje sesji;
    - decyzja online/offline zapada w `PreLoginEvent` na `PostOrder.LAST`, UUID w `GameProfileRequestEvent` też `LAST` (zmieniamy tylko UUID,
      skiny dodane przez inne pluginy zostają);
    - routing jest wymuszany w `ServerPreConnectEvent` (`LAST`), a nie tylko przy wyborze pierwszego serwera, więc żadna droga na serwer
      (komenda, inny plugin, forced host) nie omija poczekalni;
    - teksty dla graczy: katalogi `pl` i `en` + nadpisania w configu ([MESSAGES.md](MESSAGES.md)).

## Etap 4: Paper (wyłącznie Foundation) i bridge

### Foundation

- `SimplePlugin`, `SimpleSettings` (endpoint domyślnie `private`, Redis, sekret, rola serwera `AUTH` / `GAME`,
  spawn logowania), `SimpleLocalization`.
- Listenery przez `@AutoRegister`. `SimpleCommandGroup /auth` (nie `/authadmin`, patrz „Odstępstwa”): info, unregister, changepassword,
  forcelogin, setpremium / setcracked, migrate, status.
- Schedulery Foundation (`Common.runAsync` / `runLater`), `Debugger`, `SimpleExpansion`
  (PAPI: `%auth_premium%`, `%auth_registered%`).

**Dostęp do bazy:** repozytoria z `common` (Hikari), jak w JustBans. **Decyzja:** Foundation `SimpleDatabase`
dublowałby SQL, który i tak musi istnieć w `common` dla Velocity — rekomendacja: warstwa z `common`
opakowana w Foundation.

### Bramka (domyślnie wszystko zablokowane)

- `AsyncPlayerPreLoginEvent` na serwerach `GAME`: brak stanu `AUTHENTICATED` (Redis, a bez Redisa DB) →
  nie wpuszczamy. Druga linia obrony na wypadek błędu proxy.
- `PlayerJoinEvent`: gracz od razu zamrożony; asynchronicznie odczyt `auth:state:<uuid>` (bez Redisa:
  `auth_presence`); odmrożenie przez scheduler na głównym wątku. Bez ponawiania co tick i sztywnych opóźnień.
- Jeden strażnik dla wszystkich zdarzeń:
  - ruch — porównanie pozycji i `setTo(from)` zamiast teleportu;
  - czat — `AsyncChatEvent`;
  - komendy — `PlayerCommandPreprocessEvent`; `PlayerCommandSendEvent` ukrywa podpowiedzi;
  - interakcje, interakcje z bytami, kopanie i stawianie bloków;
  - ekwipunek (open, click, drag), drop, pickup, zamiana rąk;
  - obrażenia otrzymywane i zadawane, głód;
  - opcjonalnie ukrywanie niezalogowanych graczy.
- Stan w `ConcurrentHashMap<UUID, …>`; wszystko po UUID.

### Bridge: trzy tryby, jeden podpisany protokół (v1)

Proxy stoi **poza** siecią prywatną, więc Redis nie jest wymagany (kolejna usługa wystawiona do internetu). `bridge.mode` w configu proxy
i Papera:

| Tryb | Co to jest | Kiedy |
|---|---|---|
| `plugin-messaging` (**domyślny**) | Ta sama podpisana koperta w plugin message na kanale `authenticator:bridge`, przez połączenie gracza, którego dotyczy zmiana. | Zwykła sieć; zero dodatkowych usług. |
| `redis` | Ta sama koperta przez Redis (dociera też do serwerów bez graczy). | Gdy Redis już jest i chcesz natychmiastowości także dla pustych serwerów. |
| `database-only` | Bez wiadomości; Paper czyta `auth_presence`. | Najprostszy układ; też tryb shadow. |

Wspólne zasady (kod: `BridgeTransport`, `PluginMessagingTransport`, `RedisTransport`, `NullTransport`, `EnvelopeVerifier`):

1. **Baza jest źródłem prawdy, wiadomość tylko przyspiesza.** Wiadomość może zginąć (serwer pusty, gracz w trakcie zmiany serwera, restart),
   więc nic od niej nie zależy: proxy zapisuje stan w `auth_presence` *zanim* go ogłosi, a Paper przy każdym wejściu gracza
   (`AsyncPlayerPreLoginEvent` / `PlayerJoinEvent`) sprawdza stan w lokalnym cache, a gdy go nie ma — w `auth_presence`. PMC służy tylko do
   natychmiastowego (0 ticków) powiadomienia serwera, na którym gracz właśnie jest.
2. **Koperta podpisana HMAC-SHA256:** napis do podpisu `authenticator|v|id|type|ts|node|canonical(payload)`; `id` to jednorazowy nonce,
   odrzucane są: zły podpis, zbyt duży rozjazd zegara (`max-skew-seconds`), powtórzenie tego samego `id` (replay), wiadomość > 16 KiB,
   zła wersja. Ta sama weryfikacja dla Redisa i PMC.
3. **Niezależnie od HMAC proxy sprawdza źródło wiadomości.** Kanał jest zarejestrowany, więc Velocity zgłasza każdą wiadomość na nim w
   `PluginMessageEvent`. Źródło `ServerConnection` to zaufany serwer; źródło `Player` (klient) — wiadomość jest odrzucana **przed
   parsowaniem** (`ForwardResult.handled()`, więc nie trafia też dalej do serwera). Naprawia to lukę #1 z mineLogin.
4. **Kierunki:** proxy → serwer: `AUTH_STATE {uuid, state, method, epoch}`; serwer → proxy: `ACCOUNT_CHANGED {uuid, change}`. Proxy nie
   reaguje na treść `ACCOUNT_CHANGED`: to tylko „spójrz na tego gracza teraz”, a co się zmieniło, czyta z bazy. Sfałszowana wiadomość
   może więc co najwyżej wywołać jedno odczytanie wiersza.
5. **`AUTH_STATE` idzie przez połączenie gracza, którego stan się zmienił** (po `/login` i `/register`, po wejściu na serwer), nie „do
   serwera X”: PMC nie ma adresata innego niż gracz.
6. **Zdarzenia admina z Paperów** (gracz może być na innym serwerze, serwer może być pusty, gracz może zmieniać serwer) idą przez bazę:
   - zmiana/usunięcie konta: `UPDATE`/`DELETE` w `auth_accounts` (każda zmiana podbija `version`);
   - wymuszone logowanie/wylogowanie: wiersz w `auth_presence` ze `state` `AUTHENTICATED`/`method` `FORCED` (albo `PENDING_*`),
     własnym `proxy_id` (= `node-id` Papera, **musi być inny niż węzła proxy**) i `epoch` + 1;
   - proxy co `database-poll-seconds` (domyślnie 5 s, `StateWatcher`) porównuje `versions` kont i wiersze `auth_presence` swoich graczy
     i reaguje: usunięte konto → gracz wraca do rejestracji i na serwer logowania; zmiana typu konta → gracz jest wyrzucany z prośbą o
     ponowne wejście; wymuszony login/logout → stosowany. Z obcego węzła przyjmowany jest **tylko** wymuszony login i wylogowanie
     (nikt inny nie ma powodu twierdzić, że hasło zostało wpisane); wiersz starszy niż ostatni zapis proxy jest ignorowany;
   - `ACCOUNT_CHANGED` (PMC lub Redis) skraca to opóźnienie do zera: proxy sprawdza od razu tego jednego gracza.
7. **Awaria** każdego transportu nie zatrzymuje logowania: stan i tak jest w bazie, Paper co 2 s sprawdza `auth_presence` dla
   zamrożonych graczy. Po powrocie Redisa wszystko wraca samo.
8. Redis dodatkowo: klucze z TTL i blokada (`put`/`get`/`tryLock`), endpointy `public`/`private` z fallbackiem i powrotem do głównego,
   TLS z weryfikacją nazwy hosta.

## Etap 5: Testy i bezpieczne wdrożenie

1. **Jednostkowe:** zgodność hashy z mineLogin i rehash; tabela decyzji `PremiumResolver`; maszyna stanów;
   walidacja nicków; podpis i replay koperty; wybór endpointu `public` / `private` i fallbacków.
2. **Integracyjne (prawdziwa MariaDB i Redis, patrz [TESTING.md](TESTING.md)):** repozytoria, migracje, import z tabeli `mineLogin`,
   cały przebieg logowania (`AuthService`, `ProxyCore`), bridge na prawdziwym Redisie.
3. **Staging (docker-compose):** Velocity + Paper `AUTH` + Paper `GAME`. Scenariusze:
   - premium: nowy, istniejący, zmiana nicku;
   - non-premium: rejestracja, logowanie, złe hasło, timeout, sesja IP;
   - klient non-premium na nicku premium;
   - `/premium` i `/cracked`;
   - awaria Redisa, awaria DB, restart proxy z graczami online;
   - **próby ataku:** sfałszowane plugin messages, bezpośrednie połączenie z backendem;
   - klienci 1.19.1+ z podpisanym czatem;
   - burza logowań botów.
4. **Tryb shadow na produkcji:** `dry-run: true` na proxy obok działającego mineLogin — plugin liczy decyzję
   premium / non-premium i tylko ją loguje. Kilka dni porównań z mineLogin, zero ryzyka dla graczy.
5. **Przełączenie w oknie serwisowym:** backup DB → końcowy import przyrostowy → jednoczesna podmiana JAR-ów
   na proxy i wszystkich Paperach (protokoły niekompatybilne). Tabela `mineLogin` zostaje nietknięta.
6. **Rollback:** przywrócenie JAR-ów mineLogin. **Do decyzji:** konta założone po przełączeniu — eksport
   zwrotny (tylko BCrypt) albo ponowna rejestracja.
7. **Po wdrożeniu:** `/authadmin status`, alerty o rozłączeniu Redisa i DB, przegląd `auth_audit`.

---

## Decyzje (zatwierdzone)

1. **2FA (TOTP z mapami QR) i captcha:** odłożone do v2. W v1 liczy się stabilność podstawowego logowania.
2. **Geyser / Floodgate:** brak obsługi graczy Bedrock w v1.
3. **Polityka nicków premium:** domyślnie `PROTECT`, z możliwością zmiany w configu na `ALLOW_CLAIM_AUTO`.
4. **Serwer logowania:** rola serwera Paper (`AUTH` lub `GAME`) w configu. Domyślnie dedykowany serwer `AUTH` (poczekalnia), z którego
   po zalogowaniu gracz trafia na `GAME`. Blokada zdarzeń na `AUTH` musi być w 100% szczelna.
5. **Migracja z mineLogin:** tak. Hashe: BCRYPT oraz SHA256 / ARGON2. Nowe hasła: BCrypt (opcjonalnie Argon2id).
6. **Kod wspólny z JustBans:** kopia klas do `authenticator-common`, pakiet `pl.kingdomcraft.authenticator`. Bez wspólnej biblioteki.
7. **Baza na Paperze:** warstwa z `authenticator-common` (HikariCP), bez `SimpleDatabase` z Foundation.
8. **Wycinane:** panel webowy, NATS, Mongo, YAML, MaxMind, importy inne niż mineLogin, MOTD, ping, skiny, welcome picture. Zostaje lekki
   `auth_audit` (logowania, błędne hasła, akcje adminów).

---

## Status realizacji

### Etapy 1 i 2 — zrobione w `authenticator-common`

* Trzy moduły Maven (`common`, `velocity`, `paper`), Java 25, pakiet `pl.kingdomcraft.authenticator`, Paper 26.2, Foundation 6.10.2.
* Baza: HikariCP + MariaDB, endpointy `public` i `private`, `use` i `fallback`, TLS (`ssl-mode`), migracje (`Migrator.migrate` dla
  proxy, `Migrator.verify` dla serwerów Paper). Schemat: 6 tabel w 4 migracjach. Opis i uprawnienia kont: [DATABASE.md](DATABASE.md).
* Redis: Jedis, `Envelope` + HMAC-SHA256, endpointy `public` i `private` z fallbackiem i powrotem do głównego, TLS z weryfikacją
  nazwy hosta i własnym trust store, odrzucanie podróbek, starych i powtórzonych wiadomości. Klucze z TTL i blokada `tryLock`.
* Wspólny parser konfiguracji (`ConfigSection`) dla obu platform; klucze w stylu Velocity (`pool-size`) i Foundation (`Pool_Size`).

### Etap 3 — zrobiony (logika w `authenticator-common`, adapter w `authenticator-velocity`)

Cała logika, która nie potrzebuje Velocity, jest w `common` i testowana na prawdziwej MariaDB i Redisie; moduł Velocity jest cienką
nakładką (zdarzenia, komendy, połączenia).

* **Transport bridge** (`BridgeTransport`): `plugin-messaging` (domyślny), `redis` (opcjonalny, sprawdzony `RedisBus`), `database-only`.
  Ta sama podpisana koperta, wspólna weryfikacja (`EnvelopeVerifier`), odrzucanie wiadomości od klientów przed parsowaniem. Szczegóły
  w Etapie 4 → „Bridge”.
* **Decyzja o połączeniu** (`PreLoginDecider`): limit połączeń per IP, nick, blokada IP, nick już online, konto, a dla nowego nicku
  `PremiumResolver` (pamięć → baza → Mojang/Ashcon/MineTools, nieznany nick = odmowa). `PROTECT` i `ALLOW_CLAIM_AUTO`.
* **Logowanie** (`AuthService`): sesja jako maszyna stanów, `/register`, `/login`, `/changepassword`, `/unregister`, `/premium`,
  `/cracked` (dwa kroki), limity per IP, blokada IP, hashowanie na osobnej ograniczonej puli (`BUSY` zamiast zawieszenia), BCrypt
  (domyślnie) lub Argon2id, weryfikacja haseł z mineLogin (BCrypt, SHA-256, SHA-512, Argon2; `password_salt`) i podmiana hasha przy
  najbliższym logowaniu, sesja IP, stały UUID konta.
* **Zmiany za plecami proxy** (`StateWatcher`): konto usunięte, zmienione, wymuszony login/logout, zgubiony wiersz `auth_presence`.
* **Sprzątanie** (`Maintenance`), **import z mineLogin** (`MineLoginImporter`, `/authadmin migrate minelogin [dry-run]`),
  **tryb shadow** (`ShadowComparator`, `dry-run: true`), **teksty** (`Messages`: `pl` i `en`, [MESSAGES.md](MESSAGES.md)),
  **routing** (`RoutingPolicy`), **składanie całości** (`ProxyCore`). Opis dla admina: [PROXY.md](PROXY.md).
* **Adapter Velocity:** `LoginListener`, `ShadowListener`, `RoutingListener`, `VelocityPluginMessaging`, `PlayerCommand`, `AdminCommand`,
  `VelocityRuntime`.
* Testy: patrz [TESTING.md](TESTING.md). Mutowane (wyłączane po kolei, za każdym razem wywala się właściwy test): źródło wiadomości PMC,
  reguły `StateWatcher` (co wolno obcemu węzłowi), routing poczekalni, obietnice trybu shadow, odrzucanie nieznanych kluczy tekstów.

### Etap 4 — zrobiony (logika w `authenticator-common`, wtyczka w `authenticator-paper`)

Tak jak na proxy: wszystko, co nie potrzebuje Papera, jest w `common` i testowane na prawdziwej bazie; moduł Paper to cienka nakładka na
Foundation. Opis dla admina: [PAPER.md](PAPER.md).

* **`Gate`** (stan w pamięci, `ConcurrentHashMap<UUID, …>`): nieznany gracz = niezalogowany, więc serwer, który się uruchamia albo ma
  zepsutą bazę, jest zamknięty. Stan niesie `epoch` proxy; starszy raport jest ignorowany, ten sam dwa razy nic nie zmienia.
* **`JoinVerifier`**: drzwi i obserwacja. `checkLogin` (w `AsyncPlayerPreLoginEvent`, poza głównym wątkiem) czyta rekord proxy z
  `auth_presence`: brak rekordu → odmowa; `GAME` i niezalogowany → odmowa; błąd bazy → odmowa. Potem co `Poll_Seconds` czytana jest baza
  dla wszystkich graczy na serwerze, a wiadomość z mostu przyspiesza zmianę do zera. Dwa źródła, żadne nie wymaga drugiego.
* **`Gatekeeper`** (Paper): tabela zdarzeń, każde anulowane na pierwszym i ostatnim priorytecie; ruch (tylko obrót głowy, `setTo` z oryginalnym
  obrotem), teleporty (tylko własny na spawn logowania), komendy i ich podpowiedzi, czat, interakcje, bloki, ekwipunek, upuszczanie,
  podnoszenie, zamiana rąk, obrażenia (zadawane, otrzymywane, pociski), głód, portale, ukrywanie niezalogowanych. `GatekeeperTest` zatrzymuje
  budowanie, gdy zdarzenie wypadnie z listy.
* **`BackendCore`**: baza (`Migrator.verify`, nigdy `migrate`), most (`plugin-messaging` / `redis` / `database-only`), `AdminService`, importer.
* **`AdminService`** + `/auth`: `info`, `unregister`, `changepassword`, `forcelogin`, `forcelogout`, `setpremium`, `setcracked`, `migrate`,
  `status`. Każda akcja to zmiana w bazie (`auth_audit` z nazwą admina) i, jeśli się da, `ACCOUNT_CHANGED` do proxy, żeby zareagowało od razu.
* **PlaceholderAPI** (`SimpleExpansion`): `state`, `premium`, `registered`; tylko pamięć, nigdy baza.
* **Lokalizacja** `pl` / `en` (`SimpleLocalization`), **ustawienia** (`SimpleSettings`) i część wspólna z proxy (`BackendSettings`).
* Testy: patrz [TESTING.md](TESTING.md). Mutowane: drzwi (`GAME`, nieznany gracz, błąd bazy, zapomnienie starego stanu), reguły `Gate`,
  `AdminService`, tabela `Gatekeeper` (ruch na każdej osi, teleporty, podpowiedzi komend, pociski), epoch.

**Błąd znaleziony w trakcie (dotyczył też Etapu 3):** `auth_presence` ma `ON DELETE CASCADE` na koncie, więc usunięcie konta kasuje wiersz,
a następny zapis zaczynał liczyć `epoch` od 1. Serwer pamiętający wyższy numer brał nowy stan za spóźnioną wiadomość i ignorował go, więc
gracz z usuniętym kontem zostawał „zalogowany” na serwerze gry. Teraz `PresenceRepository.save` dostaje dolną granicę (`epoch` sesji + 1)
i `epoch` połączenia tylko rośnie. Test: `BackendCoreIntegrationTest` (proxy i serwer na jednej bazie, prawdziwy most) i
`RepositoriesIntegrationTest`.

### Odstępstwa od pierwotnego planu i dlaczego

* **Fallback bazy w aplikacji, nie przez `sequential`** — patrz Etap 2.
* **Czas zawsze przez `LocalDateTime` w UTC (`Sql.setInstant` / `Sql.instant`).** Test na JVM-ie ustawionym na `Pacific/Auckland`
  pokazał, że `setTimestamp` i `setObject(Instant)` zapisują czas strefy JVM-a mimo `connectionTimeZone=UTC`. Ten sam błąd
  siedzi w kodzie JustBans, z którego kopiowaliśmy (tam ukryty, bo serwery chodzą w UTC).
* **IPv4 w `INET6` jako `::ffff:a.b.c.d`** — MariaDB odrzuca zwykły tekst IPv4 w tej kolumnie (sprawdzone).
* **`password_salt` w `auth_accounts`** — potrzebne do hashy z mineLogin.
* **Kolumny „do kiedy” jako `DATETIME(3)`** zamiast `TIMESTAMP`, żeby bezterminowe blokady nie wpadły w rok 2038.
* **Bez Testcontainers** — patrz Etap 1.
* **Opcjonalny `ssl-trust-store` dla Redisa** (certyfikat z własnego CA); po stronie bazy robi to sterownik przez `properties`.
* **Redis opcjonalny, domyślnie PluginMessaging + baza** (decyzja po zatwierdzeniu planu): proxy jest poza siecią prywatną, więc nie
  wymuszamy kolejnej usługi przez WAN. Nic w logice nie zależy od dostarczenia wiadomości.
* **Sesja w `LoginEvent`, nie w `PostLoginEvent`** i **`SimpleCommand` zamiast Brigadiera** — patrz Etap 3, punkt 10 i 5.
* **Kolejność wierszy importu zależy od czasu rejestracji**, nie od kolejności skanowania tabeli: przy dwóch nickach różniących się
  tylko wielkością liter (możliwe w mineLogin przy rozróżniającym wielkość collation) nick zachowuje ten, kto zarejestrował się pierwszy,
  a próba na sucho i prawdziwy import nie mogą się rozjechać.
* **Komenda na Paperze to `/auth` (alias `/authenticator`), nie `/authadmin`.** Proxy ma komendę `/authadmin` (status i import), a komenda
  zarejestrowana na proxy wygrywa z komendą serwera backendowego, więc `/authadmin` na Paperze byłby z gry nieosiągalny. Etykiety zmienia się
  w `Command_Aliases`.
* **Placeholdery to `%authenticator_state%`, `%authenticator_premium%`, `%authenticator_registered%`, nie `%auth_…%`.** Foundation nazywa
  ekspansję nazwą wtyczki (`SimplePlugin.getNamed()`, sprawdzone w bytecodzie); przejście na `auth_` wymagałoby zmiany nazwy wtyczki.
* **Kanał PMC na Paperze przez Bukkit `Messenger`**, nie przez `BungeeListener` Foundation: ten ma własny protokół wiadomości, a tu idą
  surowe bajty podpisanej koperty.
* **Rola domyślna to `GAME`** (ścisła): zapomniana rola odmawia, nie wpuszcza.
* **`Yes` / `No` jako klucze YAML** są w YAML 1.1 wartościami `true` / `false`; teksty placeholderów to `True_Text` / `False_Text`.
* **Symulacja Velocity (`tools/velocity-simulation`) usunięta**, bo prawdziwe API jest dostępne; jej miejsce zajęły testy modułu Velocity na
  prawdziwych klasach zdarzeń.
* **Przypomnienia o logowaniu co 10 s i język komunikatów (`language: pl|en`)** — nie były w planie; tanie, a bez nich gracz na poczekalni
  nie wie, co ma wpisać.

### Czego jeszcze nie zweryfikowano

* **Moduły `velocity` i `paper` kompilują się z prawdziwym API** (velocity-api 3.4.0-SNAPSHOT, paper-api 26.2, Foundation 6.10.2) i przechodzi
  `mvn clean test-compile` na całym reaktorze. Wszystkie wywołania, które na Etapie 3 pisałem z pamięci (`PreLoginEvent#getUniqueId`,
  `ForwardResult.handled()`, `PreLoginComponentResult`, `GameProfile#withId`, `ServerConnection#sendPluginMessage`, `ProxyServer#getConfiguration`
  i reszta listy), okazały się zgodne: nie trzeba było zmienić ani jednej sygnatury.
* **Nic nie było uruchomione na prawdziwym Velocity ani Paperze.** Testy używają prawdziwych klas zdarzeń i API, ale gracze, serwery i `Server`
  to atrapy; w tym środowisku nie ma serwera Minecrafta. Do potwierdzenia na stagingu (Etap 5, punkt 3): kolejność handlerów (`PostOrder`)
  między pluginami; to że `PluginMessageEvent` jest zgłaszany dla klienta na zarejestrowanym kanale; `DisconnectEvent` po odrzuconym
  logowaniu; zachowanie `PlayerCommandPreprocessEvent` przy komendach z podpisem (1.19.1+); to że `hidePlayer` i `teleportAsync` zachowują
  się tak, jak zakładam; wiadomości PMC przychodzące na Paperze zanim wykona się `PlayerJoinEvent`.
* **Sprawdzenie zachowania w przeglądarce / kliencie** (wygląd komunikatów MiniMessage i `&`) nie było możliwe.

### Do zrobienia później, poza tym repozytorium

* **JustBans: przenieść poprawkę `Sql.setInstant` / `Sql.instant`.** `justbans-common/.../database/Sql.java` zapisuje czas przez
  `setTimestamp(Timestamp.from(...))` (linia 35) i czyta przez `getTimestamp(...).toInstant()` (linie 50–52), co zapisuje czas strefy
  JVM-a. Wzór: `Sql.setInstant` i `Sql.instant` w `authenticator-common` oraz test z JVM-em ustawionym na `Pacific/Auckland`
  (sprawdza wartość zapisaną przez serwer, a nie odczyt przez ten sam kod). Dotknie też testów JustBans, które zakładają stary format.

### Etap 5 — przygotowany (artefakty, staging, runbook); sam staging jeszcze nie uruchomiony

* **JAR-y sprawdzone** (`tools/check-jars.sh`, uruchamiany też przez `staging/prepare.sh`): w obu tylko `pl/kingdomcraft/authenticator/**`,
  biblioteki przeniesione pod `pl.kingdomcraft.authenticator.libs.*` (Foundation: `.lib.*`), brak `paper-api`, `velocity-api`, `net.kyori.adventure`,
  `slf4j`, Guice; poprawione: adnotacje (checkerframework, jetbrains, errorprone, jspecify) wykluczone, `net.kyori.option` przeniesione,
  nieprzeniesione klasy `META-INF/versions/**` usunięte; deskryptory (`velocity-plugin.json`, `plugin.yml`) z właściwymi klasami głównymi.
* **`staging/`**: `prepare.sh` (jary, sekrety, wyrenderowane konfiguracje, sprawdzone przez prawdziwe parsery) + `docker-compose.yml`
  (MariaDB, Velocity, Paper `AUTH`, Paper `GAME`) + `README.md` + `CHECKLIST.md` (otwarte punkty z Etapu 3/4). **Nie był uruchomiony** (brak
  Dockera): zmienne obrazów `itzg/*` i patch przekazywania Velocity są niesprawdzone, `staging/README.md` je wylicza.
* **`docs/DEPLOYMENT.md`**: konta SQL (`auth_proxy` z `REQUIRE SSL`, `auth_backend`), tryb shadow obok mineLogin i czytanie porównania,
  okno serwisowe z importem, przełączeniem i rollbackiem.
* **JustBans**: poprawka `Sql.setInstant`/`Sql.instant` przeniesiona z testem (JustBans, gałąź `dev`, `2e68477`). Test z JVM-em w `Pacific/Auckland`
  na starym kodzie zapisuje czas przesunięty o 12 godzin, na nowym przechodzi.

### Dalej

Uruchomić staging i przejść `staging/CHECKLIST.md`, potem shadow na produkcji (DEPLOYMENT.md, krok 2), potem okno serwisowe (krok 3).
