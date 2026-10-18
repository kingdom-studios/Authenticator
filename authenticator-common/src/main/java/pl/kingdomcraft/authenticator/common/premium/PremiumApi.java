package pl.kingdomcraft.authenticator.common.premium;

import java.util.UUID;

/** One public service that says whether a name belongs to an original Minecraft account. */
public interface PremiumApi {

	enum Kind {

		/** The name belongs to an original account. */
		FOUND,

		/** The service answered that no original account has this name. */
		NOT_FOUND,

		/** No usable answer: the service is down, rate-limited, slow, or replied with something we do not understand. */
		UNAVAILABLE
	}

	/** @param uuid the Mojang UUID, only for {@link Kind#FOUND}
	 *  @param detail why, for the log */
	record Answer(Kind kind, UUID uuid, String detail) {

		public static Answer found(UUID uuid) {
			return new Answer(Kind.FOUND, uuid, "found");
		}

		public static Answer notFound() {
			return new Answer(Kind.NOT_FOUND, null, "not found");
		}

		public static Answer unavailable(String detail) {
			return new Answer(Kind.UNAVAILABLE, null, detail);
		}
	}

	/** A short lower-case name: what the config calls it and what is stored as the source of a cached answer. */
	String name();

	/** Blocking. Never throws: every failure is an {@link Kind#UNAVAILABLE} answer. */
	Answer lookup(String username);
}
