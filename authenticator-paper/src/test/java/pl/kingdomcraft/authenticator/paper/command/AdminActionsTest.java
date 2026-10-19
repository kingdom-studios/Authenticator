package pl.kingdomcraft.authenticator.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.admin.AdminService;
import pl.kingdomcraft.authenticator.common.admin.AdminService.Outcome;
import pl.kingdomcraft.authenticator.paper.settings.Localization;
import pl.kingdomcraft.authenticator.paper.settings.Msg;

class AdminActionsTest {

	private static AdminService.Result result(Outcome outcome, String detail) {
		return new AdminService.Result(outcome, detail, null, null);
	}

	@Test
	void everyWayAnActionCanFailHasItsOwnText() {
		for (final Outcome outcome : Outcome.values()) {
			if (outcome == Outcome.OK)
				continue;

			final String text = AdminActions.failure(result(outcome, outcome == Outcome.WEAK_PASSWORD ? "TOO_SHORT:6" : ""));

			assertTrue(text != null && !text.isBlank(), outcome + " has no text");
		}
	}

	@Test
	void differentFailuresAreNotAllTheSameText() {
		assertNotEquals(AdminActions.failure(result(Outcome.NO_SUCH_ACCOUNT, "")), AdminActions.failure(result(Outcome.NOT_ONLINE, "")));
		assertNotEquals(AdminActions.failure(result(Outcome.PREMIUM_ACCOUNT, "")), AdminActions.failure(result(Outcome.NOT_PREMIUM_ACCOUNT, "")));
		assertNotEquals(AdminActions.failure(result(Outcome.LOOKUP_FAILED, "")), AdminActions.failure(result(Outcome.NOT_ORIGINAL_NAME, "")));
	}

	@Test
	void eachPasswordViolationHasItsOwnText() {
		assertEquals(Localization.Admin.WEAK_TOO_SHORT, AdminActions.failure(result(Outcome.WEAK_PASSWORD, "TOO_SHORT:6")));
		assertEquals(Localization.Admin.WEAK_TOO_LONG, AdminActions.failure(result(Outcome.WEAK_PASSWORD, "TOO_LONG")));
		assertEquals(Localization.Admin.WEAK_WHITESPACE, AdminActions.failure(result(Outcome.WEAK_PASSWORD, "WHITESPACE")));
		assertEquals(Localization.Admin.WEAK_SAME_AS_NAME, AdminActions.failure(result(Outcome.WEAK_PASSWORD, "SAME_AS_NAME")));
		assertEquals(Localization.Admin.WEAK_FORBIDDEN, AdminActions.failure(result(Outcome.WEAK_PASSWORD, "FORBIDDEN")));
	}

	@Test
	void placeholdersAreFilledAndOnlyThoseGiven() {
		assertEquals("&a Steve is being logged in.", Msg.fill("&a {player} is being logged in.", "player", "Steve"));
		assertEquals("min 6 for {player}", Msg.fill("min {min} for {player}", "min", "6"));
		assertEquals("same Steve Steve", Msg.fill("same {player} {player}", "player", "Steve"));
	}

	@Test
	void placeholdersComeInPairs() {
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Msg.fill("{player}", "player"));
	}

	@Test
	void theUtilityClassesCannotBeBuilt() throws Exception {
		final Constructor<AdminActions> constructor = AdminActions.class.getDeclaredConstructor();

		assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
	}
}
