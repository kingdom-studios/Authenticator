# Authenticator

Authentication for a **Velocity** proxy with **Paper** backend servers. Players with an original Minecraft account join without a password;
everyone else registers and logs in with one, and nobody who has not logged in can do anything on a backend server.

The proxy decides, the database remembers, and the Paper servers only check that the proxy's decision is on record.

## How it works

```
 player ──► Velocity (authenticator-velocity) ──► Paper AUTH   (waiting server, players frozen until logged in)
              │  decides premium / password,               │
              │  keeps the login state                     ▼
              │                                   Paper GAME   (refuses anyone who is not logged in)
              ▼
          MariaDB  (accounts, sessions, login state)      optional Redis for the bridge
```

| Module | What it is |
|---|---|
| `authenticator-common` | Everything that does not depend on a platform: account and session logic, premium lookup, hashing, database access and migrations, the bridge protocol, the importer, the config updater. |
| `authenticator-velocity` | The proxy plugin: connection decisions, `/login`, `/register`, `/premium`, `/cracked`, routing, `/authadmin`. |
| `authenticator-paper` | The backend plugin, built on [Foundation](https://github.com/kangarko/Foundation): the gate, `/auth` admin commands, PlaceholderAPI expansion. |

A new name is looked up (memory, database, Mojang and its mirrors) and the connection is made online or offline accordingly. The account
of an original player is created only after Mojang has confirmed them; a non-premium account only at `/register`.

## Features

* **Automatic premium detection.** Original accounts log in through Mojang with no password. Policy `PROTECT` (default) stops a cracked
  client from taking an original name; `ALLOW_CLAIM_AUTO` lets a client that is not the original owner register the name.
* **Public and private endpoints.** MariaDB and Redis each have a `public` endpoint (WAN, TLS, with `server-ssl-cert` for your own CA) and
  a `private` one (LAN). The proxy normally uses `public`, Paper servers `private`; `fallback` lists what to try when the first is down.
  A plain path in `server-ssl-cert` is enough, it is turned into the URL the driver needs.
* **Three bridge modes** between the proxy and the servers, in `bridge.mode`:
  * `plugin-messaging` (default): plugin messages in a signed envelope (HMAC-SHA256, timestamp, one-time id); nothing extra to expose;
  * `redis`: Redis pub/sub, with TLS on the public endpoint;
  * `database-only`: no messages, the servers poll the database.

  In every mode the database is the source of truth; a message only makes a change visible sooner.
* **Gatekeeper on Paper.** A player who has not logged in cannot move, chat, run commands, break or place blocks, interact, open or click
  inventories, drop or pick up items, take or deal damage, and can be hidden from others. The checks run at the first and the last event
  priority so other plugins cannot undo them. A `GAME` server refuses such a player at the door.
* **Safe password storage.** BCrypt (default) or Argon2id; passwords imported from mineLogin (BCrypt, SHA-256, SHA-512, Argon2) keep working
  and are re-hashed at the next login. Hashing runs on a bounded pool, so a flood of logins cannot stall the proxy.
* **Abuse limits.** Connections per address, wrong passwords per address with a block, nickname filter, one connection per name.
* **Migration from mineLogin** with a dry run, and a **shadow mode** that runs beside it and only compares decisions.
* **Config files update themselves.** After an update new keys are added to `config.yml` / `settings.yml` with their comments, your values
  stay, and the old file is saved as `*.bak`.

Not included: 2FA, captcha, e-mail or password recovery (an admin sets a new password), Bedrock players.

## Building

Requires **JDK 25** and **Maven 3.9+**.

```
mvn clean package
tools/check-jars.sh        # looks inside the jars: relocations, descriptors, nothing the server already provides
```

The plugins are in `authenticator-velocity/target/Authenticator-Velocity-<version>.jar` and
`authenticator-paper/target/Authenticator-Paper-<version>.jar`. Integration tests need a MariaDB and Redis and are skipped without them,
see [docs/TESTING.md](docs/TESTING.md).

## Quick start

Requires MariaDB 10.11+, Velocity, and Paper 26.2 (`api-version: "26.2"`).

1. **Database.** Create the accounts `auth_proxy` (schema changes, TLS required) and `auth_backend` (data only):
   [docs/DATABASE.md](docs/DATABASE.md), [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md#step-1-the-database-accounts).
2. **Velocity.** Put the proxy jar in `plugins/`, set `online-mode = true`, modern forwarding with a secret and
   `log-command-executions = false` in `velocity.toml`, register an `auth` and a lobby server. Start once, fill in `database`,
   `bridge.secret` (16+ characters) and `routing` in `plugins/authenticator/config.yml`, restart. Start the proxy first after every update:
   it applies the database migrations. [docs/PROXY.md](docs/PROXY.md)
3. **Paper.** Put the Paper jar on every backend server, close its port to everything but the proxy, enable Velocity forwarding. In
   `plugins/Authenticator/settings.yml` set a unique `Node_Id` (different from the proxy's `node-id`), `Server.Role`, the `private`
   database endpoint and the same `Bridge.Secret`. [docs/PAPER.md](docs/PAPER.md)

   | Role | Use for | Behaviour |
   |---|---|---|
   | `AUTH` | the waiting server | lets a not-yet-logged-in player in, frozen, until the proxy has logged them in |
   | `GAME` (default) | every other server | refuses a player who has not logged in |

4. **From mineLogin?** Run the proxy in shadow mode first, then switch in a service window:
   [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

To try everything on one machine, [`staging/`](staging/README.md) starts a Velocity, a Paper `AUTH`, a Paper `GAME` and MariaDB with Docker.

## Commands and permissions

**Player commands (Velocity).** The passwords never reach a backend server.

| Command | Who | What |
|---|---|---|
| `/register <password> <password>` | new non-premium player | creates the account and logs in |
| `/login <password>` | registered non-premium player | logs in |
| `/changepassword <old> <new>` | logged in, password account | |
| `/unregister <password>` | logged in, password account | removes the account |
| `/premium` | logged in, password account | switches to a premium account after Mojang confirms the name |
| `/cracked <new> <new>` | logged in, premium account | gives the account a password |

**Proxy admin (`authenticator.admin`, and the console).** `/authadmin status`, `/authadmin migrate minelogin [dry-run]`.

**Backend admin (`/auth`, alias `/authenticator`, `authenticator.admin`).**

| Command | What |
|---|---|
| `/auth info <player>` | the account and where the player is on the network |
| `/auth unregister <player>` | removes the account |
| `/auth changepassword <player> <password>` | sets a new password |
| `/auth forcelogin <player>` / `forcelogout <player>` | logs a waiting player in, or makes a logged-in one type the password again |
| `/auth setpremium <player>` / `setcracked <player> <password>` | changes the type of an account |
| `/auth migrate minelogin [dry-run]` | imports accounts from mineLogin's table |
| `/auth status` / `reload` | node, role, database and bridge state; re-read settings and messages |

**PlaceholderAPI** (`%authenticator_<name>%`): `state` (`authenticated`, `pending_login`, `pending_register`, `unknown`), `premium`
(`true`/`false`), `registered` (`true`/`false`).

## Documentation

| | |
|---|---|
| [docs/PROXY.md](docs/PROXY.md) | Velocity plugin: setup, how a connection is decided, shadow mode, importing |
| [docs/PAPER.md](docs/PAPER.md) | Paper plugin: roles, the gate, admin commands, placeholders |
| [docs/DATABASE.md](docs/DATABASE.md) | Accounts, endpoints, TLS, schema |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Moving from mineLogin, shadow mode, service window, rollback |
| [docs/MESSAGES.md](docs/MESSAGES.md) | Every message and how to change it |
| [docs/TESTING.md](docs/TESTING.md) | Running the tests |

## License

Apache License 2.0, see [LICENSE](LICENSE).
