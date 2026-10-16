# Authenticator — plan architektury i wdrożenia

> Plugin autoryzacyjny dla sieci Velocity + Paper 26.2: funkcjonalny klon `mineLogin`, przepisany od zera.
> Paper oparty wyłącznie na MineAcademy Foundation, MariaDB jako źródło prawdy, Redis jako bridge proxy ⇄ backend.

Status: **plan do zatwierdzenia** — kod nie jest jeszcze pisany.

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
4. Testy: JUnit 5 + Testcontainers (MariaDB, Redis), jak w JustBans. Konfiguracja CI do ustalenia.

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
- Endpointy zapasowe przez tryb `sequential` MariaDB Connector/J.
- **Redis dostaje identyczny podział `public` / `private`** — proxy jest na zewnątrz, więc jego połączenie
  z Redisem też idzie przez sieć publiczną: TLS + ACL per rola.

### Schemat

Migracje wersjonowane przez `Migrator`; tylko celowane `UPDATE`, bez `REPLACE INTO`.

| Tabela | Najważniejsze kolumny |
|---|---|
| `auth_accounts` | `id` BIGINT PK; `uuid` UUID UNIQUE (stały UUID w grze); `username`; `username_lower` UNIQUE; `premium` BOOL; `premium_uuid` UUID UNIQUE NULL; `password_hash`, `hash_algo`; `registered_at`, `registered_ip` INET6; `last_login_at`, `last_login_ip` INET6 (indeks); `version` INT (blokowanie optymistyczne) |
| `auth_sessions` | `account_id` PK/FK, `ip` INET6, `expires_at` — automatyczne logowanie non-premium z tego samego IP |
| `auth_presence` | `uuid` PK, `account_id`, `state`, `method` (PREMIUM/PASSWORD/SESSION/FORCED), `epoch`, `proxy_id`, `updated_at` — trwały stan „kto jest zalogowany”, fallback dla Papera bez Redisa |
| `auth_ip_blocks` | `ip` PK, `until`, `reason` |
| `auth_premium_cache` | `username_lower` PK, `premium_uuid` NULL, `checked_at` — cache odpowiedzi Mojanga z TTL |
| `auth_audit` (opcjonalnie) | logowania, błędne hasła, akcje adminów; z okresem przechowywania |

- **Hashowanie:** nowe hasła Argon2id (lub BCrypt — wybór w configu). Weryfikacja starych hashy z mineLogin
  (domyślnie BCRYPT; dodatkowe typy tylko te, które faktycznie są w bazie). Po udanym logowaniu rehash na
  nowy algorytm.
- **Import z mineLogin:** `/authadmin migrate minelogin` — odczyt tabeli `mineLogin` tylko do odczytu,
  mapowanie `paid` → `premium`, hashe, IP, czasy; tryb dry-run z raportem; idempotentny.

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
5. **Komendy logowania na proxy** (`BrigadierCommand`): `/register`, `/login`, `/changepassword`,
   `/unregister`, `/premium` (z potwierdzeniem), `/cracked`.
   - Hasło nigdy nie trafia na Paper ani do jego logów.
   - Hashowanie na osobnej, ograniczonej puli wątków.
   - Limit prób per IP i per konto, czasowa blokada IP.
   - Czatu i komend z podpisem **nie blokujemy na proxy** — robi to Paper (błąd #6).
6. **Po udanym logowaniu:** zapis `auth_presence` (DB) → klucz `auth:state:<uuid>` w Redisie → publikacja
   `AUTH_STATE`. Opcjonalnie przeniesienie do lobby.
7. Scheduler wyrzuca po przekroczeniu czasu na logowanie. `DisconnectEvent`: sprzątanie stanu + `LOGGED_OUT`.
8. Dodatkowe zabezpieczenie: proxy odrzuca wiadomości PMC od klientów w naszej przestrzeni nazw.
9. `velocity.toml`: `online-mode=true`, `player-info-forwarding-mode=modern` z sekretem. Porty Paperów
   otwarte w firewallu tylko dla IP proxy — inaczej da się wejść na backend z pominięciem proxy.

## Etap 4: Paper (wyłącznie Foundation) i bridge

### Foundation

- `SimplePlugin`, `SimpleSettings` (endpoint domyślnie `private`, Redis, sekret, rola serwera `AUTH` / `GAME`,
  spawn logowania), `SimpleLocalization`.
- Listenery przez `@AutoRegister`. `SimpleCommandGroup /authadmin`: info, unregister, changepassword,
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

### Bridge (Redis, protokół v1)

- Koperta JSON podpisana HMAC-SHA256: `type`, `node`, `ts`, `nonce`, `protocol`. Odrzucane: zbyt duży rozjazd
  zegara, powtórzenia (replay), zła wersja protokołu.
- Proxy → Paper: `AUTH_STATE {uuid, state, method, epoch}`, `LOGGED_OUT`, `ACCOUNT_UPDATED`.
- Paper → proxy: `ACCOUNT_CHANGED` po akcji admina (proxy np. wyrzuca gracza albo wymusza logowanie).
- Brak wyścigów:
  - proxy najpierw zapisuje DB i klucz w Redisie, potem publikuje;
  - Paper najpierw subskrybuje, potem czyta klucz;
  - `epoch` odrzuca stare wiadomości; obsługa idempotentna.
- Klucze `auth:node:<id>` z TTL zastępują Hello / VersionMismatch i zasilają `/authadmin status`.
- Awaria Redisa: proxy loguje dalej przez DB; Paper co 2 s sprawdza `auth_presence` dla zamrożonych graczy.
  Po powrocie Redisa wszystko wraca samo.

## Etap 5: Testy i bezpieczne wdrożenie

1. **Jednostkowe:** zgodność hashy z mineLogin i rehash; tabela decyzji `PremiumResolver`; maszyna stanów;
   walidacja nicków; podpis i replay koperty; wybór endpointu `public` / `private` i fallbacków.
2. **Integracyjne (Testcontainers):** repozytoria, migracje, import z dumpa tabeli `mineLogin`, bridge na
   prawdziwym Redisie.
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

## Otwarte decyzje

1. 2FA (TOTP z mapami QR) i captcha w v1? Propozycja: v2.
2. Geyser / Floodgate — czy są gracze Bedrock?
3. Polityka nicków premium: `PROTECT` czy `ALLOW_CLAIM_AUTO`?
4. Osobny serwer logowania (limbo) czy lobby jako serwer logowania?
5. Migracja danych z mineLogin — tak/nie, jakie typy hashy są w bazie?
6. Kod wspólny z JustBans: kopia czy wydzielona biblioteka?
7. Baza na Paperze: warstwa z `common` (rekomendacja) czy Foundation `SimpleDatabase`?
8. Potwierdzenie listy do wycięcia z sekcji 0.2.
