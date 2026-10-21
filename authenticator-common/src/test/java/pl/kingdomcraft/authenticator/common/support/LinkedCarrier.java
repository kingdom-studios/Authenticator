package pl.kingdomcraft.authenticator.common.support;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import pl.kingdomcraft.authenticator.common.bridge.PluginMessagingTransport;
import pl.kingdomcraft.authenticator.common.bridge.Recipient;
import pl.kingdomcraft.authenticator.common.messaging.EnvelopeVerifier;

/** A PluginMessaging carrier whose "connection" leads straight to another carrier, so a proxy and a backend server can talk in a test. */
public final class LinkedCarrier extends PluginMessagingTransport {

	public final List<String> sent = new CopyOnWriteArrayList<>();
	public volatile LinkedCarrier peer;
	public volatile boolean connected = true;

	public LinkedCarrier(EnvelopeVerifier verifier, String nodeId, Clock clock, Logger logger) {
		super(verifier, nodeId, clock, logger);
	}

	@Override
	protected boolean deliver(Recipient recipient, byte[] data) {
		this.sent.add(new String(data, StandardCharsets.UTF_8));

		final LinkedCarrier other = this.peer;

		return this.connected && other != null && other.receive(Origin.TRUSTED_PEER, data);
	}
}
