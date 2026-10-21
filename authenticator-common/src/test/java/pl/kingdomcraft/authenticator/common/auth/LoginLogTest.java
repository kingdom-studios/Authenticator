package pl.kingdomcraft.authenticator.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.kingdomcraft.authenticator.common.support.AuthFixture.NOTCH;
import static pl.kingdomcraft.authenticator.common.support.AuthFixture.PASSWORD;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.support.AuthFixture;

/** The console says who logged in and how, once per login, in English. */
class LoginLogTest {

	private AuthFixture f;
	private final List<String> lines = new CopyOnWriteArrayList<>();
	private Handler handler;

	@BeforeEach
	void setUp() throws Exception {
		this.f = new AuthFixture();
		this.handler = new Handler() {

			@Override
			public void publish(LogRecord record) {
				if (record.getLevel() == Level.INFO)
					LoginLogTest.this.lines.add(record.getMessage());
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		};
		AuthFixture.LOGGER.addHandler(this.handler);
		AuthFixture.LOGGER.setUseParentHandlers(false);
	}

	@AfterEach
	void tearDown() throws Exception {
		AuthFixture.LOGGER.removeHandler(this.handler);
		AuthFixture.LOGGER.setUseParentHandlers(true);

		if (this.f != null)
			this.f.close();
	}

	private List<String> logins() {
		return this.lines.stream().filter(line -> line.contains("logged in successfully")).toList();
	}

	private String ip() {
		return this.f.ip.getHostAddress();
	}

	@Test
	void registeringAndTypingThePasswordAreLoggedWithTheirMethod() throws Exception {
		final PlayerSession session = this.f.newPlayer("Steve");

		assertTrue(this.logins().isEmpty(), "waiting to register is not a login");

		this.f.service.register(session, PASSWORD, PASSWORD).get();

		assertEquals(List.of("Steve logged in successfully (method: password, new account registered) from " + this.ip() + "."), this.logins());

		this.f.service.close(session);
		this.f.ipSessions.delete(session.account().id());
		this.lines.clear();

		final PlayerSession again = this.f.service.open("Steve", session.uuid(), this.f.ip, false).session();

		assertTrue(this.logins().isEmpty());

		this.f.service.login(again, "wrong-password").get();

		assertTrue(this.logins().isEmpty(), "a wrong password is not a login");

		this.f.service.login(again, PASSWORD).get();

		assertEquals(List.of("Steve logged in successfully (method: password) from " + this.ip() + "."), this.logins());
	}

	@Test
	void aSessionAndAPremiumLoginAreLoggedWithTheirMethod() throws Exception {
		final PlayerSession first = this.f.newPlayer("Alex");

		this.f.service.register(first, PASSWORD, PASSWORD).get();
		this.f.service.close(first);
		this.lines.clear();

		this.f.service.open("Alex", first.uuid(), this.f.ip, false);

		assertEquals(List.of("Alex logged in successfully (method: session, returned from the address of an earlier login) from " + this.ip() + "."), this.logins());

		this.lines.clear();
		this.f.premiumPlayer("Notch", NOTCH);

		assertEquals(List.of("Notch logged in successfully (method: premium account, confirmed by Mojang) from " + this.ip() + "."), this.logins());
	}

	@Test
	void aLoginForcedByAnAdminIsLogged() throws Exception {
		final PlayerSession session = this.f.returningPlayer("Steve");

		this.lines.clear();
		this.f.service.forceAuthenticated(session);

		assertEquals(List.of("Steve logged in successfully (method: forced by an admin) from " + this.ip() + "."), this.logins());
	}
}
