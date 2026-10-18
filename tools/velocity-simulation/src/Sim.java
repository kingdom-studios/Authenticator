import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.connection.*;
import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.proxy.messages.*;
import com.velocitypowered.api.proxy.server.*;
import com.velocitypowered.api.scheduler.*;
import com.velocitypowered.api.util.GameProfile;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import pl.kingdomcraft.authenticator.common.messaging.*;
import pl.kingdomcraft.authenticator.common.support.TestMariaDb;
import pl.kingdomcraft.authenticator.velocity.VelocityRuntime;

/** Drives the real adapter classes with fake Velocity objects, against a real database. A model of the API, not the API itself. */
public final class Sim {

	static int failures = 0;
	static final String SECRET = "0123456789abcdef-test-secret";
	static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok   " : "  FAIL ") + what);
		if (!ok) failures++;
	}

	static String text(Component c) { return c == null ? "null" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c); }

	// ---- fakes ----

	static final class FakeServer implements RegisteredServer {
		final String name; final List<Player> players = new CopyOnWriteArrayList<>();
		FakeServer(String name) { this.name = name; }
		@Override public com.velocitypowered.api.proxy.server.ServerInfo getServerInfo() { return new com.velocitypowered.api.proxy.server.ServerInfo(this.name, null); }
		@Override public Collection<Player> getPlayersConnected() { return this.players; }
		@Override public String toString() { return this.name; }
	}

	static final class FakeConnection implements ServerConnection {
		final FakePlayer player; final FakeServer server;
		final List<byte[]> sent = new CopyOnWriteArrayList<>();
		FakeConnection(FakePlayer player, FakeServer server) { this.player = player; this.server = server; }
		@Override public RegisteredServer getServer() { return this.server; }
		@Override public com.velocitypowered.api.proxy.server.ServerInfo getServerInfo() { return this.server.getServerInfo(); }
		@Override public Player getPlayer() { return this.player; }
		@Override public boolean sendPluginMessage(ChannelIdentifier identifier, byte[] data) { this.sent.add(data); this.player.sentAll.add(data); return true; }
	}

	static final class FakePlayer implements Player {
		final String name; final UUID uuid; final boolean online;
		volatile boolean active = true; volatile FakeConnection current;
		final List<String> messages = new CopyOnWriteArrayList<>();
		volatile String kicked;
		final List<String> moves = new CopyOnWriteArrayList<>();
		final List<byte[]> sentAll = new CopyOnWriteArrayList<>();
		final FakeProxy proxy;
		FakePlayer(FakeProxy proxy, String name, UUID uuid, boolean online) { this.proxy = proxy; this.name = name; this.uuid = uuid; this.online = online; }
		@Override public String getUsername() { return this.name; }
		@Override public UUID getUniqueId() { return this.uuid; }
		@Override public Optional<ServerConnection> getCurrentServer() { return Optional.ofNullable(this.current); }
		@Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("203.0.113.7", 50000); }
		@Override public boolean isOnlineMode() { return this.online; }
		@Override public boolean isActive() { return this.active; }
		@Override public void disconnect(Component reason) { this.kicked = text(reason); this.active = false; }
		@Override public boolean hasPermission(String permission) { return true; }
		@Override public void sendMessage(Component message) { this.messages.add(text(message)); }
		@Override public ConnectionRequestBuilder createConnectionRequest(RegisteredServer server) {
			return () -> {
				this.moves.add(((FakeServer) server).name);
				this.proxy.connect(this, (FakeServer) server);
				return CompletableFuture.completedFuture((ConnectionRequestBuilder.Result) () -> true);
			};
		}
	}

	static final class Task { final Runnable runnable; boolean cancelled; Task(Runnable r) { this.runnable = r; } }

	static final class FakeProxy implements ProxyServer {
		final Map<String, FakeServer> servers = new LinkedHashMap<>();
		final Map<UUID, FakePlayer> players = new ConcurrentHashMap<>();
		final List<Task> tasks = new CopyOnWriteArrayList<>();
		final Map<String, SimpleCommand> commands = new ConcurrentHashMap<>();
		final List<Object[]> listeners = new CopyOnWriteArrayList<>(); // {order, listener, method}
		final Set<String> channels = ConcurrentHashMap.newKeySet();
		boolean onlineMode = true;

		FakeProxy() { for (String n : List.of("auth-1", "lobby-1", "game-1")) this.servers.put(n, new FakeServer(n)); }

		void connect(FakePlayer p, FakeServer s) {
			FakeServer previous = p.current == null ? null : p.current.server;
			if (previous != null) previous.players.remove(p);
			p.current = new FakeConnection(p, s);
			s.players.add(p);
			if (previous != null) this.fire(new PostConnect(p, previous));
		}

		@Override public Optional<Player> getPlayer(UUID uuid) { return Optional.ofNullable(this.players.get(uuid)); }
		@Override public Optional<Player> getPlayer(String username) { return this.players.values().stream().filter(p -> p.name.equalsIgnoreCase(username)).map(p -> (Player) p).findFirst(); }
		@Override public Collection<Player> getAllPlayers() { return new ArrayList<>(this.players.values()); }
		@Override public Optional<RegisteredServer> getServer(String name) { return Optional.ofNullable(this.servers.get(name)); }
		@Override public Collection<RegisteredServer> getAllServers() { return new ArrayList<>(this.servers.values()); }
		@Override public ProxyConfig getConfiguration() { return () -> this.onlineMode; }
		@Override public ChannelRegistrar getChannelRegistrar() {
			return new ChannelRegistrar() {
				@Override public void register(ChannelIdentifier... ids) { for (ChannelIdentifier i : ids) FakeProxy.this.channels.add(i.getId()); }
				@Override public void unregister(ChannelIdentifier... ids) { for (ChannelIdentifier i : ids) FakeProxy.this.channels.remove(i.getId()); }
			};
		}
		@Override public Scheduler getScheduler() {
			return (plugin, runnable) -> new Scheduler.TaskBuilder() {
				@Override public Scheduler.TaskBuilder delay(long t, TimeUnit u) { return this; }
				@Override public Scheduler.TaskBuilder repeat(long t, TimeUnit u) { return this; }
				@Override public ScheduledTask schedule() { Task task = new Task(runnable); FakeProxy.this.tasks.add(task); return () -> task.cancelled = true; }
			};
		}
		@Override public CommandManager getCommandManager() {
			return new CommandManager() {
				@Override public CommandMeta.Builder metaBuilder(String alias) {
					return new CommandMeta.Builder() {
						@Override public CommandMeta.Builder aliases(String... a) { return this; }
						@Override public CommandMeta.Builder plugin(Object p) { return this; }
						@Override public CommandMeta build() { return new NamedMeta(alias); }
					};
				}
				@Override public void register(CommandMeta meta, Command command) { FakeProxy.this.commands.put(((NamedMeta) meta).alias, (SimpleCommand) command); }
			};
		}
		@Override public EventManager getEventManager() {
			return new EventManager() {
				@Override public void register(Object plugin, Object listener) {
					for (Method m : listener.getClass().getMethods()) {
						Subscribe s = m.getAnnotation(Subscribe.class);
						if (s != null) FakeProxy.this.listeners.add(new Object[] { s.order(), listener, m });
					}
				}
				@Override public void unregisterListeners(Object plugin) { FakeProxy.this.listeners.clear(); }
				@Override public void unregisterListener(Object plugin, Object listener) { FakeProxy.this.listeners.removeIf(l -> l[1] == listener); }
			};
		}

		/** Calls every handler for the event type in PostOrder, as Velocity does. */
		void fire(Object event) {
			List<Object[]> matching = new ArrayList<>();
			for (Object[] l : this.listeners) {
				Method m = (Method) l[2];
				if (m.getParameterTypes()[0].isInstance(event)) matching.add(l);
			}
			matching.sort(Comparator.comparingInt(l -> ((PostOrder) l[0]).ordinal()));
			for (Object[] l : matching) {
				try { ((Method) l[2]).invoke(l[1], event); } catch (java.lang.reflect.InvocationTargetException e) { throw new RuntimeException(e.getCause()); } catch (IllegalAccessException e) { throw new RuntimeException(e); }
			}
		}
	}

	record NamedMeta(String alias) implements CommandMeta { }

	// events
	static final class Pre implements PreLoginEvent {
		PreLoginComponentResult result = PreLoginComponentResult.allowed(); final String username; final UUID uuid; final InboundConnection connection;
		Pre(String username, UUID uuid) { this.username = username; this.uuid = uuid; this.connection = () -> new InetSocketAddress("203.0.113.7", 50000); }
		@Override public InboundConnection getConnection() { return this.connection; }
		@Override public String getUsername() { return this.username; }
		@Override public UUID getUniqueId() { return this.uuid; }
		@Override public PreLoginComponentResult getResult() { return this.result; }
		@Override public void setResult(PreLoginComponentResult r) { this.result = r; }
	}
	static final class Profile implements GameProfileRequestEvent {
		final String username; final GameProfile original; final boolean online; GameProfile profile;
		Profile(String username, GameProfile original, boolean online) { this.username = username; this.original = original; this.profile = original; this.online = online; }
		@Override public InboundConnection getConnection() { return () -> new InetSocketAddress("203.0.113.7", 50000); }
		@Override public String getUsername() { return this.username; }
		@Override public GameProfile getOriginalProfile() { return this.original; }
		@Override public boolean isOnlineMode() { return this.online; }
		@Override public GameProfile getGameProfile() { return this.profile; }
		@Override public void setGameProfile(GameProfile p) { this.profile = p; }
	}
	static final class Login implements LoginEvent {
		ComponentResult result = ComponentResult.allowed(); final Player player;
		Login(Player p) { this.player = p; }
		@Override public Player getPlayer() { return this.player; }
		@Override public ComponentResult getResult() { return this.result; }
		@Override public void setResult(ComponentResult r) { this.result = r; }
	}
	record Disc(Player player) implements DisconnectEvent { @Override public Player getPlayer() { return this.player; } }
	static final class Choose implements PlayerChooseInitialServerEvent {
		final Player player; RegisteredServer server;
		Choose(Player p) { this.player = p; }
		@Override public Player getPlayer() { return this.player; }
		@Override public Optional<RegisteredServer> getInitialServer() { return Optional.ofNullable(this.server); }
		@Override public void setInitialServer(RegisteredServer s) { this.server = s; }
	}
	static final class PreConnect implements ServerPreConnectEvent {
		final Player player; final RegisteredServer original; ServerResult result;
		PreConnect(Player p, RegisteredServer s) { this.player = p; this.original = s; this.result = ServerResult.allowed(s); }
		@Override public Player getPlayer() { return this.player; }
		@Override public RegisteredServer getOriginalServer() { return this.original; }
		@Override public ServerResult getResult() { return this.result; }
		@Override public void setResult(ServerResult r) { this.result = r; }
	}
	record PostConnect(Player player, RegisteredServer previous) implements ServerPostConnectEvent {
		@Override public Player getPlayer() { return this.player; }
		@Override public RegisteredServer getPreviousServer() { return this.previous; }
	}
	static final class Pm implements PluginMessageEvent {
		final ChannelMessageSource source; final byte[] data; ForwardResult result = ForwardResult.forward(); final ChannelIdentifier id;
		Pm(ChannelMessageSource source, ChannelIdentifier id, byte[] data) { this.source = source; this.id = id; this.data = data; }
		@Override public ChannelMessageSource getSource() { return this.source; }
		@Override public ChannelMessageSink getTarget() { return new ChannelMessageSink() { }; }
		@Override public ChannelIdentifier getIdentifier() { return this.id; }
		@Override public byte[] getData() { return this.data; }
		@Override public ForwardResult getResult() { return this.result; }
		@Override public void setResult(ForwardResult r) { this.result = r; }
	}

	static org.slf4j.Logger slf4j() {
		InvocationHandler h = (proxy, method, args) -> {
			String n = method.getName();
			if ((n.equals("warn") || n.equals("error")) && args != null && args.length > 0 && args[0] instanceof String s) System.out.println("      [" + n + "] " + s.split("\n")[0]);
			return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
		};
		return (org.slf4j.Logger) Proxy.newProxyInstance(Sim.class.getClassLoader(), new Class<?>[] { org.slf4j.Logger.class }, h);
	}

	static String config(TestMariaDb.Server server, String schema, boolean dryRun, String mode, int timeoutSeconds) {
		return """
				node-id: velocity-1
				language: en
				dry-run: %s
				database:
				  use: private
				  name: %s
				  pool-size: 4
				  endpoints:
				    private: {host: %s, port: %d, user: %s, password: %s, ssl-mode: disable}
				bridge: {mode: %s, secret: "%s", database-poll-seconds: 1}
				routing: {auth-servers: [auth-1], lobby-servers: [lobby-1]}
				login: {timeout-seconds: %d}
				passwords: {bcrypt-cost: 4, threads: 2}
				limits: {connections-per-ip: 1000}
				premium: {apis: [mojang]}
				""".formatted(dryRun, schema, server.host(), server.port(), server.user(), server.password(), mode, SECRET, timeoutSeconds);
	}

	static <T> T await(CompletableFuture<T> f) throws Exception { return f.get(10, TimeUnit.SECONDS); }

	static boolean waitFor(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
		long end = System.nanoTime() + ms * 1_000_000L;
		while (System.nanoTime() < end) { if (c.getAsBoolean()) return true; Thread.sleep(20); }
		return c.getAsBoolean();
	}

	static void exec(TestMariaDb.Schema schema, String sql) throws Exception {
		schema.database().query(c -> { try (var st = c.createStatement()) { st.execute(sql); } return null; });
	}

	/** One whole connection: pre-login, profile, login, initial server, connect, post-connect. */
	static FakePlayer join(FakeProxy proxy, Object plugin, String name, UUID clientUuid, boolean online, UUID originalProfileUuid, String[] out) {
		Pre pre = new Pre(name, clientUuid);
		proxy.fire(pre);
		out[0] = pre.result.toString();
		if (!pre.result.isAllowed()) { out[1] = text(pre.result.reason()); return null; }
		boolean forcedOnline = pre.result.isOnlineModeAllowed();
		Profile profile = new Profile(name, new GameProfile(originalProfileUuid, name), forcedOnline);
		proxy.fire(profile);
		FakePlayer player = new FakePlayer(proxy, name, profile.profile.getId(), forcedOnline);
		Login login = new Login(player);
		proxy.fire(login);
		if (!login.result.isAllowed()) { out[1] = text(login.result.getReasonComponent().orElse(null)); return null; }
		proxy.players.put(player.uuid, player);
		Choose choose = new Choose(player);
		proxy.fire(choose);
		if (choose.server == null) return player;
		PreConnect pc = new PreConnect(player, choose.server);
		proxy.fire(pc);
		FakeServer target = (FakeServer) pc.result.getServer().orElse(null);
		if (target != null) { proxy.connect(player, target); proxy.fire(new PostConnect(player, null)); }
		return player;
	}

	public static void main(String[] args) throws Exception {
		TestMariaDb.Server server = TestMariaDb.requireServer();
		Path dir = Files.createTempDirectory("vsim");
		TestMariaDb.Schema schema = TestMariaDb.createSchema(true);
		try {
			scenario(server, schema, dir);
			shadow(server, schema, dir);
		} finally {
			schema.close();
		}
		System.out.println(failures == 0 ? "\nALL SIMULATED SCENARIOS PASSED" : "\n" + failures + " CHECKS FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	static void scenario(TestMariaDb.Server server, TestMariaDb.Schema schema, Path dir) throws Exception {
		System.out.println("== active mode");
		Files.writeString(dir.resolve("config.yml"), config(server, schema.name(), false, "plugin-messaging", 5));

		// the Mojang lookups are answered from the database cache, so no network is needed
		exec(schema, "INSERT INTO auth_premium_cache (username_lower, premium_uuid, source, checked_at) VALUES ('notch', '" + NOTCH + "', 'sim', NOW(3))");
		exec(schema, "INSERT INTO auth_premium_cache (username_lower, premium_uuid, source, checked_at) VALUES ('steve', NULL, 'sim', NOW(3))");

		FakeProxy proxy = new FakeProxy();
		Object plugin = new Object();
		VelocityRuntime runtime = VelocityRuntime.start(plugin, proxy, slf4j(), dir);
		check(proxy.channels.contains("authenticator:bridge"), "the bridge channel is registered");
		check(proxy.commands.keySet().containsAll(List.of("login", "register", "changepassword", "unregister", "premium", "cracked", "authadmin")), "all commands are registered: " + proxy.commands.keySet());

		EnvelopeVerifier verifier = new EnvelopeVerifier(new HmacSigner(SECRET), Duration.ofSeconds(30), Clock.systemUTC(), java.util.logging.Logger.getLogger("sim"));

		// --- a new non-premium player
		String[] out = new String[2];
		FakePlayer steve = join(proxy, plugin, "Steve", null, false, UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(StandardCharsets.UTF_8)), out);
		check("OFFLINE".equals(out[0]), "Steve is made an offline connection: " + out[0]);
		check(steve != null && steve.current != null && steve.current.server.name.equals("auth-1"), "Steve starts on the waiting server");
		check(steve.messages.stream().anyMatch(m -> m.contains("Register:")), "Steve is told to register: " + steve.messages);
		check(!steve.current.sent.isEmpty() && BridgeCheck.isAuthState(verifier, steve.current.sent.get(0), "PENDING_REGISTER"), "the server he is on is told, signed, that he must register");

		PreConnect toLobby = new PreConnect(steve, proxy.servers.get("lobby-1"));
		proxy.fire(toLobby);
		check(!toLobby.result.isAllowed(), "an unregistered player cannot connect to the lobby");
		check(steve.messages.stream().anyMatch(m -> m.contains("Log in first")), "and is told why");

		invoke(proxy, steve, "login", "whatever");
		check(waitFor(() -> steve.messages.stream().anyMatch(m -> m.contains("Register first")), 5_000), "/login before registering is refused: " + steve.messages);

		invoke(proxy, steve, "register", "hunter22", "hunter22");
		check(waitFor(() -> steve.messages.stream().anyMatch(m -> m.contains("Registered and logged in")), 10_000), "/register works: " + steve.messages);
		check(waitFor(() -> steve.moves.contains("lobby-1"), 5_000), "after registering he is moved to the lobby: " + steve.moves);
		check(waitFor(() -> BridgeCheck.anyAuthState(verifier, steve, "AUTHENTICATED"), 5_000), "and the server was told, signed, that he is logged in");

		PreConnect toGame = new PreConnect(steve, proxy.servers.get("game-1"));
		proxy.fire(toGame);
		check(toGame.result.isAllowed(), "a logged in player may connect anywhere");

		// --- a returning player who types the wrong password
		proxy.fire(new Disc(steve));
		proxy.players.remove(steve.uuid);
		FakePlayer again = join(proxy, plugin, "Steve", null, false, UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(StandardCharsets.UTF_8)), out);
		check(again != null && again.uuid.equals(steve.uuid), "Steve keeps his UUID on every connection");
		check(again.current.server.name.equals("lobby-1"), "a valid IP session logs him straight in: " + again.current.server.name);
		check(again.messages.stream().anyMatch(m -> m.contains("Welcome back")), "and he is welcomed back");
		proxy.fire(new Disc(again));
		proxy.players.remove(again.uuid);

		// --- a premium player
		FakePlayer notch = join(proxy, plugin, "Notch", NOTCH, true, NOTCH, out);
		check("ONLINE".equals(out[0]), "Notch is made an online connection: " + out[0]);
		check(notch != null && notch.current.server.name.equals("lobby-1"), "Notch goes straight to the lobby");
		check(notch.messages.stream().anyMatch(m -> m.contains("original account")), "and is told he was logged in automatically: " + notch.messages);
		check(notch.uuid.equals(NOTCH), "his UUID is his Mojang UUID");

		// --- refusals
		FakePlayer bad = join(proxy, plugin, "ab", null, false, UUID.randomUUID(), out);
		check(bad == null && out[0].equals("DENIED") && out[1].contains("not allowed"), "a bad name is refused with a text: " + out[0] + " / " + out[1]);

		// --- the bridge channel
		FakePlayer client = steve;
		Pm fromClient = new Pm(client, com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.create("authenticator", "bridge"), "{}".getBytes(StandardCharsets.UTF_8));
		proxy.fire(fromClient);
		check(!fromClient.result.isAllowed(), "a message from a player on the bridge channel is never forwarded");
		Pm other = new Pm(client, com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.create("other", "thing"), "{}".getBytes(StandardCharsets.UTF_8));
		proxy.fire(other);
		check(other.result.isAllowed(), "another plugin's channel is left alone");

		com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
		payload.addProperty("uuid", notch.uuid.toString());
		payload.addProperty("change", "REMOVED");
		byte[] signed = verifier.sign(Envelope.create(MessageTypes.ACCOUNT_CHANGED, "paper-auth-1", payload, Clock.systemUTC())).getBytes(StandardCharsets.UTF_8);

		exec(schema, "DELETE FROM auth_accounts WHERE username = 'Notch'");
		Pm forged = new Pm(notch, com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.create("authenticator", "bridge"), signed);
		proxy.fire(forged);
		Thread.sleep(500);
		check(!forged.result.isAllowed(), "even a validly signed message is not forwarded when a client sent it");
		check(notch.messages.stream().noneMatch(m -> m.contains("Register:")) && !notch.moves.contains("auth-1"), "and it has no effect: the proxy did not look at the player because of it: " + notch.messages + " " + notch.moves);

		Pm fromServer = new Pm(notch.current, com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.create("authenticator", "bridge"), signed);
		proxy.fire(fromServer);
		check(!fromServer.result.isAllowed(), "a message from a server is consumed, not forwarded");
		check(waitFor(() -> notch.messages.stream().anyMatch(m -> m.contains("Register:")) , 5_000), "a signed ACCOUNT_CHANGED from a server makes the proxy notice the removed account at once: " + notch.messages);
		check(waitFor(() -> notch.moves.contains("auth-1"), 5_000), "and the player is sent back to the waiting server: " + notch.moves);

		// --- login timeout
		exec(schema, "INSERT INTO auth_premium_cache (username_lower, premium_uuid, source, checked_at) VALUES ('slowpoke', NULL, 'sim', NOW(3))");
		FakePlayer slow = join(proxy, plugin, "Slowpoke", null, false, UUID.randomUUID(), out);
		Runnable tick = proxy.tasks.get(0).runnable;
		tick.run();
		check(slow != null && slow.kicked == null, "a player who still has time is not kicked");
		Thread.sleep(5_500);
		tick.run();
		check(slow.kicked != null && slow.kicked.contains("did not log in in time"), "a player who ran out of time is kicked: " + slow.kicked);

		// --- disconnect closes the session exactly once
		int before = runtimeCount(schema);
		proxy.fire(new Disc(notch));
		proxy.fire(new Disc(notch));
		check(runtimeCount(schema) <= before, "disconnecting twice is harmless");

		runtime.close();
		check(proxy.tasks.stream().allMatch(t -> t.cancelled), "closing cancels every scheduled task");
		check(!proxy.channels.contains("authenticator:bridge"), "and unregisters the bridge channel");
	}

	static int runtimeCount(TestMariaDb.Schema schema) throws Exception {
		return schema.database().query(c -> { try (var st = c.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM auth_presence")) { rs.next(); return rs.getInt(1); } });
	}

	static void invoke(FakeProxy proxy, FakePlayer player, String command, String... args) {
		proxy.commands.get(command).execute(new SimpleCommand.Invocation() {
			@Override public CommandSource source() { return player; }
			@Override public String[] arguments() { return args; }
			@Override public String alias() { return command; }
		});
	}

	static void shadow(TestMariaDb.Server server, TestMariaDb.Schema schema, Path dir) throws Exception {
		System.out.println("== shadow mode");
		Files.writeString(dir.resolve("config.yml"), config(server, schema.name(), true, "database-only", 60));
		exec(schema, "DELETE FROM auth_presence");

		FakeProxy proxy = new FakeProxy();
		Object plugin = new Object();
		VelocityRuntime runtime = VelocityRuntime.start(plugin, proxy, slf4j(), dir);

		check(proxy.commands.keySet().equals(Set.of("authadmin")), "only /authadmin exists in shadow mode: " + proxy.commands.keySet());
		check(proxy.channels.isEmpty(), "no bridge channel in shadow mode");
		check(proxy.tasks.isEmpty(), "no scheduled task in shadow mode");

		// another plugin (mineLogin) decides ONLINE for a name we know to be cracked ('steve' is cached as cracked)
		Pre pre = new Pre("Steve", null);
		pre.result = PreLoginEvent.PreLoginComponentResult.forceOnlineMode();
		proxy.fire(pre);
		check(pre.result.isOnlineModeAllowed(), "shadow mode left the other plugin's result alone: " + pre.result);

		Pre agree = new Pre("Steve", null);
		agree.result = PreLoginEvent.PreLoginComponentResult.forceOfflineMode();
		proxy.fire(agree);

		Pre undecided = new Pre("Steve", null);
		proxy.fire(undecided);

		Thread.sleep(300);
		var stats = runtimeStats(runtime, proxy);
		System.out.println("      " + stats);
		check(stats.contains("1 agree, 1 differ, 1 not comparable"), "the comparison counted one of each: " + stats);

		Profile profile = new Profile("Steve", new GameProfile(UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(StandardCharsets.UTF_8)), "Steve"), false);
		proxy.fire(profile);
		check(profile.profile == profile.original, "shadow mode never changes a profile");

		check(runtimeCount(schema) == 0, "and wrote no login state");
		runtime.close();
	}

	static String runtimeStats(VelocityRuntime runtime, FakeProxy proxy) {
		StringBuilder out = new StringBuilder();
		FakePlayer admin = new FakePlayer(proxy, "Admin", UUID.randomUUID(), true);
		proxy.commands.get("authadmin").execute(new SimpleCommand.Invocation() {
			@Override public CommandSource source() { return new CommandSource() {
				@Override public boolean hasPermission(String p) { return true; }
				@Override public void sendMessage(Component m) { out.append(text(m)).append('\n'); }
			}; }
			@Override public String[] arguments() { return new String[] { "status" }; }
			@Override public String alias() { return "authadmin"; }
		});
		return out.toString().lines().filter(l -> l.startsWith("Shadow comparison")).findFirst().orElse(out.toString());
	}

	/** Reads what the proxy sent to a server. */
	static final class BridgeCheck {
		static boolean isAuthState(EnvelopeVerifier verifier, byte[] data, String state) {
			Optional<Envelope> e = verifier.accept(new String(data, StandardCharsets.UTF_8), "sim");
			return e.isPresent() && e.get().type().equals(MessageTypes.AUTH_STATE) && e.get().payload().get("state").getAsString().equals(state);
		}
		static boolean anyAuthState(EnvelopeVerifier verifier, FakePlayer player, String state) {
			// the player may have moved: look at every message the connection he is on, or was on, got
			for (byte[] d : player.sentAll) if (isAuthState(verifier, d, state)) return true;
			return false;
		}
	}
}
