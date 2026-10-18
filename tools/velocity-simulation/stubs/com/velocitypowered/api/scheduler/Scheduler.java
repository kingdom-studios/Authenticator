// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.scheduler;
import java.util.concurrent.TimeUnit;
public interface Scheduler {
	TaskBuilder buildTask(Object plugin, Runnable runnable);
	interface TaskBuilder {
		TaskBuilder delay(long time, TimeUnit unit);
		TaskBuilder repeat(long time, TimeUnit unit);
		ScheduledTask schedule();
	}
}
