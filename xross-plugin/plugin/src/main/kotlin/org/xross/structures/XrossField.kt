package org.xross.structures

import kotlinx.serialization.Serializable

/**
 * Metadata for a field in a struct or enum variant.
 */
@Serializable
data class XrossField(
    val name: String,
    val ty: XrossType,
    val safety: XrossThreadSafety,
    val docs: List<String> = emptyList(),
)
