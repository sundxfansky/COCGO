package com.coc.suncode.jar.code.universal.buildings.walls

import com.coc.suncode.jar.code.colorschema.MyColors
import com.coc.suncode.jar.code.universal.buildings.BaseType
import com.coc.suncode.jar.code.universal.colors.findMultiColors
import kotlin.math.roundToInt

// Shared by attack, donation and upgrade plugins.
data class ResourcesPercentage(
    val gold: Int = 0, val elixir: Int = 0
)

// 100% resource bar position (x coordinate) and 0% position
private const val RESOURCE_FULL_X = 1012
private const val RESOURCE_EMPTY_X = 1261

/**
 * Calculate the resource percentage based on the x coordinate of the resource bar.
 * Linear interpolation: x=1012 -> 100%, x=1268 -> 0%.
 */
private fun calculatePercentage(x: Int): Int {
    return ((RESOURCE_EMPTY_X - x) * 100.0 / (RESOURCE_EMPTY_X - RESOURCE_FULL_X)).roundToInt().coerceIn(0, 100)
}

suspend fun calculateResourcesPercentage(currentBase: BaseType): ResourcesPercentage {
    val goldSchema = if (currentBase == BaseType.Builder) MyColors.BuilderBaseGold else MyColors.GoldColor
    val elixirSchema = if (currentBase == BaseType.Builder) MyColors.BuilderBaseExiler else MyColors.ElixirColor

    // Calculate gold percentage
    val goldPosition = findMultiColors(schema = goldSchema)
    val goldPercent = if (goldPosition != null) calculatePercentage(goldPosition.x) else 0

    // Calculate elixir percentage
    val elixirPosition = findMultiColors(schema = elixirSchema)
    val elixirPercent = if (elixirPosition != null) calculatePercentage(elixirPosition.x) else 0

    return ResourcesPercentage(gold = goldPercent, elixir = elixirPercent)
}
