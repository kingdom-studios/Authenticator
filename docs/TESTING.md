# Running the tests

```
mvn -pl authenticator-common test
```

Unit tests need nothing. The integration tests use real services and are skipped when they are not available, so the build
passes on a bare machine.

| Needs | Provided by | Without it |
|---|---|---|
| MariaDB 10.11+ | `AUTHENTICATOR_TEST_MARIADB=host:port:user:password` | 207 of the 442 tests are skipped |
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

Mutated since (20 mutations, each disabled in turn; every one made a test in the class meant to catch it fail, and none was a compile error):
the checks of the bridge origin (a message a player's client sent is dropped before it is parsed), the watcher's rules for
what another node may write (only a forced login or a logout, never from the proxy's own id, never older than the proxy's last write),
the routing rule that keeps unauthenticated players on a waiting server, the shadow mode's promise to write nothing, and the
refusal of unknown message keys, the clean-up of expired rows, the cap on shadow-mode log lines.

## The proxy without a proxy

`ProxyCore` (in `authenticator-common`) is everything the proxy does that is not Velocity: settings, schema, login machinery, bridge,
watcher. `ProxyCoreIntegrationTest` starts it on a real database with a fake carrier standing in for PluginMessaging, and with a real Redis
for the Redis mode, and checks what is sent, who may send, and what changes.

## The Velocity adapter

The `velocity` and `paper` modules need `repo.papermc.io`, which the environment these tests were written in cannot reach, so they
could not be compiled against the real API there. `tools/velocity-simulation` stands in: it compiles the adapter against hand-written
stubs of the small part of the Velocity API it uses (taking the signatures from the JustBans plugin, which does compile, where it uses
the same calls) and then drives the real adapter classes through a whole session on a real database: pre-login, profile, login, first
server, registering, being moved to the lobby, a premium player, refusals, the bridge channel with messages from a server and from a
client, the login timeout and shadow mode. It proves that the adapter is consistent and does what it should *given the model of the
API in the stubs*; it cannot prove that the model is right. Run `mvn -pl authenticator-velocity -am clean package` on a machine that can
reach the repository, and delete the simulation when you trust that.

