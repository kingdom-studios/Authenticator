-- Logins, wrong passwords and admin actions. No foreign keys: the trail must outlive the account it describes.
CREATE TABLE auth_audit (
	id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
	created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
	event VARCHAR(32) NOT NULL,
	uuid UUID NULL,
	username VARCHAR(16) NULL,
	ip INET6 NULL,
	-- Who did it when it was not the player: an admin's name or CONSOLE.
	actor VARCHAR(48) NULL,
	-- The node that recorded it.
	node VARCHAR(64) NULL,
	details VARCHAR(255) NULL,
	PRIMARY KEY (id),
	KEY ix_audit_time (created_at),
	KEY ix_audit_uuid_time (uuid, created_at),
	KEY ix_audit_event_time (event, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
