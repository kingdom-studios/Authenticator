package pl.kingdomcraft.authenticator.paper.gate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import io.papermc.paper.event.player.AsyncChatEvent;
import pl.kingdomcraft.authenticator.common.gate.Gate;

/**
 * Everything a player who has not logged in must not be able to do, and the one place that says so. A player is frozen until the {@link Gate}
 * says they have logged in; a player the gate has never heard of is frozen too, so a server that is still starting, or whose database is
 * down, is closed.
 *
 * <p>Most rules are one line in the table below: an event, and who it is done by. Each is registered at the first and at the last priority,
 * so that other plugins neither act on what a frozen player did (they see it cancelled) nor undo the cancellation. Movement, teleports and
 * the command list need more than a cancel and are handled below the table.
 *
 * <p>Nothing here waits for the database or for a tick: the decision is one map lookup.
 */
public final class Gatekeeper implements Listener {

	/** Tells a frozen player that they have to log in. Called at most as often as the settings allow. */
	@FunctionalInterface
	public interface Reminder {
		void remind(Player player);
	}

	/** An event that a frozen player may not cause, and how to find out who caused it. */
	private record Rule<E extends Event & Cancellable>(Class<E> type, Function<? super E, ? extends Entity> who) {

		boolean applies(Event event) {
			return this.type.isInstance(event);
		}

		Entity whoDid(Event event) {
			return this.who.apply(this.type.cast(event));
		}
	}

	private final Gate gate;
	private final Reminder reminder;
	private final Predicate<Player> ownTeleport;
	private final List<Rule<?>> rules = new ArrayList<>();

	/**
	 * @param ownTeleport whether a teleport of this player is one this plugin makes (to the login spawn) and must be let through
	 */
	public Gatekeeper(Gate gate, Reminder reminder, Predicate<Player> ownTeleport) {
		this.gate = gate;
		this.reminder = reminder;
		this.ownTeleport = ownTeleport;

		this.defineRules();
	}

	private <E extends Event & Cancellable> void rule(Class<E> type, Function<? super E, ? extends Entity> who) {
		this.rules.add(new Rule<>(type, who));
	}

	private void defineRules() {
		// chat and commands
		this.rule(AsyncChatEvent.class, AsyncChatEvent::getPlayer);
		this.rule(AsyncPlayerChatEvent.class, AsyncPlayerChatEvent::getPlayer);
		this.rule(PlayerCommandPreprocessEvent.class, PlayerCommandPreprocessEvent::getPlayer);

		// using the world
		this.rule(PlayerInteractEvent.class, PlayerInteractEvent::getPlayer);
		this.rule(PlayerInteractEntityEvent.class, PlayerInteractEntityEvent::getPlayer);
		this.rule(PlayerInteractAtEntityEvent.class, PlayerInteractAtEntityEvent::getPlayer);
		this.rule(PlayerArmorStandManipulateEvent.class, PlayerArmorStandManipulateEvent::getPlayer);
		this.rule(PlayerBucketEmptyEvent.class, PlayerBucketEmptyEvent::getPlayer);
		this.rule(PlayerBucketFillEvent.class, PlayerBucketFillEvent::getPlayer);
		this.rule(PlayerBucketEntityEvent.class, PlayerBucketEntityEvent::getPlayer);
		this.rule(PlayerFishEvent.class, PlayerFishEvent::getPlayer);
		this.rule(PlayerBedEnterEvent.class, PlayerBedEnterEvent::getPlayer);
		this.rule(PlayerEditBookEvent.class, PlayerEditBookEvent::getPlayer);
		this.rule(PlayerShearEntityEvent.class, PlayerShearEntityEvent::getPlayer);
		this.rule(PlayerTakeLecternBookEvent.class, PlayerTakeLecternBookEvent::getPlayer);
		this.rule(PlayerAnimationEvent.class, PlayerAnimationEvent::getPlayer);
		this.rule(HangingPlaceEvent.class, HangingPlaceEvent::getPlayer);
		this.rule(HangingBreakByEntityEvent.class, HangingBreakByEntityEvent::getRemover);
		this.rule(VehicleEnterEvent.class, VehicleEnterEvent::getEntered);
		this.rule(EntityMountEvent.class, EntityMountEvent::getEntity);

		// blocks
		this.rule(BlockBreakEvent.class, BlockBreakEvent::getPlayer);
		this.rule(BlockPlaceEvent.class, BlockPlaceEvent::getPlayer);
		this.rule(BlockMultiPlaceEvent.class, BlockMultiPlaceEvent::getPlayer);
		this.rule(BlockDamageEvent.class, BlockDamageEvent::getPlayer);
		this.rule(SignChangeEvent.class, SignChangeEvent::getPlayer);

		// items and inventories
		this.rule(InventoryOpenEvent.class, InventoryOpenEvent::getPlayer);
		this.rule(InventoryClickEvent.class, InventoryClickEvent::getWhoClicked);
		this.rule(InventoryCreativeEvent.class, InventoryCreativeEvent::getWhoClicked);
		this.rule(CraftItemEvent.class, CraftItemEvent::getWhoClicked);
		this.rule(InventoryDragEvent.class, InventoryDragEvent::getWhoClicked);
		this.rule(PlayerDropItemEvent.class, PlayerDropItemEvent::getPlayer);
		this.rule(PlayerSwapHandItemsEvent.class, PlayerSwapHandItemsEvent::getPlayer);
		this.rule(PlayerItemHeldEvent.class, PlayerItemHeldEvent::getPlayer);
		this.rule(PlayerItemConsumeEvent.class, PlayerItemConsumeEvent::getPlayer);
		this.rule(PlayerAttemptPickupItemEvent.class, PlayerAttemptPickupItemEvent::getPlayer);
		this.rule(EntityPickupItemEvent.class, EntityPickupItemEvent::getEntity);

		// damage, hunger and being targeted
		this.rule(EntityDamageEvent.class, EntityDamageEvent::getEntity);
		this.rule(EntityDamageByEntityEvent.class, EntityDamageByEntityEvent::getEntity);
		this.rule(EntityDamageByEntityEvent.class, event -> attacker(event.getDamager()));
		this.rule(FoodLevelChangeEvent.class, FoodLevelChangeEvent::getEntity);
		this.rule(EntityTargetEvent.class, EntityTargetEvent::getTarget);
		this.rule(EntityShootBowEvent.class, EntityShootBowEvent::getEntity);
		this.rule(ProjectileLaunchEvent.class, event -> attacker(event.getEntity()));

		// leaving the world
		this.rule(PlayerToggleFlightEvent.class, PlayerToggleFlightEvent::getPlayer);
		this.rule(PlayerPortalEvent.class, PlayerPortalEvent::getPlayer);
		this.rule(EntityPortalEvent.class, EntityPortalEvent::getEntity);
	}

