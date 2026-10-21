# Deployment: from mineLogin to Authenticator

Three steps, each of which can be stopped at without harm to the one before. Nothing here changes mineLogin's table, so going back is always
possible (see "Rollback").

Before anything, try the whole procedure on [the staging cluster](../staging/README.md).

Names used below: the database is `authenticator`, the proxy's address is `<proxy-ip>`, the Paper servers are in `10.0.0.0/24`.

## Step 1: the database accounts

On the MariaDB server, as an administrator. Two accounts, with different rights (more in [DATABASE.md](DATABASE.md)):

```sql
CREATE DATABASE IF NOT EXISTS authenticator CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

-- the proxy: reaches the database over the public endpoint, so TLS is required; it alone changes the schema
CREATE USER 'auth_proxy'@'<proxy-ip>' IDENTIFIED BY '<long random password>' REQUIRE SSL;
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER ON authenticator.* TO 'auth_proxy'@'<proxy-ip>';

-- the Paper servers: private network, data only, no schema changes
CREATE USER 'auth_backend'@'10.0.0.%' IDENTIFIED BY '<another long random password>';
GRANT SELECT, INSERT, UPDATE, DELETE ON authenticator.* TO 'auth_backend'@'10.0.0.%';

-- the import reads mineLogin's table; skip this if it is in the `authenticator` database
GRANT SELECT ON `minelogin_schema`.`mineLogin` TO 'auth_proxy'@'<proxy-ip>';
```

Check from the proxy host that TLS is really in use, and that the account without it is refused:

```
mariadb -h <db-host> -u auth_proxy -p --ssl --ssl-verify-server-cert -e "SHOW STATUS LIKE 'Ssl_cipher'"   # a cipher name
mariadb -h <db-host> -u auth_proxy -p --skip-ssl -e "SELECT 1"                                              # ERROR 1045
```

