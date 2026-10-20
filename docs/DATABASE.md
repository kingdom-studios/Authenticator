# Database and bridge setup

How Authenticator connects to MariaDB and Redis, what the schema looks like, and which accounts to create. Everything marked
"verified" was checked against a real MariaDB 10.11 and a real Redis 7 (see [TESTING.md](TESTING.md)).

## Public and private endpoints

The proxy sits outside the infrastructure; the Paper servers sit inside. Both read the same database and Redis, but over
different addresses. Each service has two endpoints in the config, `public` and `private`, and a `use` key that says which one
this node connects to:

| Node | `use` (default) | Typical endpoint |
|---|---|---|
| Velocity | `public` | the address reachable from the internet, over TLS |
| Paper | `private` | the address inside the network |

`fallback` lists endpoints to try, in order, when `use` cannot be reached (for example `fallback: [private]` on the proxy if it
can also reach the private network). It is optional and empty by default.

* **MariaDB:** the endpoint is chosen once at startup. If the network recovers later, restart to return to `use`.
* **Redis:** the bridge tries `use` first, then each fallback, reconnecting with backoff. While it runs on a fallback it probes the
  primary about once a minute and switches back as soon as it answers.

The sample files are [`config.yml`](../authenticator-velocity/src/main/resources/config.yml) (Velocity) and
[`settings.yml`](../authenticator-paper/src/main/resources/settings.yml) (Paper). Keys are case-insensitive and `_` equals `-`,
so both styles mean the same thing. A test loads both samples through the real parsers, so they cannot drift from the code.

### What is enforced

* The `public` endpoint may not use `ssl-mode: disable` (database) or `ssl: false` (Redis): it crosses an untrusted network.
  The config is refused at startup with the exact key, e.g. `database.endpoints.public: ...`.
* The `use` zone and every `fallback` zone must have an endpoint; a zone cannot be listed twice.
* The Redis `secret` is required and must be at least 16 characters.
* Passwords and the secret never appear in log lines, exceptions or `toString()` output (verified by tests).

### TLS to MariaDB

`ssl-mode` is `disable`, `trust`, `verify-ca` or `verify-full`:

* `trust` encrypts but does not check the certificate (protects against sniffing, not against a man in the middle).
* `verify-ca` checks the certificate chain; `verify-full` also checks that the certificate is valid for `host`.
* For a certificate from your own CA (or a self-signed one; without it the driver fails with `Self signed certificates. Either set
  sslMode=trust ...`), give the CA file in `server-ssl-cert` (`Server_Ssl_Cert` on Paper) as an ordinary path:

  ```yaml
  ssl-mode: verify-full
  server-ssl-cert: "/home/minecraft/plugins/authenticator/db-ca.pem"   # or just "db-ca.pem", in the plugin's folder
  ```

  The plugin turns it into the `file:///...` URL the driver needs. A relative path is looked for in the plugin's data folder. A value that
  already starts with `file:`, `classpath:` or `-----BEGIN CERTIFICATE-----` is passed on as written. A file that does not exist or cannot be
  read stops the plugin at startup with the exact path. `properties.serverSslCert` gets the same treatment; if both are set,
  `server-ssl-cert` wins. Other option values are passed to the driver unchanged (it does not decode them), so a value containing `&` or a
  control character is rejected.

Verified against a real server: `trust` encrypts; `verify-ca` accepts the server's own certificate and rejects another one;
`verify-full` accepts a host name that is in the certificate and rejects one that is not.

### TLS to Redis

The public endpoint must use `ssl: true`. By default the certificate must be valid for `host` (`verify-hostname: true`); turning
it off accepts any certificate Java trusts, whatever host it was issued for. For a certificate from your own CA, put it in a
PKCS12 (or `.jks`) trust store and set `ssl-trust-store` and `ssl-trust-store-password`. Without a trust store, Java's own trust
store is used, so a self-signed certificate is rejected (verified).

