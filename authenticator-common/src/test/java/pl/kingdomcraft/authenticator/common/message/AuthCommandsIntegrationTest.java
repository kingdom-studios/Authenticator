package pl.kingdomcraft.authenticator.common.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.kingdomcraft.authenticator.common.support.AuthFixture.NOTCH;
import static pl.kingdomcraft.authenticator.common.support.AuthFixture.PASSWORD;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.auth.AuthService.Outcome;
import pl.kingdomcraft.authenticator.common.auth.AuthService.Result;
import pl.kingdomcraft.authenticator.common.auth.PlayerSession;
import pl.kingdomcraft.authenticator.common.message.AuthCommands.Command;
import pl.kingdomcraft.authenticator.common.message.AuthCommands.Response;
import pl.kingdomcraft.authenticator.common.premium.PremiumApi.Answer;
import pl.kingdomcraft.authenticator.common.support.AuthFixture;

/** The player commands end to end: what is typed, what the player is told, whether they are kicked. */
class AuthCommandsIntegrationTest {

	private AuthFixture f;
	private AuthCommands commands;

	@BeforeEach
	void setUp() throws Exception {
		this.f = new AuthFixture();
		this.commands = new AuthCommands(this.f.service);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (this.f != null)
			this.f.close();
	}

	private Response run(PlayerSession session, Command command, String... args) throws Exception {
		return this.commands.run(session, command, List.of(args)).get(10, TimeUnit.SECONDS);
	}

	private static void assertResponse(String key, boolean kick, Response response) {
		assertEquals(key, response.key(), response.toString());
		assertEquals(kick, response.kick(), response.toString());
	}

	@Test
	void everyCommandNamesItsUsageWhenTheArgumentsDoNotFit() throws Exception {
		final PlayerSession session = this.f.newPlayer("Steve");

		assertResponse("usage.login", false, this.run(session, Command.LOGIN));
		assertResponse("usage.register", false, this.run(session, Command.REGISTER, "one"));
		assertResponse("usage.register", false, this.run(session, Command.REGISTER, "a", "b", "c"));
		assertResponse("usage.changepassword", false, this.run(session, Command.CHANGEPASSWORD, "one"));
		assertResponse("usage.unregister", false, this.run(session, Command.UNREGISTER));
		assertResponse("usage.premium", false, this.run(session, Command.PREMIUM, "extra"));
		assertResponse("usage.cracked", false, this.run(session, Command.CRACKED, "one"));

		assertEquals(0, this.f.count("SELECT COUNT(*) FROM auth_accounts"), "a wrong number of arguments does nothing");
	}

	@Test
	void registeringAndLoggingIn() throws Exception {
		final PlayerSession session = this.f.newPlayer("Steven");

		assertResponse("outcome.password-mismatch", false, this.run(session, Command.REGISTER, PASSWORD, "different"));

		final Response weak = this.run(session, Command.REGISTER, "abc", "abc");

		assertResponse("weak.too-short", false, weak);
		assertEquals(Map.of("min", "6"), weak.values());

		assertResponse("weak.forbidden", false, this.run(session, Command.REGISTER, "password123", "password123"));
		assertResponse("weak.same-as-name", false, this.run(session, Command.REGISTER, "steven", "steven"));

		final Response registered = this.run(session, Command.REGISTER, PASSWORD, PASSWORD);

		assertResponse("register.ok", false, registered);
		assertTrue(registered.ok());
		assertResponse("outcome.already-logged-in", false, this.run(session, Command.LOGIN, PASSWORD));
	}

	@Test
	void aWrongPasswordCountsDownAndTheLastOneKicks() throws Exception {
		final PlayerSession session = this.f.returningPlayer("Steve");

		final Response first = this.run(session, Command.LOGIN, "wrong-one");

		assertResponse("outcome.wrong-password", false, first);
		assertEquals(Map.of("attempts", "2"), first.values());
		assertFalse(first.ok());

		assertEquals(Map.of("attempts", "1"), this.run(session, Command.LOGIN, "wrong-two").values());
		assertResponse("kick.too-many-attempts", true, this.run(session, Command.LOGIN, "wrong-three"));
	}

	@Test
	void theRightPasswordLogsIn() throws Exception {
		final PlayerSession session = this.f.returningPlayer("Steve");

		final Response response = this.run(session, Command.LOGIN, PASSWORD);

		assertResponse("login.ok", false, response);
		assertTrue(response.ok());
		assertTrue(session.authenticated());
	}

