# Staging cluster

One command prepares and starts a small network on your machine: **1× Velocity**, **1× Paper `AUTH`**, **1× Paper `GAME`**, MariaDB, all
with the shared bridge secret and the Velocity forwarding secret generated for you.

```
staging/prepare.sh        # builds the jars if missing, checks them, writes .env, renders every config into staging/data/
cd staging && docker compose up
```

Connect a Minecraft client to `localhost:25565` (scenarios below). `prepare.sh --reset` throws
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
  non-premium client (an offline-mode test client) and `/login hunter2`.
* **Shadow mode**: set `dry-run: true` and `bridge.mode: database-only` in `data/velocity/plugins/authenticator/config.yml`, restart the
  proxy; see [DEPLOYMENT.md](../docs/DEPLOYMENT.md) step 2 for what to look at.
* **Look in the database**: `mariadb -h127.0.0.1 -P3307 -uroot -p"$DB_ROOT_PASSWORD" authenticator` (the password is in `.env`).

* **Premium and non-premium players**: a real account goes straight to `game`; a non-premium name is frozen on `auth` until `/register` or
  `/login`; wrong passwords kick and then block the address; `/premium` and `/cracked` switch an account's type.
* **Signed commands**: `/login` and `/register` from a 1.19.1+ client must not be kicked for chat validation.
* **Going around the proxy**: a direct connection to a Paper server (publish its port temporarily) is refused, and so is a name the proxy
  has no record of entering `paper-game`.
* **Admin commands**: `/auth forcelogin`, `/auth forcelogout`, `/auth setpremium`, `/auth setcracked` act within the proxy's
  `bridge.database-poll-seconds`.
* **Failures**: stop `mariadb` (new connections are refused politely, players already online keep playing, everything recovers when it is
  back); restart the proxy with players online; restart `paper-auth` while a player waits.
* **Certificate options and config updates**: put an older `config.yml` / `settings.yml` into `data/` and restart; the new keys appear, your
  values stay, and the old file is saved as `*.bak`.

## Notes

The images are third-party, so a variable can change between their versions:

* the `itzg/minecraft-server` variables (`TYPE=PAPER`, `VERSION`, `CFG_VELOCITY_SECRET`) and `patches/velocity-forwarding.json`, which
  switches on Velocity forwarding in `paper-global.yml`; if a Paper server refuses the proxy ("unable to verify player details"), set the
  forwarding secret by hand in `data/paper-*/config/paper-global.yml`;
* the `itzg/mc-proxy` image reads `data/velocity/velocity.toml` as it is, but may rewrite parts of it from its environment;
* Paper 26.2 and Java 25 must exist as image tags (`PAPER_VERSION` in `.env` changes the version).