## Accounts

Create one account per role and pin each to the host it connects from.

```sql
CREATE DATABASE authenticator CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

-- The proxy applies schema migrations, so it is the only account that may change tables.
CREATE USER 'auth_proxy'@'<proxy public IP>' IDENTIFIED BY '<long random password>' REQUIRE SSL;
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER ON authenticator.* TO 'auth_proxy'@'<proxy public IP>';

-- Paper servers only read and write rows. They check the schema version but never change the schema.
CREATE USER 'auth_backend'@'10.0.0.%' IDENTIFIED BY '<another long random password>';
GRANT SELECT, INSERT, UPDATE, DELETE ON authenticator.* TO 'auth_backend'@'10.0.0.%';
```

Verified: with exactly these grants the proxy account applies all migrations over TLS, is refused without TLS, and the backend
account passes `verify()`, can read and write rows, and is refused when it tries to create a table. Future migrations may need
more privileges (for example `DROP`); the release notes will say so.

Start the proxy first after an update: a Paper server whose database is older than its build refuses to start and says so.

## Schema (MariaDB 10.11 or newer)

Migrations are in [`db/mariadb/`](../authenticator-common/src/main/resources/db/mariadb), listed in `migrations.list`, and run under
`GET_LOCK`, so several nodes starting at once apply each one exactly once. Never edit a released migration: add a new one.

| Table | Purpose |
|---|---|
| `auth_accounts` | One row per registered or premium player. `uuid` is the in-game UUID and never changes; `username_lower` (generated) makes names unique ignoring case; `premium` requires a `premium_uuid`; the hash and its algorithm go together; `password_salt` holds the extra value of imported mineLogin passwords; `version` supports optimistic locking. Rows are created only after the player proved who they are. |
| `auth_sessions` | Lets a non-premium player return from the same address without the password. |
| `auth_presence` | Who is online and how far they got (`PENDING_LOGIN`, `PENDING_REGISTER`, `AUTHENTICATED`), with a per-player `epoch` and the owning `proxy_id`. The proxy writes it before it announces a change; a Paper server reads it whenever a player joins and whenever no bridge message told it better, so a lost message costs nothing. A Paper server also writes it for an admin's forced login or logout, under its own `node-id` (which must differ from the proxy's), and the proxy picks that up within `bridge.database-poll-seconds`. An authenticated row needs an account and a method. Deleting an account deletes its row; the next write then keeps counting from the epoch the caller last wrote, so the epoch of a connection never goes back (a server that remembers the old number would otherwise ignore the new state as a late message). |
| `auth_ip_blocks` | Addresses locked out after too many wrong passwords. |
| `auth_premium_cache` | Mojang's answers, so a Mojang outage does not stop new players. |
| `auth_audit` | Logins, wrong passwords, admin actions. No foreign keys, so it outlives the account. |

Constraints are tested against a real server (duplicate names in another case, premium without a Mojang UUID, hash without an
algorithm, an authenticated player without an account, cascading deletes).

### Pitfalls the tests caught

* **`INET6` rejects plain IPv4 text** (`1.2.3.4`). IPv4 must be written IPv4-mapped (`::ffff:1.2.3.4`), and comparisons in SQL
  must use that form too. Always use `Sql.setIp` / `Sql.ip`.
* **Never use `setTimestamp` or `setObject(Instant)`.** The MariaDB driver then writes the wall-clock time of the JVM's default
  time zone, whatever `connectionTimeZone` says: on a machine that is not set to UTC the rows hold the wrong time and disagree with
  defaults the server fills in. Reading it back through the same call hides the mistake. Always use `Sql.setInstant` /
  `Sql.instant`, which go through `LocalDateTime` in UTC (tested with the JVM set to `Pacific/Auckland`, checking the value the
  server actually holds).
* Columns that can hold a far-future "until" value are `DATETIME(3)`, because `TIMESTAMP` ends in 2038.
