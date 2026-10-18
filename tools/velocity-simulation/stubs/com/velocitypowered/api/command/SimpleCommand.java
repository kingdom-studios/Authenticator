// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.command;
import java.util.List;
public interface SimpleCommand extends Command {
	interface Invocation { CommandSource source(); String[] arguments(); String alias(); }
	void execute(Invocation invocation);
	default List<String> suggest(Invocation invocation) { return List.of(); }
	default boolean hasPermission(Invocation invocation) { return true; }
}
