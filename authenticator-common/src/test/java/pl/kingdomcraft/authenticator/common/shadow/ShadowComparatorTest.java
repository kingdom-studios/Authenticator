package pl.kingdomcraft.authenticator.common.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.auth.PreLoginDecider.Decision;
import pl.kingdomcraft.authenticator.common.auth.PreLoginDecider.Mode;
import pl.kingdomcraft.authenticator.common.auth.PreLoginDecider.Reason;
import pl.kingdomcraft.authenticator.common.shadow.ShadowComparator.Observed;
import pl.kingdomcraft.authenticator.common.shadow.ShadowComparator.Verdict;
import pl.kingdomcraft.authenticator.common.support.MutableClock;

class ShadowComparatorTest {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T12:00:00Z"));
	private final List<String> log = new ArrayList<>();
	private final ShadowComparator comparator = new ShadowComparator(this.clock, this.log::add);

	private static Decision decision(Mode mode, Reason reason) {
		return new Decision(mode, reason, "", "test");
	}

	@Test
	void everyCombinationIsJudgedAsTheTableSays() {
		final Mode[] modes = Mode.values();
		final Observed[] observed = Observed.values();

		for (final Mode mode : modes)
			for (final Observed theirs : observed) {
				final Verdict expected = theirs == Observed.UNDECIDED ? Verdict.UNDECIDED
						: (mode == Mode.ONLINE && theirs == Observed.ONLINE) || (mode == Mode.OFFLINE && theirs == Observed.OFFLINE) || (mode == Mode.DENY && theirs == Observed.DENIED) ? Verdict.AGREE : Verdict.DISAGREE;

				assertEquals(expected, this.comparator.compare("Steve", decision(mode, Reason.NEW_NON_PREMIUM), theirs), mode + " vs " + theirs);
			}

		final ShadowComparator.Stats stats = this.comparator.stats();

		assertEquals(3, stats.agree());
		assertEquals(6, stats.disagree());
		assertEquals(3, stats.undecided());
		assertEquals(9, stats.compared(), "undecided answers are not part of the comparison");
	}

	@Test
	void aDisagreementSaysWhatEachSideWouldHaveDone() {
		this.comparator.compare("Notch", decision(Mode.ONLINE, Reason.ORIGINAL_NAME), Observed.OFFLINE);

		assertEquals(1, this.comparator.recent().size());
		assertEquals("Notch: we would require Mojang (ORIGINAL_NAME), the other plugin: OFFLINE", this.comparator.recent().get(0).toString());
		assertEquals(1, this.log.size());
		assertTrue(this.log.get(0).contains("Notch"), this.log.get(0));
	}

	@Test
	void uuidsAreComparedToo() {
		final UUID a = UUID.randomUUID();

		assertEquals(Verdict.AGREE, this.comparator.compareUuid("Steve", a, a));
		assertEquals(Verdict.DISAGREE, this.comparator.compareUuid("Steve", a, UUID.randomUUID()));

		assertEquals(1, this.comparator.stats().uuidAgree());
		assertEquals(1, this.comparator.stats().uuidDisagree());
		assertTrue(this.comparator.recent().get(0).toString().contains(a.toString()));
	}

	@Test
	void theLogIsCappedButTheCountIsNot() {
		for (int i = 0; i < 100; i++)
			this.comparator.compare("Bot" + i, decision(Mode.DENY, Reason.BAD_NICKNAME), Observed.OFFLINE);

		assertEquals(10, this.log.size(), "ten lines a minute, however many disagree");
		assertEquals(100, this.comparator.stats().disagree());

		this.clock.advance(Duration.ofSeconds(61));
		this.comparator.compare("Late", decision(Mode.DENY, Reason.BAD_NICKNAME), Observed.OFFLINE);

		assertEquals(10 + 2, this.log.size(), "a line about the 90 that were not listed, then the new one");
		assertTrue(this.log.get(10).contains("90 more"), this.log.get(10));
		assertTrue(this.log.get(11).contains("Late"));
	}

	@Test
	void onlyTheLatestDisagreementsAreKept() {
		for (int i = 0; i < 80; i++)
			this.comparator.compare("Bot" + i, decision(Mode.DENY, Reason.BAD_NICKNAME), Observed.OFFLINE);

		final List<ShadowComparator.Disagreement> recent = this.comparator.recent();

		assertEquals(50, recent.size());
		assertEquals("Bot30", recent.get(0).username());
		assertEquals("Bot79", recent.get(49).username());
	}
}
