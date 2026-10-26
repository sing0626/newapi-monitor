package com.newapimonitor.data.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Values are server-published catalog values, not costs inferred from usage logs. No currency is assumed. */
data class OfficialModelPrice(
    val name: String,
    val modelRatio: Double?,
    val completionRatio: Double?,
    val fixedPrice: Double?,
)

data class OfficialGroup(val id: String, val label: String, val ratio: Double?)

data class OfficialCatalog(val models: List<OfficialModelPrice>, val groups: List<OfficialGroup>)

/** NewAPI versions vary: the public pricing endpoint may return a direct object or a data envelope. */
object PricingParsing {
    fun catalog(raw: JsonElement): OfficialCatalog {
        val root = unwrap(raw)
        val obj = root as? JsonObject
        val ratioByModel = ratioMap(obj?.get("model_ratio") ?: obj?.get("modelRatio"))
        val completionByModel = ratioMap(obj?.get("completion_ratio") ?: obj?.get("completionRatio"))
        val fixedByModel = ratioMap(obj?.get("model_price") ?: obj?.get("modelPrice"))
        val individualModels = when (val value = obj?.get("models") ?: obj?.get("model_prices")) {
            is JsonArray -> value.mapNotNull { model(it as? JsonObject) }
            is JsonObject -> value.entries.mapNotNull { (name, entry) ->
                model(entry as? JsonObject, name) ?: number(entry)?.let { OfficialModelPrice(name, null, null, it) }
            }
            else -> if (root is JsonArray) root.mapNotNull { model(it as? JsonObject) } else emptyList()
        }
        val ratioModels = (ratioByModel.keys + completionByModel.keys + fixedByModel.keys).map { name ->
            OfficialModelPrice(name, ratioByModel[name], completionByModel[name], fixedByModel[name])
        }
        val models = (individualModels + ratioModels).distinctBy { it.name }
        val ratios = ratioMap(obj?.get("group_ratio") ?: obj?.get("groupRatio"))
        val labels = stringMap(obj?.get("usable_group") ?: obj?.get("groups"))
        val groups = (labels.keys + ratios.keys).distinct().map { id -> OfficialGroup(id, labels[id] ?: id, ratios[id]) }
        return OfficialCatalog(models.sortedBy { it.name }, groups.sortedBy { it.id })
    }

    fun userGroups(raw: JsonElement): List<OfficialGroup> {
        val root = unwrap(raw)
        val obj = root as? JsonObject
        val values = obj?.get("groups") ?: obj?.get("usable_group") ?: root
        if (values is JsonArray) return values.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.content.takeIf { item.isString && it.isNotBlank() }?.let { OfficialGroup(it, it, null) }
                is JsonObject -> {
                    val id = text(item["name"]) ?: text(item["group"]) ?: text(item["id"])
                    id?.let { OfficialGroup(it, text(item["description"]) ?: it, number(item["ratio"])) }
                }
                else -> null
            }
        }.distinctBy { it.id }
        if (values is JsonObject) {
            val ratios = ratioMap(values)
            val labels = stringMap(values)
            return values.entries.map { (id, entry) ->
                val detail = entry as? JsonObject
                OfficialGroup(id, detail?.let { text(it["description"]) ?: text(it["name"]) }
                    ?: labels[id] ?: id, detail?.let { number(it["ratio"]) } ?: ratios[id])
            }
        }
        return emptyList()
    }

    fun mergeGroups(catalog: List<OfficialGroup>, user: List<OfficialGroup>): List<OfficialGroup> =
        (catalog + user).groupBy { it.id }.map { (id, entries) ->
            OfficialGroup(id, entries.firstOrNull { it.label != id }?.label ?: id,
                entries.firstNotNullOfOrNull { it.ratio })
        }.sortedBy { it.id }

    private fun unwrap(raw: JsonElement): JsonElement =
        (raw as? JsonObject)?.get("data") ?: raw

    private fun model(obj: JsonObject?, fallback: String? = null): OfficialModelPrice? {
        if (obj == null) return null
        val name = text(obj["model_name"]) ?: text(obj["model"]) ?: text(obj["name"]) ?: fallback
        if (name.isNullOrBlank()) return null
        return OfficialModelPrice(name, number(obj["model_ratio"] ?: obj["modelRatio"]),
            number(obj["completion_ratio"] ?: obj["completionRatio"]),
            number(obj["model_price"] ?: obj["modelPrice"]))
    }

    private fun ratioMap(value: JsonElement?): Map<String, Double> =
        (value as? JsonObject)?.mapNotNull { (key, item) -> number(item)?.let { key to it } }?.toMap() ?: emptyMap()

    private fun stringMap(value: JsonElement?): Map<String, String> =
        (value as? JsonObject)?.mapNotNull { (key, item) -> text(item)?.let { key to it } }?.toMap() ?: emptyMap()

    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun number(value: JsonElement?): Double? =
        (value as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }
}
