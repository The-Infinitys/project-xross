package org.xross.structures

import kotlinx.serialization.Serializable

/**
 * Represents the receiver type of a Rust method and how it maps to Kotlin.
 */
@Serializable
enum class XrossMethodType {
    /** Static function (does not take self). Maps to a static method in Kotlin. */
    Static,

    /** Immutable reference (&self). Maps to an instance method in Kotlin. */
    ConstInstance,

    /** Mutable reference (&mut self). Maps to an instance method in Kotlin. */
    MutInstance,

    /** Consumes ownership of self. The Kotlin instance should be invalidated after calling this. */
    OwnedInstance,
}
