package pl.kingdomcraft.authenticator.common.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/** A tiny HTTP server on the loopback address that answers as the test says, and remembers what it was asked. */
public final class FakeHttpServer implements AutoCloseable {

	/** @param status HTTP status; @param body text; @param delayMs how long to wait before answering; @param location sent as a Location header when set */
	public record Reply(int status, String body, long delayMs, String location) {

		public static Reply ok(String body) {
			return new Reply(200, body, 0, null);
		}

		public static Reply status(int status) {
			return new Reply(status, "", 0, null);
		}

		public static Reply slow(String body, long delayMs) {
			return new Reply(200, body, delayMs, null);
		}

		public static Reply redirect(String location) {
			return new Reply(302, "", 0, location);
		}
	}

	private final HttpServer server;
	private final Map<String, Reply> replies = new ConcurrentHashMap<>();
	private final List<String> requests = new CopyOnWriteArrayList<>();
	private final AtomicInteger count = new AtomicInteger();

	public FakeHttpServer() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		this.server.setExecutor(Executors.newCachedThreadPool());
		this.server.createContext("/", exchange -> {
			this.count.incrementAndGet();
			this.requests.add(exchange.getRequestURI().getPath() + "|" + exchange.getRequestHeaders().getFirst("User-Agent"));

			final Reply reply = this.replies.getOrDefault(exchange.getRequestURI().getPath(), Reply.status(404));

			try {
				if (reply.delayMs() > 0)
					Thread.sleep(reply.delayMs());

				if (reply.location() != null)
					exchange.getResponseHeaders().add("Location", reply.location());

				final byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);

				exchange.sendResponseHeaders(reply.status(), reply.status() == 204 ? -1 : body.length);

				if (reply.status() != 204)
					try (OutputStream stream = exchange.getResponseBody()) {
						stream.write(body);
					}

			} catch (final InterruptedException | IOException ex) {
				// the client gave up (a timeout test); nothing to answer
			} finally {
				exchange.close();
			}
		});
		this.server.start();
	}

	/** Base URL ending in a slash, as the API clients expect it. */
	public String url() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/";
	}

	public void reply(String path, Reply reply) {
		this.replies.put(path, reply);
	}

	public int requestCount() {
		return this.count.get();
	}

	public List<String> requests() {
		return this.requests;
	}

	@Override
	public void close() {
		this.server.stop(0);
	}
}
