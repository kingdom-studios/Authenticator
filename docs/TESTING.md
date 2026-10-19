# Running the tests

```
mvn -pl authenticator-common test
```

Unit tests need nothing. The integration tests use real services and are skipped when they are not available, so the build
passes on a bare machine.

| Needs | Provided by | Without it |
|---|---|---|
| MariaDB 10.11+ | `AUTHENTICATOR_TEST_MARIADB=host:port:user:password` | 252 of the 503 tests in authenticator-common are skipped |
| Redis 7 | `redis-server` on the `PATH` (a throwaway process on a random port is started per test class) | the Redis tests are skipped |
| Redis with TLS | the above plus `openssl` and `keytool` (a self-signed certificate is generated per test) | the TLS tests are skipped |
| MariaDB with TLS | `AUTHENTICATOR_TEST_MARIADB_CERT` and `AUTHENTICATOR_TEST_MARIADB_OTHER_CERT` | the TLS tests are skipped |

The MariaDB user must be allowed to create and drop schemas named `authtest_*`; every test gets its own schema and drops it.

For the TLS tests the server must have TLS enabled with a certificate that is valid for the DNS name `localhost` but **not** for
the IP address `127.0.0.1` (this is what lets one test prove that host name checking is really enforced):

```
openssl req -x509 -newkey rsa:2048 -nodes -keyout db-key.pem -out db-cert.pem -days 30 -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost"
mariadbd ... --ssl-cert=db-cert.pem --ssl-key=db-key.pem --ssl-ca=db-cert.pem
export AUTHENTICATOR_TEST_MARIADB=127.0.0.1:3307:user:password
export AUTHENTICATOR_TEST_MARIADB_CERT=/path/db-cert.pem
export AUTHENTICATOR_TEST_MARIADB_OTHER_CERT=/path/any-other-cert.pem
```

In CI, run the services next to the build (for example GitHub Actions `services:` for MariaDB and Redis) and set the variables.
Testcontainers is not used: it needs a Docker daemon, which not every build machine has.

## The security tests have teeth

The checks that matter for security were mutation-tested: each was disabled in turn and exactly the test meant to catch it
failed. Disabled and caught: the HMAC signature check, the clock-skew check, the replay check and TLS host name verification.

Mutated since (40 mutations in two rounds, each disabled in turn; every one made a test in the class meant to catch it fail, and none was a compile error):
the checks of the bridge origin (a message a player's client sent is dropped before it is parsed), the watcher's rules for
what another node may write (only a forced login or a logout, never from the proxy's own id, never older than the proxy's last write),
the routing rule that keeps unauthenticated players on a waiting server, the shadow mode's promise to write nothing, and the
refusal of unknown message keys, the clean-up of expired rows, the cap on shadow-mode log lines.

## The proxy without a proxy

`ProxyCore` (in `authenticator-common`) is everything the proxy does that is not Velocity: settings, schema, login machinery, bridge,
watcher. `ProxyCoreIntegrationTest` starts it on a real database with a fake carrier standing in for PluginMessaging, and with a real Redis
for the Redis mode, and checks what is sent, who may send, and what changes.

## The Velocity and Paper modules

Both compile against the real APIs (velocity-api, paper-api, Foundation); `mvn clean test-compile` covers the whole reactor. Their tests run
without a server, with the real event and channel classes and stand-ins for players, servers and the `Server`:

* **Velocity**: the carrier on the proxy (a message from a backend server is handed on and not forwarded; one from a player's client is
  neither forwarded nor read, even when correctly signed; a bad signature; another plugin's channel is left alone; what is sent to the
  server a player is on), the reading of another plugin's pre-login result for shadow mode, the rendering of every message in both languages
  (a value can never inject formatting), and the shipped `config.yml` through Configurate.
* **Paper**: the gatekeeper with real Bukkit events (movement on every axis, only turning the head allowed, teleports, command list, chat,
  blocks, interactions, drops, damage including a frozen player's arrow, hunger, hand swaps) and a list of events that must be covered; the
  rules Foundation applies to `@AutoRegister` classes (otherwise the plugin fails to start); the shipped `settings.yml` through the same parser
  the plugin uses; both language files against the messages in the code; the placeholders.
* Everything that does not need Velocity or Paper is in `authenticator-common` and tested on a real database: `ProxyCore`, `BackendCore`
  (a proxy and a backend server on one database talking through the real bridge code, over PluginMessaging, over Redis and with no bridge),
  `JoinVerifier`, `Gate`, `AdminService`.

What these cannot show is how a real Velocity or Paper behaves (handler order between plugins, which events a client's plugin message
raises, signed commands, `hidePlayer`). That is for the staging run (plan, Stage 5).
