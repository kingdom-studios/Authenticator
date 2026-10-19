# The Velocity plugin

`authenticator-velocity` decides, for every connection, whether the player is **premium** (Mojang confirms the name, no password) or
must **log in with a password**, keeps everyone who has not logged in on a waiting server, and carries the login state to the Paper
servers. It is the only place a decision is made: a Paper server only checks that the proxy's decision is on record.

## Set up

1. `velocity.toml`
   * `online-mode = true`. Authenticator makes every connection online or offline by itself, but this is what protects you if the plugin
     is not loaded: then only original accounts get in.
   * `player-info-forwarding-mode = "modern"` with a secret, and the Paper ports open **only** to the proxy's address. Otherwise anyone
     who can reach a Paper server directly can pretend to be any player.
   * `log-command-executions = false`: `/login` and `/register` carry passwords.
   * Both a waiting server (`routing.auth-servers`) and a lobby (`routing.lobby-servers`) must be registered.
2. Create the database user (see [DATABASE.md](DATABASE.md)), start the proxy once: `plugins/authenticator/config.yml` is written.
3. Fill in `database` and, unless `bridge.mode` is `database-only`, `bridge.secret` (at least 16 characters, the same on every Paper
   server). The plugin refuses to start with a bad config and says what is wrong; until then Velocity's online mode lets in original
   accounts only.
4. Restart. `/authadmin status` shows the node, the database endpoint, the bridge and who is online.

## Commands

| Command | Who | What |
|---|---|---|
| `/register <password> <password>` | a new non-premium player | creates the account and logs in |
| `/login <password>` | a registered non-premium player | logs in |
| `/changepassword <old> <new>` | logged in, password account | |
| `/unregister <password>` | logged in, password account | removes the account; the player is kicked |
| `/premium` | logged in, password account | asks Mojang whether the name is an original one; type it again within 30 s to confirm; the account stops needing a password and the player is kicked to reconnect |
| `/cracked <new> <new>` | logged in, premium account | the account gets a password and stops being checked by Mojang; two steps; kicked to reconnect |
| `/authadmin status` | `authenticator.admin` (and the console) | |
| `/authadmin migrate minelogin [dry-run]` | `authenticator.admin` (and the console) | imports accounts from mineLogin's table, which is only read |

The account commands for admins (`info`, `unregister`, `changepassword`, `forcelogin`, `forcelogout`, `setpremium`, `setcracked`) are `/auth` on the
Paper servers, see [PAPER.md](PAPER.md).

The player commands live on the proxy, so a password never reaches a Paper server. They have no tab completion.

## How a connection is decided

In this order, cheapest first, so that a flood is refused before it costs a query or a request to Mojang:

| Check | If it fails |
|---|---|
| connections from this IP in the window (`limits.connections-per-ip`) | refused |
| the name matches `login.nickname-regex` | refused |
| the IP is blocked after too many wrong passwords | refused until the block ends |
| the name is already online | refused |
| a registered account exists | wrong letter case: refused, with the right spelling; premium account: **online** (Mojang confirms); password account: **offline** |
| a new name | asks the lookup services (memory, then the database, then Mojang and the mirrors in `premium.apis`): not an original name: **offline**; an original name: see below; no service answers: refused ("try again in a moment"), because a name that cannot be checked must not be let in as non-premium |

A new name that is an original one:

* an original player who changed their name keeps their account (found by Mojang UUID);
* `premium.policy: PROTECT` (default): the connection is **online**. A client that is not an original one cannot get in with that name.
* `premium.policy: ALLOW_CLAIM_AUTO`: a client that announces some other UUID than the original account's is let in as non-premium at once;
  any other client is tried online once, and when it fails to pass Mojang's check its next connection (same name and IP, within
  `premium.claim-seconds`) is offline. The real owner keeps playing as before until someone has registered the name; after that the
  owner has to use `/premium`.

The account row of an original player is created only after Mojang has confirmed them, and that of a non-premium player only at
`/register`: a name that merely connected never gets one.

## Shadow mode

`dry-run: true` lets Authenticator run **next to** mineLogin and only compare. For every connection it works out what it would have done and
writes down whether mineLogin did the same; it runs after every other plugin, never calls a setter on an event, registers no player
command, no bridge and no routing, and kicks nobody. Set `bridge.mode: database-only` for it (it needs no secret then).

1. Load Authenticator beside mineLogin with `dry-run: true`.
2. `/authadmin migrate minelogin dry-run`, then without `dry-run`: both plugins now know the same players.
3. Let it run for days. `/authadmin status` shows how many decisions agreed, how many differed, how many could not be compared (mineLogin
   left the decision to Velocity), whether the UUIDs agreed, and the last differences. Every difference is also logged (at most ten
   lines a minute).
4. Investigate every difference before switching: it is either a bug here or a bug (or a fix) there.

Shadow mode does write to the database: the schema, and the cache of Mojang's answers. It does not write accounts, sessions or login
states, and it does not clean up the states a crashed run left.

## Moving from mineLogin

`/authadmin migrate minelogin` reads the mineLogin table (`migration.minelogin.schema` and `table` in `config.yml`) and creates the accounts
here. It never changes the table, so mineLogin can be put back at any time.

* Passwords keep working: BCrypt, SHA-256, SHA-512 and Argon2 of mineLogin, with its salt. They are replaced at the player's next login by
  the algorithm in `passwords.algorithm` (BCrypt by default, which mineLogin also understands: rolling back never locks out an account
  created after the switch).
* Skipped, and listed with the reason: hashes of any other kind, a name that does not match `login.nickname-regex`, a UUID that is
  not one or belongs to another account, a name already here. Names that differ only in letter case cannot coexist here: the player who
  registered first keeps the name.
* A premium account in mineLogin is imported with its UUID; the hashed addresses mineLogin may hold are not (they cannot be turned
  back into addresses).
* It is safe to run again: accounts that are already here are left alone.
* `dry-run` reports exactly what the real run would do and writes nothing.

## Behaviour to know about

* A name that is already online cannot connect a second time, for original players too: the old connection has to end first (a few
  seconds after a crashed client). This stops anyone from kicking a logged-in player by knowing the name.
* One player cannot be online on two proxies sharing a database at the same time; the second would overwrite the first's login state.
* A password with spaces (only possible for an imported one) works with `/login`; new passwords cannot contain spaces.
* The waiting server is chosen by the fewest players among `routing.auth-servers`; the plugin does not ping servers, so a server that is
  down is still chosen.
* No Bedrock (Geyser/Floodgate) players, no 2FA, no captcha, no e-mail or password recovery: an admin sets a new password on a Paper server.
* If the database is unreachable at a connection, the connection is refused ("try again in a moment"); players already online keep playing.
