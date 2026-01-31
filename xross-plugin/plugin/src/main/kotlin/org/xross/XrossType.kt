package org.xross

import kotlinx.serialization.Serializable

@Serializable
enum class XrossType { Pointer, I32, I64, F32, F64, Void }
