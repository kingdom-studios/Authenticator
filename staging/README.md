# Staging cluster

One command prepares and starts a small network on your machine: **1× Velocity**, **1× Paper `AUTH`**, **1× Paper `GAME`**, MariaDB, all
with the shared bridge secret and the Velocity forwarding secret generated for you.

```
staging/prepare.sh        # builds the jars if missing, checks them, writes .env, renders every config into staging/data/
cd staging && docker compose up
```

Connect a Minecraft client (online mode) to `localhost:25565` and work through [CHECKLIST.md](CHECKLIST.md). `prepare.sh --reset` throws
everything away (data, secrets) and starts from nothing; `docker compose down` keeps it.

Needs Docker with the compose plugin, Maven and a JDK 25 (to build), `unzip`, `awk`.

## What is in it

| Piece | Details |
|---|---|
| `velocity` | `itzg/mc-proxy`, `online-mode = true`, modern forwarding, servers `auth` and `game`, published on `25565` |
| `paper-auth` | Paper, `Server.Role: AUTH`, `Node_Id: paper-auth`, not published |
| `paper-game` | Paper, `Server.Role: GAME`, `Node_Id: paper-game`, not published |
| `mariadb` | 10.11, database `authenticator`, accounts `auth_proxy` / `auth_backend`, on `127.0.0.1:3307` for you to look in |

The plugin configs are the **shipped samples** with only the staging values changed (`prepare.sh` refuses to run if a key it sets has been
renamed), so what you test is the config you will deploy. They are checked to load through the real parsers.

## Things to try

* **Import**: the database holds a stand-in `mineLogin` table with `OldPlayer` (password `hunter2`, BCrypt).
  `docker compose exec velocity rcon-cli authadmin migrate minelogin dry-run`, then without `dry-run`; join as `OldPlayer` with a
  non-premium client (or use the checklist's offline-client trick) and `/login hunter2`.
* **Shadow mode**: set `dry-run: true` and `bridge.mode: database-only` in `data/velocity/plugins/authenticator/config.yml`, restart the
  proxy; see [DEPLOYMENT.md](../docs/DEPLOYMENT.md) step 2 for what to look at.
* **Look in the database**: `mariadb -h127.0.0.1 -P3307 -uroot -p"$DB_ROOT_PASSWORD" authenticator` (the password is in `.env`).

## Not verified

This kit has been written and its configs validated, but **it has never been started**: there was no Docker in the place it was written.
The parts that depend on the container images are guesses to check on the first run:

* the `itzg/minecraft-server` variables (`TYPE=PAPER`, `VERSION`, `CFG_VELOCITY_SECRET`) and the `patches/velocity-forwarding.json` that
  switches on Velocity forwarding in `paper-global.yml`; if the Paper servers refuse the proxy ("unable to verify player details"), the
  forwarding secret did not arrive: set it by hand in `data/paper-*/config/paper-global.yml`;
* the `itzg/mc-proxy` image reading `data/velocity/velocity.toml` as it is (it may rewrite parts of it from its environment);
* Paper 26.2 and Java 25 being available as image tags (`PAPER_VERSION` in `.env` changes the version).

Whatever you have to change, tell me, and the kit is changed with it.
