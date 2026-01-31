package org.xross

import kotlinx.serialization.Serializable


@Serializable
data class XrossClass(
    val package_name: String, // JSONのキー名に合わせる
    val struct_name: String,
    val methods: List<XrossMethod>
)
