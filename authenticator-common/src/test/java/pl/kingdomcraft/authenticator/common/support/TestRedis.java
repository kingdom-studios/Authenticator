package pl.kingdomcraft.authenticator.common.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;

import redis.clients.jedis.Jedis;

/**
 * A throwaway {@code redis-server} process for the integration tests (the binary must be on the PATH; tests are skipped without it).
 * It can be stopped and started again on the same port, which the failover tests rely on. TLS mode needs {@code openssl} and
 * {@code keytool} and serves a self-signed certificate that is valid only for the given DNS name.
 */
public final class TestRedis implements AutoCloseable {

	private static final String TRUST_PASSWORD = "changeit";

	private final int port;
	private final boolean tls;
	private final Path directory;
	private Process process;

	private TestRedis(int port, boolean tls, Path directory) {
		this.port = port;
		this.tls = tls;
		this.directory = directory;
	}

	public static boolean binaryAvailable() {
		return runs("redis-server", "--version");
	}

	/** A plain Redis, already running. */
	public static TestRedis start() throws Exception {
		Assumptions.assumeTrue(binaryAvailable(), "redis-server is not on the PATH");

		final TestRedis redis = new TestRedis(freePort(), false, Files.createTempDirectory("auth-redis"));
		redis.start0();

		return redis;
	}

	/** A TLS-only Redis whose certificate is valid for {@code dnsName} only, already running. */
	public static TestRedis startTls(String dnsName) throws Exception {
		Assumptions.assumeTrue(binaryAvailable() && runs("openssl", "version") && runs("keytool", "-help"), "redis-server, openssl and keytool are needed for the TLS tests");

		final Path directory = Files.createTempDirectory("auth-redis-tls");

		exec(directory, "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "key.pem", "-out", "cert.pem", "-days", "2", "-subj", "/CN=" + dnsName, "-addext",
				"subjectAltName=DNS:" + dnsName);
		exec(directory, "keytool", "-importcert", "-noprompt", "-alias", "redis", "-file", "cert.pem", "-keystore", "trust.p12", "-storetype", "PKCS12", "-storepass", TRUST_PASSWORD);

		final TestRedis redis = new TestRedis(freePort(), true, directory);
		redis.start0();

		return redis;
	}

	public int port() {
		return this.port;
	}

	/** Path of a trust store that trusts this server's certificate. */
	public String trustStore() {
		return this.directory.resolve("trust.p12").toString();
	}

	public String trustStorePassword() {
		return TRUST_PASSWORD;
	}

	public void stop() throws InterruptedException {
		if (this.process != null) {
			this.process.destroy();
			this.process.waitFor(5, TimeUnit.SECONDS);
			this.process = null;
		}
	}

	/** Starts the server again on the same port (after {@link #stop}). */
	public void restart() throws Exception {
		this.stop();
		this.start0();
	}

	@Override
	public void close() throws InterruptedException {
		this.stop();
	}

	private void start0() throws Exception {
		final List<String> command = new ArrayList<>(List.of("redis-server", "--bind", "127.0.0.1", "--save", "", "--appendonly", "no", "--dir", this.directory.toString()));

		if (this.tls)
			command.addAll(List.of("--port", "0", "--tls-port", String.valueOf(this.port), "--tls-cert-file", "cert.pem", "--tls-key-file", "key.pem", "--tls-ca-cert-file", "cert.pem",
					"--tls-auth-clients", "no"));
		else
			command.addAll(List.of("--port", String.valueOf(this.port)));

		this.process = new ProcessBuilder(command).directory(this.directory.toFile()).redirectErrorStream(true).redirectOutput(this.directory.resolve("redis.log").toFile()).start();

		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

		while (!this.accepts()) {
			if (System.nanoTime() > deadline || !this.process.isAlive())
				throw new IllegalStateException("redis-server did not start, see " + this.directory.resolve("redis.log"));

			Thread.sleep(50);
		}
	}

	private boolean accepts() {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", this.port), 200);

			return true;

		} catch (final IOException ex) {
			return false;
		}
	}

	/** Plain-text PING, for tests that need a quick check on a non-TLS server. */
	public boolean pings() {
		try (Jedis jedis = new Jedis("127.0.0.1", this.port)) {
			return "PONG".equals(jedis.ping());

		} catch (final RuntimeException ex) {
			return false;
		}
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
			return socket.getLocalPort();
		}
	}

	private static boolean runs(String... command) {
		try {
			final Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();

			return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;

		} catch (final IOException | InterruptedException ex) {
			return false;
		}
	}

	private static void exec(Path directory, String... command) throws IOException, InterruptedException {
		final Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("exec.log").toFile()).start();

		if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0)
			throw new IllegalStateException(String.join(" ", command) + " failed, see " + directory.resolve("exec.log"));
	}
}
