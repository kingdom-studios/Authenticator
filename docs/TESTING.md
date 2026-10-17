# Running the tests

```
mvn -pl authenticator-common test
```

Unit tests need nothing. The integration tests use real services and are skipped when they are not available, so the build
passes on a bare machine.

| Needs | Provided by | Without it |
|---|---|---|
| MariaDB 10.11+ | `AUTHENTICATOR_TEST_MARIADB=host:port:user:password` | 33 tests are skipped |
| Redis 7 | `redis-server` on the `PATH` (a throwaway process on a random port is started per test class) | Redis tests are skipped |
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
