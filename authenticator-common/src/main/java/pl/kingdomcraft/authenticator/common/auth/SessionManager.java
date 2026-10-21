package pl.kingdomcraft.authenticator.common.auth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The sessions of everyone connected to this proxy. */
public final class SessionManager {

	private final ConcurrentHashMap<UUID, PlayerSession> byUuid = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, UUID> byName = new ConcurrentHashMap<>();

	/** Adds the session, replacing one with the same UUID. */
	public void add(PlayerSession session) {
		this.byUuid.put(session.uuid(), session);
		this.byName.put(Account.key(session.username()), session.uuid());
	}

	public Optional<PlayerSession> find(UUID uuid) {
		return Optional.ofNullable(this.byUuid.get(uuid));
	}

	public Optional<PlayerSession> findByName(String username) {
		final UUID uuid = this.byName.get(Account.key(username));

		return uuid == null ? Optional.empty() : this.find(uuid);
	}

	public boolean isOnline(String username) {
		return this.byName.containsKey(Account.key(username));
	}

	/** Removes this very session; a newer one with the same UUID is left alone. */
	public void remove(PlayerSession session) {
		if (this.byUuid.remove(session.uuid(), session))
			this.byName.remove(Account.key(session.username()), session.uuid());
	}

	/** Sessions of players who are still not authenticated and whose time is up. */
	public List<PlayerSession> expired(Instant now) {
		final List<PlayerSession> expired = new ArrayList<>();

		for (final PlayerSession session : this.byUuid.values())
			if (session.expired(now))
				expired.add(session);

		return expired;
	}

	public Collection<PlayerSession> all() {
		return List.copyOf(this.byUuid.values());
	}

	public int size() {
		return this.byUuid.size();
	}
}
