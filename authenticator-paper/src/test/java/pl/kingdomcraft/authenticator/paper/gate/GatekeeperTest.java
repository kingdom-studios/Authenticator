package pl.kingdomcraft.authenticator.paper.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Projectile;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.auth.AuthMethod;
import pl.kingdomcraft.authenticator.common.auth.AuthState;
import pl.kingdomcraft.authenticator.common.gate.Gate;

/** What a player who has not logged in can and cannot do. Players and entities are stand-ins: only what the gate asks of them is real. */
class GatekeeperTest {

	private final Gate gate = new Gate();
	private final List<UUID> reminded = new ArrayList<>();
	private final Set<UUID> ownTeleports = new HashSet<>();
	private final Gatekeeper keeper = new Gatekeeper(this.gate, player -> this.reminded.add(player.getUniqueId()), player -> this.ownTeleports.contains(player.getUniqueId()));

	private static final World WORLD = (World) Proxy.newProxyInstance(GatekeeperTest.class.getClassLoader(), new Class<?>[] { World.class }, (proxy, method, args) -> switch (method.getName()) {
		case "hashCode" -> 1;
		case "equals" -> proxy == args[0];
		case "toString" -> "World";
		default -> defaultValue(method.getReturnType());
	});

	/** A server with nobody online: event constructors ask for it. */
	private static final Server SERVER = (Server) Proxy.newProxyInstance(GatekeeperTest.class.getClassLoader(), new Class<?>[] { Server.class }, (proxy, method, args) -> switch (method.getName()) {
		case "getOnlinePlayers" -> List.of();
		case "hashCode" -> 2;
		case "equals" -> proxy == args[0];
		case "toString" -> "Server";
		default -> defaultValue(method.getReturnType());
	});

	/** A player that knows only its UUID and name. */
	private static Player player(String name) {
		final UUID uuid = UUID.nameUUIDFromBytes(name.getBytes());

		final InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
			case "getUniqueId" -> uuid;
			case "getName" -> name;
			case "getServer" -> SERVER;
			case "hashCode" -> uuid.hashCode();
			case "equals" -> proxy == args[0];
			case "toString" -> "Player[" + name + "]";
			default -> defaultValue(method.getReturnType());
		};

