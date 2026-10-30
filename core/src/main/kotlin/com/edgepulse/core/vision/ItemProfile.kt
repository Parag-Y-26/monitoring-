package com.edgepulse.core.vision

import kotlinx.serialization.Serializable

@Serializable
data class ItemProfile(val id: String, val name: String, val characteristics: String = "", val referenceFilename: String = "",
    val classId: Int? = null, val similarityThreshold: Float = .90f, val minWidthFraction: Float = .05f, val maxWidthFraction: Float = .65f) {
    fun validate() {
        require(id.matches(Regex("[0-9a-f-]{36}")))
        require(name.isNotBlank() && name.length <= 80 && name.none(Char::isISOControl))
        require(characteristics.length <= 4000)
        require(classId == null || classId in 0..79)
        require(referenceFilename.isEmpty() || referenceFilename.matches(Regex("[0-9a-f-]{36}\\.webp\\.enc")))
        require(similarityThreshold in .5f..1f && minWidthFraction in .02f..1f && maxWidthFraction in minWidthFraction..1f)
        require(classId != null || referenceFilename.isNotEmpty())
    }
}
