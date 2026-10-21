package pl.kingdomcraft.authenticator.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.kingdomcraft.authenticator.common.support.AuthFixture.NOTCH;
import static pl.kingdomcraft.authenticator.common.support.AuthFixture.PASSWORD;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.auth.AuthService.OpenFailure;
import pl.kingdomcraft.authenticator.common.auth.AuthService.Opened;
import pl.kingdomcraft.authenticator.common.hash.HashAlgo;
import pl.kingdomcraft.authenticator.common.repository.AccountRepository.NewAccount;
import pl.kingdomcraft.authenticator.common.repository.PresenceRepository.Presence;
import pl.kingdomcraft.authenticator.common.support.AuthFixture;

/** What happens when a player connects: which UUID they get, and whether they start authenticated. */
class AuthServiceConnectIntegrationTest {

	private AuthFixture f;

	@BeforeEach
	void setUp() throws Exception {
		this.f = new AuthFixture();
	}

	@AfterEach
	void tearDown() throws Exception {
		if (this.f != null)
			this.f.close();
	}

	private Presence presenceOf(PlayerSession session) throws Exception {
		return this.f.presence.find(session.uuid()).orElseThrow();
	}

	// ---- which UUID ----

	@Test
	void aNewNonPremiumPlayerKeepsTheUuidVelocityMade() {
		final UUID offline = UUID.randomUUID();

		assertEquals(offline, this.f.service.profileUuid("Steve", false, offline));
	}

	@Test
	void aRegisteredNonPremiumPlayerKeepsTheUuidOfTheirAccountWhateverVelocityMade() throws Exception {
		final UUID stored = UUID.randomUUID();

		this.f.accounts.insert(new NewAccount(stored, "Steve", false, null, "$2a$04$h", null, HashAlgo.BCRYPT, null));

		assertEquals(stored, this.f.service.profileUuid("Steve", false, UUID.randomUUID()));
	}

	@Test
	void anOriginalPlayerWhoChangedTheirNameKeepsTheUuidOfTheirAccount() throws Exception {
		final UUID stored = UUID.randomUUID();

		this.f.accounts.insert(new NewAccount(stored, "OldName", true, NOTCH, null, null, null, null));

		assertEquals(stored, this.f.service.profileUuid("NewName", true, NOTCH));
	}

	@Test
	void aNewOriginalPlayerKeepsTheirMojangUuid() {
		assertEquals(NOTCH, this.f.service.profileUuid("Notch", true, NOTCH));
	}

	/** The one mapping that must never happen: an original player taking the UUID of another account because the names match. */
	@Test
	void aNameAloneNeverMapsToSomeoneElsesUuid() throws Exception {
		final UUID victim = UUID.randomUUID();
		final UUID victimMojang = UUID.randomUUID();
		final UUID attackerMojang = UUID.randomUUID();

		this.f.accounts.insert(new NewAccount(victim, "SharedName", true, victimMojang, null, null, null, null));

		assertEquals(attackerMojang, this.f.service.profileUuid("SharedName", true, attackerMojang));
	}

	@Test
	void aPremiumAccountDoesNotLendItsUuidToAnOfflineConnection() throws Exception {
		final UUID stored = UUID.randomUUID();
		final UUID offline = UUID.randomUUID();

		this.f.accounts.insert(new NewAccount(stored, "Notch", true, NOTCH, null, null, null, null));

		assertEquals(offline, this.f.service.profileUuid("Notch", false, offline));
	}

	// ---- offline connections ----

	@Test
	void aNewNameStartsWaitingToRegisterAndIsAnnounced() throws Exception {
		final PlayerSession session = this.f.newPlayer("Steve");

		assertEquals(AuthState.PENDING_REGISTER, session.state());
		assertNull(session.account());
		assertFalse(session.premiumConnection());
		assertTrue(this.f.sessions.isOnline("Steve"));

		final Presence presence = this.presenceOf(session);

		assertEquals(AuthState.PENDING_REGISTER, presence.state());
		assertNull(presence.accountId());
		assertEquals(1, presence.epoch());
		assertEquals("velocity-1", presence.proxyId());
		assertEquals("Steve:PENDING_REGISTER:null:1", this.f.notifications.get(0));
	}

	@Test
	void aRegisteredPlayerStartsWaitingToLogIn() throws Exception {
		final PlayerSession session = this.f.returningPlayer("Steve");

		assertEquals(AuthState.PENDING_LOGIN, session.state());
		assertNotNull(session.account());
		assertEquals(AuthState.PENDING_LOGIN, this.presenceOf(session).state());
		assertEquals(session.account().id(), this.presenceOf(session).accountId());
	}

