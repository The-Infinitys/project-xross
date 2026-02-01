package org.xross

import kotlinx.serialization.Serializable

@Serializable
data class XrossClass(
    val packageName: String,
    val structName: String,
    val docs: List<String> = emptyList(), // 新しく追加
    val fields: List<XrossField> = emptyList(), // 新しく追加
    val methods: List<XrossMethod>
)
