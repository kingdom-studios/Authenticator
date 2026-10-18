// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.proxy;
import java.util.concurrent.CompletableFuture;
public interface ConnectionRequestBuilder {
	CompletableFuture<Result> connect();
	interface Result { boolean isSuccessful(); }
}