	@Test
	void aPasswordWithSpacesTypedAfterLoginIsPutBackTogether() throws Exception {
		this.f.accounts.insert(new pl.kingdomcraft.authenticator.common.repository.AccountRepository.NewAccount(java.util.UUID.randomUUID(), "Spacey", false, null,
				this.f.hasher.hash("two words").hash(), null, pl.kingdomcraft.authenticator.common.hash.HashAlgo.BCRYPT, null));

		final PlayerSession spacey = this.f.service.open("Spacey", this.f.accounts.findByUsername("Spacey").orElseThrow().uuid(), this.f.ip, false).session();

		assertResponse("login.ok", false, this.run(spacey, Command.LOGIN, "two", "words"));
	}

	@Test
	void changingTheOwnPassword() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");

		assertResponse("outcome.wrong-password", false, this.run(session, Command.CHANGEPASSWORD, "not-it", "a-new-password"));
		assertResponse("weak.too-short", false, this.run(session, Command.CHANGEPASSWORD, PASSWORD, "tiny"));
		assertResponse("changepassword.ok", false, this.run(session, Command.CHANGEPASSWORD, PASSWORD, "a-new-password"));
		assertResponse("outcome.not-logged-in", false, this.run(this.f.returningPlayer("Alex"), Command.CHANGEPASSWORD, PASSWORD, "a-new-password"));
	}

	@Test
	void unregisteringKicksSoThePlayerCanStartAgain() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");

		assertResponse("outcome.wrong-password", false, this.run(session, Command.UNREGISTER, "not-it"));
		assertResponse("kick.unregistered", true, this.run(session, Command.UNREGISTER, PASSWORD));
		assertEquals(0, this.f.count("SELECT COUNT(*) FROM auth_accounts"));
	}

	@Test
	void switchingToAnOriginalAccountTakesTwoCommandsThenKicks() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Notch");

		final Response first = this.run(session, Command.PREMIUM);

		assertResponse("outcome.confirm-required.premium", false, first);
		assertFalse(first.ok());

		final Response second = this.run(session, Command.PREMIUM);

		assertResponse("kick.reconnect.premium", true, second);
		assertTrue(second.ok());
		assertTrue(this.f.accounts.findByUsername("Notch").orElseThrow().premium());
	}

	@Test
	void switchingToAPasswordAccountTakesTwoCommandsThenKicks() throws Exception {
		final PlayerSession session = this.f.premiumPlayer("Notch", NOTCH);

		assertResponse("outcome.confirm-required.cracked", false, this.run(session, Command.CRACKED, "my-new-password", "my-new-password"));
		assertResponse("kick.reconnect.cracked", true, this.run(session, Command.CRACKED, "my-new-password", "my-new-password"));
		assertFalse(this.f.accounts.findByUsername("Notch").orElseThrow().premium());
	}

	@Test
	void theOtherRefusalsHaveTheirOwnTexts() throws Exception {
		this.f.api.answers.put("Steve", Answer.notFound());

		final PlayerSession steve = this.f.loggedInPlayer("Steve");

		this.run(steve, Command.PREMIUM);

		assertResponse("outcome.not-original-name", false, this.run(steve, Command.PREMIUM));
		assertResponse("outcome.not-premium-account", false, this.run(steve, Command.CRACKED, "my-new-password", "my-new-password"));
		assertResponse("outcome.premium-account", false, this.run(this.f.premiumPlayer("Notch", NOTCH), Command.PREMIUM));

		this.f.api.answers.clear();
		this.run(steve, Command.PREMIUM);

		assertResponse("outcome.lookup-failed", false, this.run(steve, Command.PREMIUM));
	}

	@Test
	void everyOutcomeMapsToAKeyThatExists() {
		final Messages messages = Messages.load("en", Map.of());

		for (final Command command : Command.values())
			for (final Outcome outcome : Outcome.values()) {
				final String detail = switch (outcome) {
					case WEAK_PASSWORD -> "TOO_SHORT:6";
					default -> "1";
				};

				final Response response = AuthCommands.respond(command, new Result(outcome, detail));

				assertTrue(messages.has(response.key()), command + "/" + outcome + " -> " + response.key());
			}
	}

	@Test
	void commandsAreFoundByNameWhateverTheCase() {
		assertEquals(Command.LOGIN, Command.byName("LOGIN").orElseThrow());
		assertEquals(Command.CHANGEPASSWORD, Command.byName("changepassword").orElseThrow());
		assertTrue(Command.byName("l").isEmpty());
	}
}
