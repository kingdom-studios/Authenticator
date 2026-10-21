package pl.kingdomcraft.authenticator.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.repository.AuditRepository.Event;
import pl.kingdomcraft.authenticator.common.support.AuthFixture;

class MaintenanceIntegrationTest {

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

	private Maintenance maintenance(int auditDays) {
		return new Maintenance(this.f.ipSessions, this.f.blocks, this.f.premiumCache, this.f.audit, auditDays, this.f.clock, AuthFixture.LOGGER);
	}

	@Test
	void removesWhatHasExpiredAndKeepsTheRest() throws Exception {
		final PlayerSession steve = this.f.loggedInPlayer("Steve");
		final PlayerSession alex = this.f.loggedInPlayer("Alex");

		this.f.ipSessions.save(alex.account().id(), this.f.ip, this.f.clock.instant().plus(Duration.ofDays(40))); // Alex's session outlives the hour of the others
		this.f.blocks.block(this.f.otherIp, this.f.clock.instant().plus(Duration.ofMinutes(5)), "short");
		this.f.blocks.block(java.net.InetAddress.getByName("203.0.113.99"), this.f.clock.instant().plus(Duration.ofDays(40)), "long");
		this.f.premiumCache.save("Old", UUID.randomUUID(), "mojang", this.f.clock.instant());
		this.f.premiumCache.save("Recent", UUID.randomUUID(), "mojang", this.f.clock.instant().plus(Duration.ofDays(20)));
		this.f.audit.log(Event.LOGIN_SUCCESS, steve.uuid(), "Steve", this.f.ip, null, "velocity-1", null);

		this.f.clock.advance(Duration.ofDays(31));

		final Maintenance.Report report = this.maintenance(90).runOnce();

		assertEquals(1, report.sessions(), "Steve's");
		assertEquals(1, report.ipBlocks(), "the short one only");
		assertEquals(1, report.premiumCache(), "only the answer older than a month");
		assertEquals(0, report.audit(), "inside its 90 days");
		assertEquals(1, this.f.count("SELECT COUNT(*) FROM auth_premium_cache"));
		assertEquals(0, this.f.count("SELECT COUNT(*) FROM auth_ip_blocks WHERE reason = 'short'"));
		assertEquals(1, this.f.count("SELECT COUNT(*) FROM auth_ip_blocks WHERE reason = 'long'"));
		assertEquals(1, this.f.count("SELECT COUNT(*) FROM auth_sessions"), "Alex's is still valid");
	}

	@Test
	void auditEntriesLiveForTheirRetentionOnly() throws Exception {
		this.f.audit.log(Event.LOGIN_SUCCESS, null, "Steve", this.f.ip, null, "velocity-1", null);
		this.f.clock.advance(Duration.ofDays(10));

		assertEquals(0, this.maintenance(30).runOnce().audit());
		assertEquals(0, this.maintenance(0).runOnce().audit(), "0 keeps them for ever");

		this.f.clock.advance(Duration.ofDays(30));

		assertEquals(0, this.maintenance(0).runOnce().audit(), "still for ever");
		assertEquals(1, this.maintenance(30).runOnce().audit());
		assertEquals(0, this.f.count("SELECT COUNT(*) FROM auth_audit WHERE event = 'LOGIN_SUCCESS'"));
	}

	@Test
	void aFailingPartDoesNotStopTheOthers() throws Exception {
		this.f.blocks.block(this.f.otherIp, this.f.clock.instant().plus(Duration.ofMinutes(1)), "short");
		this.f.clock.advance(Duration.ofMinutes(5));
		this.f.execute("RENAME TABLE auth_sessions TO auth_sessions_away");

		try {
			final Maintenance.Report report = this.maintenance(90).runOnce();

			assertEquals(0, report.sessions(), "that part failed");
			assertEquals(1, report.ipBlocks(), "this one did not care");
			assertTrue(report.total() >= 1);

		} finally {
			this.f.execute("RENAME TABLE auth_sessions_away TO auth_sessions");
		}
	}
}
