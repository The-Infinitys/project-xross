package org.xross.structures

import kotlinx.serialization.Serializable

/**
 * Defines how the native method handle should be invoked.
 */
@Serializable
enum class HandleMode {
    /** Standard execution. */
    Normal,

    /** Optimized for extremely short-running, non-blocking computations. Maps to Linker.Option.critical(false) in Java. */
    Critical,

    /** Can panic and should be caught to propagate as an exception to JVM. */
    Panicable,
}