		return (Player) Proxy.newProxyInstance(GatekeeperTest.class.getClassLoader(), new Class<?>[] { Player.class }, handler);
	}

	private static Object defaultValue(Class<?> type) {
		if (type == boolean.class)
			return false;

		if (type == int.class)
			return 0;

		if (type == long.class)
			return 0L;

		if (type == double.class)
			return 0D;

		if (type == float.class)
			return 0F;

		return null;
	}

	private Player frozen(String name) {
		return player(name); // unknown to the gate
	}

	private Player loggedIn(String name) {
		final Player player = player(name);

		this.gate.apply(player.getUniqueId(), AuthState.AUTHENTICATED, AuthMethod.PASSWORD, 1);

		return player;
	}

	private Player waiting(String name) {
		final Player player = player(name);

		this.gate.apply(player.getUniqueId(), AuthState.PENDING_LOGIN, null, 1);

		return player;
	}

	// ---- who is frozen ----

	@Test
	void everyoneIsFrozenUntilTheProxyHasLoggedThemIn() {
		assertTrue(this.keeper.frozen(this.frozen("Unknown")), "a player the gate never heard of");
		assertTrue(this.keeper.frozen(this.waiting("Waiting")));
		assertFalse(this.keeper.frozen(this.loggedIn("Steve")));
	}

	@Test
	void anythingButAPlayerIsNeverFrozen() {
		assertFalse(this.keeper.frozen(null));
		assertFalse(this.keeper.frozen((Entity) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Entity.class }, (proxy, method, args) -> defaultValue(method.getReturnType()))));
	}

	// ---- chat and commands ----

	@Test
	void chatAndCommandsOfAFrozenPlayerAreCancelled() {
		final Player steve = this.frozen("Steve");

		final AsyncPlayerChatEvent chat = new AsyncPlayerChatEvent(true, steve, "hello", new HashSet<>());
		final PlayerCommandPreprocessEvent command = new PlayerCommandPreprocessEvent(steve, "/spawn");

		assertTrue(this.keeper.dispatch(chat));
		assertTrue(chat.isCancelled());
		assertTrue(this.keeper.dispatch(command));
		assertTrue(command.isCancelled());
	}

	@Test
	void chatAndCommandsOfALoggedInPlayerAreLeftAlone() {
		final Player steve = this.loggedIn("Steve");

		final AsyncPlayerChatEvent chat = new AsyncPlayerChatEvent(true, steve, "hello", new HashSet<>());
		final PlayerCommandPreprocessEvent command = new PlayerCommandPreprocessEvent(steve, "/spawn");

		assertFalse(this.keeper.dispatch(chat));
		assertFalse(chat.isCancelled());
		assertFalse(this.keeper.dispatch(command));
		assertFalse(command.isCancelled());
		assertEquals(List.of(), this.reminded);
	}

	@Test
	void aFrozenPlayerIsToldOfNoCommandAtAll() {
		final List<String> commands = new ArrayList<>(List.of("spawn", "home", "msg"));

		this.keeper.onCommandList(new PlayerCommandSendEvent(this.waiting("Steve"), commands));

		assertEquals(List.of(), commands);

		final List<String> others = new ArrayList<>(List.of("spawn", "home"));

		this.keeper.onCommandList(new PlayerCommandSendEvent(this.loggedIn("Alex"), others));

		assertEquals(List.of("spawn", "home"), others, "and a logged in player keeps theirs");
	}

	// ---- movement ----

	private static Location at(double x, double y, double z, float yaw, float pitch) {
		return new Location(WORLD, x, y, z, yaw, pitch);
	}

	@Test
	void aFrozenPlayerCannotMoveButCanTurnTheirHead() {
		final Player steve = this.waiting("Steve");

		final PlayerMoveEvent walk = new PlayerMoveEvent(steve, at(0, 64, 0, 10, 5), at(1, 64, 0, 90, 20));

		this.keeper.onMove(walk);

		assertEquals(0, walk.getTo().getX(), "back where they were");
		assertEquals(64, walk.getTo().getY());
		assertEquals(90, walk.getTo().getYaw(), "but the camera follows the mouse");
		assertEquals(20, walk.getTo().getPitch());
		assertFalse(walk.isCancelled(), "the position is put back, there is no cancelled move to jitter the camera");
	}

	@Test
	void everyAxisCountsAsMovement() {
		final Player steve = this.waiting("Steve");

		for (final Location to : List.of(at(1, 64, 0, 0, 0), at(0, 65, 0, 0, 0), at(0, 64, 1, 0, 0), at(0, 64, -0.001, 0, 0))) {
			final PlayerMoveEvent event = new PlayerMoveEvent(steve, at(0, 64, 0, 0, 0), to);

			this.keeper.onMove(event);

			assertEquals(0, event.getTo().getX() + event.getTo().getZ() + event.getTo().getY() - 64, "moved to " + to);
		}
	}

	@Test
	void justTurningTheHeadIsLeftAloneAndCostsNoReminder() {
		final Player steve = this.waiting("Steve");
		final Location to = at(0, 64, 0, 45, 10);
		final PlayerMoveEvent turn = new PlayerMoveEvent(steve, at(0, 64, 0, 0, 0), to);

		this.keeper.onMove(turn);

		assertSame(to, turn.getTo(), "no location was replaced: no teleport");
		assertEquals(List.of(), this.reminded);
	}

	@Test
	void aLoggedInPlayerMovesFreely() {
		final PlayerMoveEvent walk = new PlayerMoveEvent(this.loggedIn("Steve"), at(0, 64, 0, 0, 0), at(5, 64, 5, 0, 0));

		this.keeper.onMove(walk);

		assertEquals(5, walk.getTo().getX());
	}

	@Test
	void heldPutsOnlyThePositionBack() {
		assertNull(Gatekeeper.held(at(1, 2, 3, 0, 0), at(1, 2, 3, 99, 99)));
		assertNull(Gatekeeper.held(at(1, 2, 3, 0, 0), null));

		final Location back = Gatekeeper.held(at(1, 2, 3, 0, 0), at(9, 9, 9, 30, 40));

		assertEquals(1, back.getX());
		assertEquals(2, back.getY());
		assertEquals(3, back.getZ());
		assertEquals(30, back.getYaw());
		assertEquals(40, back.getPitch());
	}

	@Test
	void aTeleportIsRefusedUnlessThisPluginMakesIt() {
		final Player steve = this.waiting("Steve");

		final PlayerTeleportEvent pearl = new PlayerTeleportEvent(steve, at(0, 64, 0, 0, 0), at(50, 64, 50, 0, 0), PlayerTeleportEvent.TeleportCause.ENDER_PEARL);

		this.keeper.onTeleport(pearl);

		assertTrue(pearl.isCancelled());

		this.ownTeleports.add(steve.getUniqueId());

		final PlayerTeleportEvent spawn = new PlayerTeleportEvent(steve, at(0, 64, 0, 0, 0), at(0, 70, 0, 0, 0), PlayerTeleportEvent.TeleportCause.PLUGIN);

		this.keeper.onTeleport(spawn);

		assertFalse(spawn.isCancelled(), "to the login spawn");

		final PlayerTeleportEvent free = new PlayerTeleportEvent(this.loggedIn("Alex"), at(0, 64, 0, 0, 0), at(50, 64, 50, 0, 0), PlayerTeleportEvent.TeleportCause.COMMAND);

		this.keeper.onTeleport(free);

		assertFalse(free.isCancelled());
	}

	// ---- hunger, items ----

	@Test
	void hungerAndHandSwapsAreStoppedForAFrozenPlayerOnly() {
		final Player steve = this.waiting("Steve");
		final Player alex = this.loggedIn("Alex");

		final FoodLevelChangeEvent hungry = new FoodLevelChangeEvent(steve, 10);
		final PlayerSwapHandItemsEvent swap = new PlayerSwapHandItemsEvent(steve, null, null);

		assertTrue(this.keeper.dispatch(hungry));
		assertTrue(hungry.isCancelled());
		assertTrue(this.keeper.dispatch(swap));
		assertTrue(swap.isCancelled());

		final FoodLevelChangeEvent fine = new FoodLevelChangeEvent(alex, 10);

		assertFalse(this.keeper.dispatch(fine));
		assertFalse(fine.isCancelled());
	}

	// ---- the world, items, inventories, damage ----

	private static <T> T stand(Class<T> type) {
		return type.cast(Proxy.newProxyInstance(GatekeeperTest.class.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> switch (method.getName()) {
			case "hashCode" -> System.identityHashCode(proxy);
			case "equals" -> proxy == args[0];
			case "toString" -> type.getSimpleName();
			default -> defaultValue(method.getReturnType());
		}));
	}

	@Test
	void blocksAreNotBrokenOrPlacedByAFrozenPlayer() {
		final Player steve = this.waiting("Steve");

		final BlockBreakEvent breaking = new BlockBreakEvent(stand(Block.class), steve);
		final BlockDamageEvent digging = new BlockDamageEvent(steve, stand(Block.class), null, null, false);

		assertTrue(this.keeper.dispatch(breaking));
		assertTrue(breaking.isCancelled());
		assertTrue(this.keeper.dispatch(digging));
		assertTrue(digging.isCancelled());

		final BlockBreakEvent allowed = new BlockBreakEvent(stand(Block.class), this.loggedIn("Alex"));

		assertFalse(this.keeper.dispatch(allowed));
		assertFalse(allowed.isCancelled());
	}

	@Test
	void interactionsAndDropsAreStopped() {
		final Player steve = this.waiting("Steve");

		final PlayerInteractEvent click = new PlayerInteractEvent(steve, Action.RIGHT_CLICK_BLOCK, null, stand(Block.class), BlockFace.UP);
		final PlayerDropItemEvent drop = new PlayerDropItemEvent(steve, stand(Item.class));
		final PlayerItemHeldEvent scroll = new PlayerItemHeldEvent(steve, 0, 1);

		assertTrue(this.keeper.dispatch(click));
		assertTrue(click.useInteractedBlock() == Event.Result.DENY || click.isCancelled());
		assertTrue(this.keeper.dispatch(drop));
		assertTrue(drop.isCancelled());
		assertTrue(this.keeper.dispatch(scroll));
		assertTrue(scroll.isCancelled());
	}

	@Test
	void aFrozenPlayerNeitherTakesNorDealsDamage() {
		final Player steve = this.waiting("Steve");
		final Player alex = this.loggedIn("Alex");

		final EntityDamageEvent fall = new EntityDamageEvent(steve, EntityDamageEvent.DamageCause.FALL, null, 5);

		assertTrue(this.keeper.dispatch(fall));
		assertTrue(fall.isCancelled());

		final EntityDamageByEntityEvent hit = new EntityDamageByEntityEvent(steve, alex, EntityDamageEvent.DamageCause.ENTITY_ATTACK, null, 2);

		assertTrue(this.keeper.dispatch(hit), "a frozen player cannot be hit");
		assertTrue(hit.isCancelled());

		final EntityDamageByEntityEvent swing = new EntityDamageByEntityEvent(alex, stand(Entity.class), EntityDamageEvent.DamageCause.ENTITY_ATTACK, null, 2);

		assertFalse(this.keeper.dispatch(swing), "a logged in player hitting a mob is fine");

		final EntityDamageByEntityEvent punch = new EntityDamageByEntityEvent(steve, stand(Entity.class), EntityDamageEvent.DamageCause.ENTITY_ATTACK, null, 2);

		assertTrue(this.keeper.dispatch(punch), "and a frozen player cannot hit anything");
		assertTrue(punch.isCancelled());
	}

	@Test
	void anArrowAFrozenPlayerShotCountsAsTheirs() {
		final Player steve = this.waiting("Steve");
		final Projectile arrow = (Projectile) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Projectile.class }, (proxy, method, args) -> switch (method.getName()) {
			case "getShooter" -> steve;
			case "hashCode" -> 7;
			case "equals" -> proxy == args[0];
			default -> defaultValue(method.getReturnType());
		});

		final EntityDamageByEntityEvent hit = new EntityDamageByEntityEvent(arrow, stand(Entity.class), EntityDamageEvent.DamageCause.PROJECTILE, null, 2);

		assertTrue(this.keeper.dispatch(hit));
		assertTrue(hit.isCancelled());
	}

	// ---- reminders ----

	@Test
	void aFrozenPlayerIsRemindedWhenTheyTrySomething() {
		final Player steve = this.waiting("Steve");

		this.keeper.dispatch(new PlayerCommandPreprocessEvent(steve, "/spawn"));

		assertEquals(List.of(steve.getUniqueId()), this.reminded);
	}

	// ---- coverage ----

	/** The events a frozen player must not get through. If one of these is dropped from the table the build fails. */
	private static final List<String> REQUIRED = List.of(
			// chat and commands
			"io.papermc.paper.event.player.AsyncChatEvent", "org.bukkit.event.player.AsyncPlayerChatEvent", "org.bukkit.event.player.PlayerCommandPreprocessEvent",
			"org.bukkit.event.player.PlayerCommandSendEvent",
			// movement
			"org.bukkit.event.player.PlayerMoveEvent", "org.bukkit.event.player.PlayerTeleportEvent", "org.bukkit.event.player.PlayerPortalEvent", "org.bukkit.event.player.PlayerToggleFlightEvent",
			// interactions
			"org.bukkit.event.player.PlayerInteractEvent", "org.bukkit.event.player.PlayerInteractEntityEvent", "org.bukkit.event.player.PlayerInteractAtEntityEvent",
			"org.bukkit.event.player.PlayerArmorStandManipulateEvent", "org.bukkit.event.player.PlayerBucketEmptyEvent", "org.bukkit.event.player.PlayerBucketFillEvent",
			"org.bukkit.event.player.PlayerFishEvent", "org.bukkit.event.player.PlayerBedEnterEvent", "org.bukkit.event.player.PlayerEditBookEvent", "org.bukkit.event.player.PlayerShearEntityEvent",
			// blocks
			"org.bukkit.event.block.BlockBreakEvent", "org.bukkit.event.block.BlockPlaceEvent", "org.bukkit.event.block.BlockDamageEvent", "org.bukkit.event.block.SignChangeEvent",
			// inventories and items
			"org.bukkit.event.inventory.InventoryOpenEvent", "org.bukkit.event.inventory.InventoryClickEvent", "org.bukkit.event.inventory.InventoryDragEvent",
			"org.bukkit.event.inventory.InventoryCreativeEvent", "org.bukkit.event.player.PlayerDropItemEvent", "org.bukkit.event.player.PlayerSwapHandItemsEvent",
			"org.bukkit.event.player.PlayerAttemptPickupItemEvent", "org.bukkit.event.entity.EntityPickupItemEvent", "org.bukkit.event.player.PlayerItemConsumeEvent",
			// damage and hunger
			"org.bukkit.event.entity.EntityDamageEvent", "org.bukkit.event.entity.EntityDamageByEntityEvent", "org.bukkit.event.entity.FoodLevelChangeEvent",
			"org.bukkit.event.entity.EntityTargetEvent");

	@Test
	void everyEventAFrozenPlayerMustNotCauseIsCovered() {
		final Set<String> covered = new HashSet<>();

		for (final Class<? extends Event> type : this.keeper.covered())
			covered.add(type.getName());

		for (final String required : REQUIRED)
			assertTrue(covered.contains(required), required + " is not covered by the gate");
	}

	@Test
	void everyCoveredEventThatCanBeCancelledIsCancelledNotJustWatched() {
		for (final Class<? extends Event> type : this.keeper.covered())
			if (!type.getName().endsWith("PlayerCommandSendEvent") && !type.getName().endsWith("PlayerMoveEvent"))
				assertTrue(Cancellable.class.isAssignableFrom(type), type.getName() + " cannot be cancelled, so the table cannot stop it");
	}

	@Test
	void theHandlersThatBlockHaveTheLastWord() {
		for (final Method method : Gatekeeper.class.getDeclaredMethods()) {
			final EventHandler handler = method.getAnnotation(EventHandler.class);

			if (handler == null)
				continue;

			assertEquals(EventPriority.HIGHEST, handler.priority(), method.getName() + ": a plugin that runs later could undo it");
			assertFalse(handler.ignoreCancelled(), method.getName() + ": it must also act on events somebody else already cancelled");
			assertTrue(Modifier.isPublic(method.getModifiers()), method.getName() + " must be public for the event system");
		}
	}

	@Test
	void theLoginSpawnTeleportFlagIsTheOnlyWayThroughForAFrozenPlayer() {
		final AtomicBoolean asked = new AtomicBoolean();
		final Gatekeeper strict = new Gatekeeper(this.gate, player -> {
		}, player -> {
			asked.set(true);

			return false;
		});

		final Player steve = this.frozen("Steve");
		final PlayerTeleportEvent teleport = new PlayerTeleportEvent(steve, at(0, 0, 0, 0, 0), at(1, 1, 1, 0, 0), PlayerTeleportEvent.TeleportCause.PLUGIN);

		strict.onTeleport(teleport);

		assertTrue(asked.get());
		assertTrue(teleport.isCancelled(), "even a teleport by another plugin");
	}
}
