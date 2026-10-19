# The Paper plugin

`authenticator-paper` runs on every backend server. It does two things: **nobody plays until the proxy has logged them in**, and it holds the
admin commands (`/auth`). It is built on Foundation 6.10.2; Foundation loads the settings and the messages, registers the commands, the
listener and the placeholders, and calls the plugin class.

The proxy ([PROXY.md](PROXY.md)) decides; this plugin only checks that the proxy's decision is on record, and keeps everyone who has no such
record from doing anything.

## Set up

1. Start the proxy first after every update: it applies the database migrations. A Paper server never changes the schema; it only checks that
   it is the one this build needs, and does not start (and keeps everyone out) if it is not.
2. `plugins/Authenticator/settings.yml` is written on the first start. Set:
   * `Node_Id`: unique, **different from the proxy's `node-id`** and from every other server;
   * `Server.Role`: `AUTH` for the waiting server, `GAME` for the others (see below);
   * `Database` (the private endpoint), and `Bridge.Secret`: the same long secret as on the proxy (unless `Bridge.Mode` is `database-only`);
   * on the `AUTH` server, `Login_Spawn` if players should be put somewhere particular.
3. `velocity.toml`: `player-info-forwarding-mode = "modern"`, the Paper servers' ports open only to the proxy (`paper-global.yml` → proxies →
   velocity → enabled, secret). Nothing on a Paper server can tell a player who came around the proxy from one who did not except the
   proxy's record, and the plugin does check that (below), but the ports must be closed all the same.
4. Permissions: `authenticator.admin` for `/auth`.

Until the plugin has read the database nobody gets in: a server that is starting, or whose database is down, is closed rather than open.

## The two roles

| | `AUTH` (the waiting server) | `GAME` |
|---|---|---|
| A player the proxy has no record of | refused ("join through the proxy") | refused |
| A player who has not logged in | let in, frozen until the proxy has logged them in | **refused** at the door |
| A player who has logged in | let in, not frozen (the proxy moves them on) | let in |
| The database cannot be read | refused | refused |

On either role a player whose login is **taken back** while they are on the server (an admin logged them out, their account was removed) is
frozen at once, until the proxy has dealt with them (it sends them to the waiting server).

The default is `GAME`, the strict one: a server whose role was forgotten refuses rather than admits.

## What a frozen player cannot do

Everything. The gate is not configurable on that point, on purpose:

* **move**: the position is put back on every move, only turning the head is allowed (so there is no teleport every tick and the camera
  stays smooth); every teleport but the plugin's own to the login spawn is refused; portals, flight, vehicles;
* **chat**, **commands** (all of them), and the **command list** the client gets for suggestions is emptied;
* **blocks**: break, place, damage, signs; **interactions** with blocks, entities, armor stands, item frames, buckets, fishing, beds, books,
  shears, lecterns, and the arm swing;
* **items and inventories**: open, click, drag, creative, crafting, drop, swap hands, hotbar slot, eating, picking up;
* **damage**: none taken, none dealt (a projectile counts as its shooter's), no hunger, mobs do not target them;
* optionally (`Gate.Hide_Unauthenticated`, on by default) they are **hidden** from every other player.

Every one of these is cancelled at the first and again at the last event priority, so that other plugins neither act on what a frozen player
did nor undo the cancellation. `GatekeeperTest` fails the build if an event is dropped from the list.

A player is frozen the moment the join event fires; there is no delay, no polling every tick and no timer. They are released on the main
thread when the proxy's word arrives.

## How the server learns that a player has logged in

Two sources, either of which is enough:

1. **the bridge** (`Bridge.Mode`: `plugin-messaging`, `redis` or `database-only`): an `AUTH_STATE` message from the proxy, signed, arrives
   within a tick of the login. It carries an epoch; a message older than what the server knows is ignored.
2. **the database** (`auth_presence`): read when the player connects, and then every `Gate.Poll_Seconds` (2 s) for everybody on the server.
   It is never lost, so a lost message costs one poll, and a server without a bridge works the same, a little slower.

On a Paper server every plugin message arrives "through a player": the source says nothing about who sent it. What protects the channel is
the signature on every message, the proxy dropping every message a client sends on `authenticator:bridge` (see PROXY.md), and the closed
ports.

## Commands: `/auth` (alias `/authenticator`)

It is not `/authadmin`: that is the proxy's command (status and the import), and a command registered on the proxy wins over a backend
server's for a player. The labels can be changed in `Command_Aliases`. All subcommands need `authenticator.admin`; each works off the main
thread and answers when done.

| Command | |
|---|---|
| `/auth info <player>` | the account, and how far the player is on the network |
| `/auth unregister <player>` | removes the account; the player goes back to registering |
| `/auth changepassword <player> <password>` | a new password (it must satisfy `Passwords.Min_Length` and `Forbidden`); other addresses must type it |
| `/auth forcelogin <player>` | logs in a player who is waiting to (they must be on the network, waiting) |
| `/auth forcelogout <player>` | makes a logged in player with a password type it again |
| `/auth setpremium <player>` | asks Mojang; if the name is an original one the account becomes an original account and the player must reconnect |
| `/auth setcracked <player> <password>` | an original account becomes a password account; the player must reconnect and type it |
| `/auth migrate minelogin [dry-run]` | the same import as the proxy's, reading mineLogin's table |
| `/auth status` | node, role, database, bridge, who the gate knows |
| `/auth reload` | re-reads the settings and the messages (Foundation) |

Every action is a change in the database, which is where the proxy looks: it notices within `bridge.database-poll-seconds` (5 s). After the
change the command also **tells the proxy at once** over the bridge (`ACCOUNT_CHANGED`: "look at this player now"; the proxy reads the
database, it does not trust the message). If there is nobody on the server to carry the message, or the bridge is `database-only`, the
command says so and the proxy catches up within its poll. Admin actions are recorded in `auth_audit` with the admin's name.

`forcelogin` and `forcelogout` write the player's row in `auth_presence` under **this server's** `Node_Id`, which is how the proxy knows the
row is a command and not its own; that is why the ids must differ. The proxy accepts only these two kinds of change from another node.

## PlaceholderAPI

Foundation names the expansion after the plugin, so the placeholders are:

* `%authenticator_state%`: `authenticated`, `pending_login`, `pending_register` or `unknown`;
* `%authenticator_premium%`: `true` or `false` (an original account);
* `%authenticator_registered%`: `true` or `false`.

They only read memory (the gate, and a snapshot filled in the background), so they never wait for the database; until the first snapshot
they show `loading`. The words are in the messages file (`Placeholders`).

## Messages

`Locale: pl` or `en` in `settings.yml`; the files are in `plugins/Authenticator/localization/`. The sections `Gate`, `Admin` and
`Placeholders` are this plugin's; the rest is Foundation's framework text (in English in both files).

## Not done on a Paper server

* No 2FA, no captcha, no e-mail or password recovery: an admin sets a new password.
* No Bedrock players.
* The gate does not kick on a timeout: the proxy does.
* The plugin has been compiled against the real Paper API and Foundation and its logic tested on a real database, but it has **not yet been
  run on a real Paper server**: see the plan, "Czego jeszcze nie zweryfikowano".
