package com.coc.suncode.core.data.village

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.Strictness

/** Converts an in-game export into the display fields shared by both settings pages. */
internal object CocVillageParser {
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()
    private val playerTag = Regex("#[A-Za-z0-9]{2,16}")
    private val groups = listOf(
        "buildings", "traps", "units", "siege_machines", "heroes", "spells", "pets",
        "equipment", "helpers", "guardians", "decos", "obstacles", "house_parts", "skins",
        "sceneries", "buildings2", "traps2", "units2", "heroes2", "decos2", "obstacles2",
        "skins2", "sceneries2"
    )
    private val boostKeys = listOf(
        "builder_boost", "builder_consumable", "lab_boost", "lab_consumable",
        "pet_boost", "clocktower_boost", "clocktower_cooldown"
    )
    const val MAX_BYTES = 1_000_000
    private const val MAX_ROWS = 4_000
    private const val MAX_SECONDS = 316_224_000L

    fun parse(raw: String): JsonObject {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "游戏 JSON 超过 1 MB" }
        val root = try {
            gson.fromJson(raw, JsonObject::class.java)
        } catch (_: JsonParseException) {
            null
        } ?: throw IllegalArgumentException("游戏 JSON 格式无效")
        val rows = JsonArray()
        fun add(row: JsonObject) {
            require(rows.size() < MAX_ROWS) { "游戏 JSON 条目过多" }
            rows.add(row)
        }

        val townHall = root.array("buildings").firstNotNullOfOrNull { hallLevel(it, 1000001) }
        val builderHall = root.array("buildings2").firstNotNullOfOrNull { hallLevel(it, 1000034) }
        require(townHall != null || builderHall != null) { "游戏 JSON 缺少有效的大本营或建筑大师大本营数据" }

        for (group in groups) {
            for (value in root.array(group)) {
                val row = item(group, value) ?: continue
                add(row)
                if (group != "buildings" && group != "buildings2") continue
                // Crafting stations export defense types and their modules inside the parent building.
                val building = row.get("id").asInt
                for (variant in value.asJsonObject.array("types")) {
                    val defense = item(group, variant, parent = building) ?: continue
                    add(defense)
                    for (module in variant.asJsonObject.array("modules")) {
                        item(group, module, parent = defense.get("id").asInt)?.let(::add)
                    }
                }
            }
        }

        return JsonObject().apply {
            root.get("tag")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString?.trim()?.takeIf(playerTag::matches)?.let { addProperty("tag", it) }
            // Some regional exports omit the timestamp. Keep their details and raw timer durations.
            root.get("timestamp").integer(1_420_070_400L..4_102_444_800L)?.let { addProperty("timestamp", it) }
            townHall?.let { addProperty("townHall", it) }
            builderHall?.let { addProperty("builderHall", it) }
            add("items", rows)
            root.get("boosts")?.takeIf { it.isJsonObject }?.asJsonObject?.let { boosts ->
                val durations = JsonObject()
                for (key in boostKeys) boosts.get(key).integer(1L..MAX_SECONDS)?.let { durations.addProperty(key, it) }
                if (durations.size() > 0) add("boosts", durations)
            }
        }
    }

    private fun item(group: String, value: JsonElement, parent: Int? = null): JsonObject? {
        val fields = value.takeIf { it.isJsonObject }?.asJsonObject
        val id = (fields?.get("data") ?: value).integer(1L..Int.MAX_VALUE.toLong()) ?: return null
        return JsonObject().apply {
            addProperty("group", group)
            addProperty("id", id)
            addProperty("count", fields?.get("cnt").integer(1L..10_000L) ?: 1L)
            fields?.get("lvl").integer(0L..1_000L)?.let { addProperty("level", it) }
            fields?.get("timer").integer(1L..MAX_SECONDS)?.let { addProperty("seconds", it) }
            fields?.get("weapon").integer(1L..100L)?.let { addProperty("weapon", it) }
            fields?.get("gear_up").integer(1L..100L)?.let { addProperty("gearUp", it) }
            fields?.get("helper_cooldown").integer(1L..MAX_SECONDS)?.let { addProperty("cooldown", it) }
            parent?.let { addProperty("parent", it) }
        }
    }

    private fun hallLevel(value: JsonElement, id: Long): Long? {
        val item = value.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (item.get("data").integer(id..id) == null) return null
        return item.get("lvl").integer(1L..100L)
    }

    private fun JsonObject.array(key: String): Iterable<JsonElement> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: emptyList()

    // Numeric strings are present in some exports. Ignore invalid values without dropping adjacent entries.
    private fun JsonElement?.integer(range: LongRange): Long? =
        this?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull()?.takeIf { it in range }
}
