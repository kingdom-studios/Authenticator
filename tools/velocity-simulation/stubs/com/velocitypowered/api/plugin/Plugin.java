// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.plugin;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
public @interface Plugin { String id(); String name() default ""; String version() default ""; String description() default ""; String url() default ""; String[] authors() default {}; }
