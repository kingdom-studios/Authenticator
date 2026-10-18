package pl.kingdomcraft.authenticator.common.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import pl.kingdomcraft.authenticator.common.premium.PremiumApi;

/** A lookup service that answers from a map (keyed by the name as asked) and counts how often it was asked. */
public final class ScriptedPremiumApi implements PremiumApi {

	public final Map<String, Answer> answers = new ConcurrentHashMap<>();
	public final AtomicInteger calls = new AtomicInteger();
	public volatile Answer fallback = Answer.unavailable("down");

	@Override
	public String name() {
		return "scripted";
	}

	@Override
	public Answer lookup(String username) {
		this.calls.incrementAndGet();

		return this.answers.getOrDefault(username, this.fallback);
	}
}
