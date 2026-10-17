-- Lets a non-premium player come back from the same address without typing the password again.
CREATE TABLE auth_sessions (
	account_id BIGINT UNSIGNED NOT NULL,
	ip INET6 NOT NULL,
	expires_at DATETIME(3) NOT NULL,
	created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
	PRIMARY KEY (account_id),
	KEY ix_sessions_expires (expires_at),
	CONSTRAINT fk_sessions_account FOREIGN KEY (account_id) REFERENCES auth_accounts (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Who is online and how far they got. Backend servers read it when Redis is unavailable, and to catch up after a restart.
-- The proxy writes it, always before it announces the change over Redis.
CREATE TABLE auth_presence (
	uuid UUID NOT NULL,
	-- NULL until the player has an account (a new non-premium player who has not registered yet).
	account_id BIGINT UNSIGNED NULL,
	state ENUM('PENDING_LOGIN', 'PENDING_REGISTER', 'AUTHENTICATED') NOT NULL,
	method ENUM('PREMIUM', 'PASSWORD', 'SESSION', 'FORCED') NULL,
	-- Counts up by one on every change of this player's row, so a message that arrives late can be told from a current one.
	epoch BIGINT UNSIGNED NOT NULL DEFAULT 1,
	-- The proxy that owns the row. Rows of a proxy that crashed are found and removed by this id.
	proxy_id VARCHAR(64) NOT NULL,
	updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
	PRIMARY KEY (uuid),
	KEY ix_presence_account (account_id),
	KEY ix_presence_proxy (proxy_id, updated_at),
	CONSTRAINT fk_presence_account FOREIGN KEY (account_id) REFERENCES auth_accounts (id) ON DELETE CASCADE,
	CONSTRAINT ck_presence_authenticated CHECK (state <> 'AUTHENTICATED' OR (account_id IS NOT NULL AND method IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