	/** Who is responsible for a hit or a shot: the shooter of a projectile, otherwise the entity itself. */
	private static Entity attacker(Entity damager) {
		if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter)
			return shooter;

		return damager;
	}

	/** Whether this player has not logged in. */
	public boolean frozen(Entity entity) {
		return entity instanceof Player player && !this.gate.authenticated(player.getUniqueId());
	}

	/**
	 * Runs every rule that fits the event: if a frozen player caused it, it is cancelled and the player reminded.
	 *
	 * @return whether it was cancelled
	 */
	public boolean dispatch(Event event) {
		boolean cancelled = false;

		for (final Rule<?> rule : this.rules)
			if (rule.applies(event))
				cancelled |= this.apply(rule, event);

		return cancelled;
	}

	private <E extends Event & Cancellable> boolean apply(Rule<E> rule, Event event) {
		final Entity who = rule.whoDid(event);

		if (!this.frozen(who))
			return false;

		((Cancellable) event).setCancelled(true);
		this.reminder.remind((Player) who);

		return true;
	}

	/** Registers every rule of the table, at the first and at the last priority. */
	public void register(Plugin plugin) {
		final PluginManager manager = plugin.getServer().getPluginManager();

		for (final Rule<?> rule : this.rules)
			for (final EventPriority priority : new EventPriority[] { EventPriority.LOWEST, EventPriority.HIGHEST })
				manager.registerEvent(rule.type(), this, priority, (listener, event) -> {
					if (rule.applies(event))
						this.apply(rule, event);
				}, plugin, false);

		manager.registerEvents(this, plugin);
	}

	/** How many events the table covers; the tests insist on the list. */
	public List<Class<? extends Event>> covered() {
		final List<Class<? extends Event>> types = new ArrayList<>();

		for (final Rule<?> rule : this.rules)
			types.add(rule.type());

		types.add(PlayerMoveEvent.class);
		types.add(PlayerTeleportEvent.class);
		types.add(PlayerCommandSendEvent.class);

		return types;
	}

	// ---- what a cancel is not enough for ----

	/**
	 * Movement: the player may turn their head and nothing else. Only the position is put back, so there is no teleport every tick and the
	 * camera stays smooth.
	 */
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
	public void onMove(PlayerMoveEvent event) {
		final Player player = event.getPlayer();

		if (!this.frozen(player))
			return;

		final Location held = held(event.getFrom(), event.getTo());

		if (held != null) {
			event.setTo(held);
			this.reminder.remind(player);
		}
	}

	/**
	 * @return where to put the player back, with the rotation they asked for; null if they only turned (or did not move)
	 */
	static Location held(Location from, Location to) {
		if (to == null)
			return null;

		if (from.getWorld() == to.getWorld() && from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ())
			return null;

		final Location back = from.clone();

		back.setYaw(to.getYaw());
		back.setPitch(to.getPitch());

		return back;
	}

	/** Any teleport but the ones this plugin makes (to the login spawn) is refused: an ender pearl, a command of another plugin. */
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
	public void onTeleport(PlayerTeleportEvent event) {
		final Player player = event.getPlayer();

		if (this.frozen(player) && !this.ownTeleport.test(player)) {
			event.setCancelled(true);
			this.reminder.remind(player);
		}
	}

	/** A frozen player is told of no command at all, so there is nothing to suggest. */
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
	public void onCommandList(PlayerCommandSendEvent event) {
		if (this.frozen(event.getPlayer()))
			event.getCommands().clear();
	}
}
