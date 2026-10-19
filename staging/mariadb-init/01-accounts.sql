-- Staging only. The accounts of docs/DEPLOYMENT.md, without REQUIRE SSL because this MariaDB has no certificate and the network is a private
-- Docker one. Production accounts must use REQUIRE SSL for the proxy.
CREATE USER 'auth_proxy'@'%' IDENTIFIED BY '@AUTH_PROXY_PASSWORD@';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER ON authenticator.* TO 'auth_proxy'@'%';

CREATE USER 'auth_backend'@'%' IDENTIFIED BY '@AUTH_BACKEND_PASSWORD@';
GRANT SELECT, INSERT, UPDATE, DELETE ON authenticator.* TO 'auth_backend'@'%';

-- A stand-in for the mineLogin table, so that the import can be tried (see staging/README.md).
CREATE TABLE authenticator.mineLogin (username VARCHAR(36) NOT NULL, uuid VARCHAR(36), email VARCHAR(254), salt VARCHAR(64), paid VARCHAR(5) NOT NULL, password VARCHAR(255), hashType VARCHAR(32),
	registerAddress VARCHAR(64), lastJoinAddress VARCHAR(64), registerTime LONG, lastJoinTime LONG, sessionAddress VARCHAR(64), sessionTime LONG, sessionActive VARCHAR(5), lastAttempt VARCHAR(32),
	lastUpdate TIMESTAMP, secret TEXT, recoveryCodes TEXT, logged BOOLEAN, PRIMARY KEY (username));
-- hunter2, BCrypt, made by an independent implementation: log in as "OldPlayer" with it after /authadmin migrate minelogin
INSERT INTO authenticator.mineLogin (username, uuid, paid, password, hashType, registerTime, lastJoinTime)
	VALUES ('OldPlayer', UUID(), 'false', '$2b$06$rjGfgeL9RvskvOZYptY0lOYBluwW0p9wRX2YFfoNvYXfPUJNcWKcC', 'BCRYPT', '1700000000000', '1760000000000');
