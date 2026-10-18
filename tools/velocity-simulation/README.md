# Velocity simulation

A stand-in for compiling and testing `authenticator-velocity` when the real Velocity API cannot be downloaded.

* `stubs/` is a hand-written copy of the small part of the Velocity API the adapter uses. **It is a model, written from memory and from
  the JustBans plugin, which compiles against the real API. It is not the API.**
* `src/Sim.java` implements fake players, servers and events on top of the stubs and drives the real adapter classes through whole
  sessions against a real MariaDB: see `docs/TESTING.md` for what it covers.
* `./run.sh` does it all; it needs `AUTHENTICATOR_TEST_MARIADB`.

A pass means the adapter is consistent and behaves as intended *under the model*. Only `mvn -pl authenticator-velocity -am clean package`
against the real `velocity-api` shows that the model is right. Delete this folder once that works.