Put the endpoints into the configs (`database.endpoints` in the proxy's `config.yml`, `Database.Endpoints` in each Paper `settings.yml`),
`ssl-mode: verify-full` on the proxy's. If the mineLogin table is in another schema, set `migration.minelogin.schema` (and `table`).

Back up the database now (`mariadb-dump`); that is the state you can go back to.

## Step 2: shadow mode, beside mineLogin

Authenticator watches the connections mineLogin decides and writes down whether it would have decided the same. It changes nothing for a
player: no kicks, no routing, no commands, no bridge. Only the proxy gets the plugin in this step.

1. Copy `Authenticator-Velocity-1.0.0.jar` to the proxy's `plugins/` **next to** mineLogin. Start the proxy once to get `config.yml`, stop it.
2. In `plugins/authenticator/config.yml`:
   ```yaml
   dry-run: true
   bridge:
     mode: database-only
   ```
   plus `node-id` (unique), and `database` (Step 1). The routing section does not matter in this mode.
3. Start the proxy. The log says "Authenticator started as node ... in SHADOW mode". Run `/authadmin migrate minelogin dry-run`, read the list of what would be skipped
   and why, then `/authadmin migrate minelogin`: Authenticator now knows the same accounts. It is safe to run again whenever mineLogin has
   gained players.
4. Let it run through at least a few days with normal traffic, including a weekend.

### Reading the comparison

* `/authadmin status` shows **agree / differ / undecided**, whether the **UUIDs agreed**, and the last differences. *Undecided* means mineLogin
  left the decision to Velocity (no information to compare); it is not a problem. Only *differ* is.
* Every difference is also in the proxy log, at most ten lines a minute (the rest is counted):
  ```
  Shadow mode disagreement: <name>: Authenticator would ... , mineLogin ...
  ```
  Search with `grep "Shadow mode disagreement" logs/latest.log`.
* Every difference is either a difference in the rules or a bug on one side. Typical, and fine: a name mineLogin lets in that does not match
  `login.nickname-regex`; a name that differs only in letter case. Not fine: a premium player Authenticator would send through a password
  prompt, a non-premium name it would refuse as "original", a UUID that differs for an existing account. Open an issue for each of those
  with the log line.
* **Go on to step 3 only when** there is no unexplained difference over several days, `uuid agree` has no failures, and the import's skipped
  list has been looked at name by name.

Shadow mode writes to the database (the schema, the cache of Mojang's answers) but no accounts, sessions or login states other than the
import you ran yourself. Leave it as long as needed.

## Step 3: the service window

Announce it. Prepare everything in advance: the final jars on the machine, the configs written (below), the old mineLogin jars and configs
kept where they are. Plan 20 to 30 minutes.

**Before the window**: configs for Authenticator on the proxy (`dry-run: false`, `bridge.mode: plugin-messaging`, `bridge.secret` of 16+
random characters, `routing.auth-servers` and `routing.lobby-servers` registered in `velocity.toml`) and on every Paper server
(`Node_Id` unique and **different from the proxy's**, `Server.Role` `AUTH` for the waiting server and `GAME` for all the others,
`Bridge.Secret` the same). `velocity.toml`: `online-mode = true`, modern forwarding, `log-command-executions = false`; Paper ports closed
to everything but the proxy.

1. **Close the door**: set the proxy to refuse joins (announce, then kick everyone or stop the proxy).
2. **Back up** the database again (`mariadb-dump`), and keep a copy of the proxy's and Paper servers' `plugins/` directories.
3. **Stop** the proxy and all Paper servers.
4. **Swap the plugins** on all of them at once: remove the mineLogin jar (keep it in a `rollback/` folder), put in Authenticator's:
   `Authenticator-Velocity` on the proxy, `Authenticator-Paper` on every Paper server. PlaceholderAPI stays; its `%authenticator_*%`
   placeholders replace the old ones.
5. **Start the proxy first** (it applies the migrations; a Paper server refuses everyone until the schema is the right one), wait for
   "Authenticator started as node ... in active mode" without errors, then the Paper servers. `/auth status` on each: node, role, database, bridge all OK.
6. **The final import**: `/authadmin migrate minelogin` on the proxy (it only adds accounts that appeared since step 2). Read the
   summary: created / already there / skipped.
7. **Verify** with three accounts before opening: a premium one (straight to the lobby, no password), an imported non-premium one
   (`/login` with the old password; check the next login is not asked again from the same address), and a new name (`/register`).
   `/auth info <name>` tells you what the network knows about each.
8. **Open the door.** Watch the proxy log and `/authadmin status` for the first hour: wrong-password and refused-connection counts should
   look like a normal day.

Passwords are re-hashed with the new algorithm (BCrypt by default) at each player's next login; nothing else is asked of the players.

### Rollback

Authenticator never wrote to mineLogin's table, so the old plugin can simply be put back:

1. Stop the proxy and the Paper servers.
2. Remove Authenticator's jars, put the mineLogin jars (and its configs, which were not touched) back.
3. Start the proxy, then the servers. Players log in as before.

What is different afterwards, and what is not:

* **Accounts registered after the switch** are only in Authenticator's tables, not in mineLogin's. Their passwords are BCrypt, which
  mineLogin also understands, so they can be **copied back** into the mineLogin table by hand if you want them to keep playing: a row in
  `authenticator.auth_accounts` becomes one in `mineLogin` with `hashType = 'BCRYPT'` (the exact statement depends on your mineLogin
  version). Without that, those players register again.
* Passwords changed after the switch are not reflected in mineLogin's table.
* If the switch itself went wrong in the database (a bad migration), restore the dump from step 2; the `authenticator` database can also
  just be dropped, since it holds nothing mineLogin needs.

Decide on the rollback in the first hours: the longer Authenticator runs, the more accounts will have to be copied back.

### After

Keep the mineLogin jars and table for a few weeks, then remove them. Keep `auth_audit` in mind: it is cleaned after
`audit.retention-days`. Update the proxy first on every later release, then the Paper servers.
