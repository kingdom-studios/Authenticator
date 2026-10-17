-- Addresses locked out after too many wrong passwords.
CREATE TABLE auth_ip_blocks (
	ip INET6 NOT NULL,
	blocked_until DATETIME(3) NOT NULL,
	reason VARCHAR(64) NOT NULL,
	created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
	PRIMARY KEY (ip),
	KEY ix_ip_blocks_until (blocked_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Answers from Mojang about whether a name belongs to an original account, so a Mojang outage does not stop new players.
CREATE TABLE auth_premium_cache (
	username_lower VARCHAR(16) NOT NULL,
	-- NULL means Mojang said the name is not an original account.
	premium_uuid UUID NULL,
	-- Which lookup answered, for diagnosing disagreements between the APIs.
	source VARCHAR(16) NOT NULL,
	checked_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
	PRIMARY KEY (username_lower),
	KEY ix_premium_cache_checked (checked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
