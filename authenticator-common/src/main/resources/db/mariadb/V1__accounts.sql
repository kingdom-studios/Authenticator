-- Requires MariaDB 10.11 or newer (UUID and INET6 column types).
-- All timestamps are UTC. Columns that can hold a far-future "until" value use DATETIME(3), because TIMESTAMP ends in 2038.

-- One row per player who registered a password (non-premium) or logged in with a premium account.
-- Rows are created only after the player has proven who they are, never at the first connection attempt.
CREATE TABLE auth_accounts (
	id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
	-- The UUID the player has on every server. It does not change when /premium or /cracked changes the account type.
	uuid UUID NOT NULL,
	username VARCHAR(16) NOT NULL,
	-- Lookups use this column; the unique key on it makes names case-insensitively unique.
	username_lower VARCHAR(16) GENERATED ALWAYS AS (LOWER(username)) STORED,
	premium BOOLEAN NOT NULL DEFAULT 0,
	-- The Mojang UUID of the original account, set while premium is true.
	premium_uuid UUID NULL,
	password_hash VARCHAR(255) NULL,
	-- Extra value mixed into the password before hashing. Only imported mineLogin accounts that used salted passwords have one.
	password_salt VARCHAR(64) NULL,
	-- BCRYPT or ARGON2ID for new passwords; SHA256, SHA512 and ARGON2 are accepted for passwords imported from mineLogin.
	hash_algo VARCHAR(16) NULL,
	registered_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
	registered_ip INET6 NULL,
	last_login_at TIMESTAMP(3) NULL,
	last_login_ip INET6 NULL,
	-- Incremented on every update; writers use it to detect that someone else changed the row first.
	version INT UNSIGNED NOT NULL DEFAULT 0,
	PRIMARY KEY (id),
	UNIQUE KEY uq_accounts_uuid (uuid),
	UNIQUE KEY uq_accounts_username (username_lower),
	UNIQUE KEY uq_accounts_premium_uuid (premium_uuid),
	KEY ix_accounts_registered_ip (registered_ip),
	KEY ix_accounts_last_login_ip (last_login_ip),
	CONSTRAINT ck_accounts_hash CHECK ((password_hash IS NULL) = (hash_algo IS NULL)),
	CONSTRAINT ck_accounts_premium CHECK (premium = 0 OR premium_uuid IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
