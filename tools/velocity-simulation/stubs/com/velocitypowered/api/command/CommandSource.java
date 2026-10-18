// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.command;
import net.kyori.adventure.audience.Audience;
public interface CommandSource extends Audience { boolean hasPermission(String permission); }
