# Staging checklist

What the unit and integration tests could not prove, because it needs a real Velocity and real Paper servers. Tick them off in order; each
line says what should happen. Anything else is a finding: copy the log lines of the proxy and the server involved.

Logs: `docker compose logs -f velocity paper-auth paper-game`. Commands on the proxy console: `docker compose exec velocity rcon-cli …`
(or attach), on a Paper server: `docker compose exec paper-auth rcon-cli …` (RCON may need enabling) or from a player with `authenticator.admin`
(`docker compose exec paper-auth rcon-cli op <you>` works only for a connected player).

## 1. Start

- [ ] Velocity loads `Authenticator` with no error; it applies the migrations (`auth_*` tables appear in the database).
- [ ] Both Paper servers load `Authenticator`; `/auth status` shows the right `Node_Id`, role and "database ok".
- [ ] Starting the Paper servers **before** the proxy has migrated: they refuse everyone and say why; once the proxy is up they open.

## 2. Premium player (real Mojang account)

- [ ] Joins, is put on `game` straight away without being asked for a password; `/auth info <you>` says premium.
- [ ] On `game` the player is not frozen and not hidden.

## 3. Non-premium player (offline client, a name that is not an original one)

- [ ] First join: put on `auth`, frozen (cannot move, chat, run commands, break blocks), told to `/register`.
- [ ] `/register a a` with a mismatch and with a too-short password: refused with the right message; a good one logs in and moves to `game`.
- [ ] Reconnect from the same IP within the session window: no password. From another IP (or after `/auth forcelogout`): `/login` asked.
- [ ] Wrong password `login.max-attempts` times: kicked; again from the same IP: blocked for the configured time.
- [ ] The password is **not** in `docker compose logs` anywhere (proxy, Paper), nor in the proxy's command log.

## 4. Open staging points

### Signed commands (1.19.1+ clients)
- [ ] `/login` and `/register` typed by a **1.19.1+ client** work and the player is not kicked with "chat validation" / "invalid signed command". (They are handled on the proxy, which cancels the command before it reaches the backend.)
- [ ] On `paper-auth`, a frozen player's other commands (`/help`, `/tp …`) do nothing; Paper cancelling `PlayerCommandPreprocessEvent` does not kick a 1.19.1+ client.

### Hiding and freezing
- [ ] With two players on `auth` (or one on `auth`, one on `game` joining the same world) a frozen player is invisible to the others (`Gate.Hide_Unauthenticated`), and becomes visible after login (`hidePlayer`/`showPlayer` on Paper 26.2).
- [ ] The frozen player is held at the login spawn (`Login_Spawn.World`); head turning is smooth, no rubber-banding.
- [ ] The teleport to the login spawn happens (it is async on Paper) and the player is not left in the void or at world spawn.

### Event order
- [ ] A premium login is decided in `PreLoginEvent` (online) and the player never appears as "pending" anywhere (check `/auth info`).
- [ ] After `/login` the `AUTH_STATE` message reaches `paper-auth` before the player has finished joining a **second** time: no frozen flash on `game`.
- [ ] `PlayerJoinEvent` on `paper-auth` freezes before any other plugin acts (the gate cancels at both first and last priority; try a command from another plugin when frozen).
- [ ] `DisconnectEvent` after a refused login does not leave a stuck session: reconnect immediately works (no "already online").

### The bridge
- [ ] A **client** sending a plugin message on `authenticator:bridge` (any mod or `/` client packet) is dropped on the proxy and has no effect.
- [ ] Killing the message path (set `Bridge.Mode: database-only` on one Paper server): players still get through within `Gate.Poll_Seconds`.
- [ ] `/auth forcelogin <name>` for a player waiting on `auth`: they are logged in and moved within the proxy's `database-poll-seconds`.
- [ ] `/auth forcelogout <name>` for a logged in password player: frozen at once on the server, sent to `auth`, asked for the password.

## 5. Going around the proxy

- [ ] Connecting directly to a Paper server (publish the port temporarily) is refused by Paper itself (forwarding secret) and, with the secret removed from the test, by the plugin ("join through the proxy").
- [ ] A name the proxy has no record of, entering `paper-game` by any means, is refused at the door.

## 6. Premium switching

- [ ] `/premium` as a password player with an original name: asks for confirmation within 30 s, then kicks; reconnecting as the real account needs no password.
- [ ] `/cracked <p> <p>` as a premium player: two steps, kicked, reconnecting offline asks for the password.
- [ ] `/auth setpremium` and `/auth setcracked` from an admin do the same.

## 7. Import and shadow mode

- [ ] `/authadmin migrate minelogin dry-run` lists `OldPlayer` and writes nothing; the real run creates it; `/login hunter2` works; run again: nothing new.
- [ ] Shadow mode (`dry-run: true`): nobody is kicked or routed by Authenticator, `/authadmin status` counts decisions, and a deliberate difference shows up as a `Shadow mode disagreement` line.

## 8. Failures

- [ ] **Database down** (`docker compose stop mariadb`): new connections are refused politely ("try again in a moment"); players already online keep playing; the Paper servers close to new joins; after `start` everything recovers without a restart.
- [ ] **Proxy restart with players online**: they are disconnected cleanly; logging in again works, nobody is stuck "online".
- [ ] **Paper `auth` restart** while a player waits: they are moved or kicked sensibly, not left on the wrong server.
- [ ] **Bot storm**: 50 offline connections in a few seconds from one address are refused at `limits.connections-per-ip` without a query per connection and without harming a legitimate login.
- [ ] After a long run: `docker stats` shows no growth in the proxy's memory; `auth_*` tables have no expired sessions piling up (maintenance runs).