	@Test
	void aPlayerWithAValidSessionFromTheSameAddressIsLoggedInAtOnce() throws Exception {
		final PlayerSession first = this.f.newPlayer("Steve");

		this.f.service.register(first, PASSWORD, PASSWORD).get(); // saves the IP session
		this.f.service.close(first);

		final PlayerSession again = this.f.service.open("Steve", first.uuid(), this.f.ip, false).session();

		assertEquals(AuthState.AUTHENTICATED, again.state());
		assertEquals(AuthMethod.SESSION, again.method());
		assertEquals(AuthMethod.SESSION, this.presenceOf(again).method());
		assertEquals(1, this.f.count("SELECT COUNT(*) FROM auth_audit WHERE event = 'LOGIN_SUCCESS' AND details = 'session'"));
	}

	@Test
	void aSessionDoesNotHelpFromAnotherAddressOrAfterItExpires() throws Exception {
		final PlayerSession first = this.f.newPlayer("Steve");

		this.f.service.register(first, PASSWORD, PASSWORD).get();
		this.f.service.close(first);

		assertEquals(AuthState.PENDING_LOGIN, this.f.service.open("Steve", first.uuid(), this.f.otherIp, false).session().state());

		this.f.service.close(this.f.sessions.findByName("Steve").orElseThrow());
		this.f.clock.advance(Duration.ofMinutes(61));

		assertEquals(AuthState.PENDING_LOGIN, this.f.service.open("Steve", first.uuid(), this.f.ip, false).session().state());
	}

	@Test
	void sessionsCanBeTurnedOff() throws Exception {
		try (AuthFixture off = new AuthFixture(false, 2, 16)) {
			final PlayerSession first = off.newPlayer("Steve");

			off.service.register(first, PASSWORD, PASSWORD).get();
			off.service.close(first);

			assertEquals(AuthState.PENDING_LOGIN, off.service.open("Steve", first.uuid(), off.ip, false).session().state());
			assertEquals(0, off.count("SELECT COUNT(*) FROM auth_sessions"));
		}
	}

	@Test
	void aPremiumAccountOnAnOfflineConnectionIsRefused() throws Exception {
		this.f.accounts.insert(new NewAccount(NOTCH, "Notch", true, NOTCH, null, null, null, null));

		final Opened opened = this.f.service.open("Notch", UUID.randomUUID(), this.f.ip, false);

		assertNull(opened.session());
		assertEquals(OpenFailure.ACCOUNT_TYPE_MISMATCH, opened.failure());
		assertFalse(this.f.sessions.isOnline("Notch"));
	}

	// ---- online connections ----

	@Test
	void aNewOriginalPlayerGetsAnAccountAndIsAuthenticatedWithoutAPassword() throws Exception {
		final PlayerSession session = this.f.premiumPlayer("Notch", NOTCH);

		assertEquals(AuthState.AUTHENTICATED, session.state());
		assertEquals(AuthMethod.PREMIUM, session.method());
		assertTrue(session.premiumConnection());

		final Account account = this.f.accounts.findByUsername("Notch").orElseThrow();

		assertTrue(account.premium());
		assertEquals(NOTCH, account.premiumUuid());
		assertEquals(NOTCH, account.uuid());
		assertFalse(account.hasPassword());
		assertNotNull(account.lastLoginAt());
		assertEquals(AuthMethod.PREMIUM, this.presenceOf(session).method());
		assertEquals(1, this.f.count("SELECT COUNT(*) FROM auth_audit WHERE event = 'LOGIN_SUCCESS' AND details = 'premium'"));
	}

	@Test
	void noAccountIsCreatedForAnOriginalNameUntilMojangHasConfirmedIt() throws Exception {
		// A connection that never completed online authentication never reaches the account stage.
		this.f.service.profileUuid("Notch", false, UUID.randomUUID());

		assertEquals(0, this.f.accounts.count());
		assertEquals(0, this.f.count("SELECT COUNT(*) FROM auth_presence"));
	}

	@Test
	void anOriginalPlayerWithAnAccountIsAuthenticatedAgain() throws Exception {
		this.f.service.close(this.f.premiumPlayer("Notch", NOTCH));

		final PlayerSession again = this.f.premiumPlayer("Notch", NOTCH);

		assertEquals(AuthMethod.PREMIUM, again.method());
		assertEquals(1, this.f.accounts.count());
	}

	@Test
	void anOriginalPlayerWhoChangedTheirNameIsRenamed() throws Exception {
		this.f.service.close(this.f.premiumPlayer("OldName", NOTCH));

		final PlayerSession renamed = this.f.premiumPlayer("NewName", NOTCH);

		assertEquals(AuthState.AUTHENTICATED, renamed.state());
		assertEquals("NewName", this.f.accounts.findByPremiumUuid(NOTCH).orElseThrow().username());
		assertTrue(this.f.accounts.findByUsername("OldName").isEmpty());
		assertEquals(1, this.f.count("SELECT COUNT(*) FROM auth_audit WHERE event = 'ACCOUNT_RENAMED' AND details = 'was OldName'"));
		assertEquals(1, this.f.accounts.count());
	}

	@Test
	void aRenameToANameThatIsTakenIsRefused() throws Exception {
		this.f.service.close(this.f.premiumPlayer("OldName", NOTCH));
		this.f.accounts.insert(new NewAccount(UUID.randomUUID(), "NewName", false, null, "$2a$04$h", null, HashAlgo.BCRYPT, null));

		final UUID uuid = this.f.service.profileUuid("NewName", true, NOTCH);
		final Opened opened = this.f.service.open("NewName", uuid, this.f.ip, true);

		assertEquals(OpenFailure.RENAME_CONFLICT, opened.failure());
	}

	@Test
	void anotherOriginalPlayerCannotTakeAnAccountByItsName() throws Exception {
		this.f.service.close(this.f.premiumPlayer("SharedName", NOTCH));

		final UUID attacker = UUID.randomUUID();
		final UUID uuid = this.f.service.profileUuid("SharedName", true, attacker);
		final Opened opened = this.f.service.open("SharedName", uuid, this.f.ip, true);

		assertNull(opened.session());
		assertEquals(OpenFailure.NAME_OWNED_BY_OTHER, opened.failure());
		assertEquals(NOTCH, this.f.accounts.findByUsername("SharedName").orElseThrow().premiumUuid(), "the account must be untouched");
	}

	@Test
	void anOnlineConnectionForANonPremiumAccountIsRefused() throws Exception {
		this.f.accounts.insert(new NewAccount(UUID.randomUUID(), "Steve", false, null, "$2a$04$h", null, HashAlgo.BCRYPT, null));

		final UUID mojang = UUID.randomUUID();
		final Opened opened = this.f.service.open("Steve", this.f.service.profileUuid("Steve", true, mojang), this.f.ip, true);

		assertEquals(OpenFailure.ACCOUNT_TYPE_MISMATCH, opened.failure());
	}

	@Test
	void anOnlineConnectionWhoseMojangUuidIsUnknownIsRefusedBecauseOwnershipCannotBeChecked() {
		final Opened opened = this.f.service.open("Notch", NOTCH, this.f.ip, true); // profileUuid was never called

		assertEquals(OpenFailure.UNVERIFIED_PROFILE, opened.failure());
		assertNull(opened.session());
	}

	@Test
	void theMojangUuidIsUsedOnceSoItCannotBeReplayedForAnotherConnection() {
		final UUID uuid = this.f.service.profileUuid("Notch", true, NOTCH);

		assertNotNull(this.f.service.open("Notch", uuid, this.f.ip, true).session());

		this.f.service.close(this.f.sessions.findByName("Notch").orElseThrow());

		assertEquals(OpenFailure.UNVERIFIED_PROFILE, this.f.service.open("Notch", uuid, this.f.ip, true).failure());
	}

	// ---- leaving ----

	@Test
	void leavingRemovesThePlayerAndTheirRow() throws Exception {
		final PlayerSession session = this.f.newPlayer("Steve");

		this.f.service.close(session);

		assertFalse(this.f.sessions.isOnline("Steve"));
		assertTrue(this.f.presence.find(session.uuid()).isEmpty());
	}

	@Test
	void aLateDisconnectOfAnOldConnectionDoesNotRemoveTheRowOfTheNewOne() throws Exception {
		final UUID uuid = UUID.randomUUID();
		final PlayerSession old = this.f.service.open("Steve", uuid, this.f.ip, false).session();
		final PlayerSession fresh = this.f.service.open("Steve", uuid, this.f.ip, false).session(); // reconnected before the old disconnect was handled

		this.f.service.close(old);

		assertTrue(this.f.presence.find(uuid).isPresent(), "the new connection's row must survive");
		assertEquals(fresh, this.f.sessions.find(uuid).orElseThrow());

		this.f.service.close(fresh);

		assertTrue(this.f.presence.find(uuid).isEmpty());
	}

	@Test
	void aBrokenDatabaseRefusesTheSession() throws Exception {
		this.f.schema.database().close();

		final Opened opened = this.f.service.open("Steve", UUID.randomUUID(), this.f.ip, false);

		assertEquals(OpenFailure.DATABASE_ERROR, opened.failure());
		assertFalse(this.f.sessions.isOnline("Steve"));
	}

	@Test
	void aFailingStateListenerNeverBreaksTheSession() throws Exception {
		this.f.service.setStateListener(session -> {
			throw new IllegalStateException("boom");
		});

		assertNotNull(this.f.newPlayer("Steve"));
	}
}
