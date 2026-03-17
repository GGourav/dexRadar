package com.dexradar.data

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.Gson

class IdMapRepository(private val context: Context) {

    var coordinatePlanB: Pair<Float, Float> = Pair(-32768f, 32768f)
        private set

    var knownPrefixes: Map<String, List<String>> = emptyMap()
        private set

    var eventCodeSeeds: Map<Int, String> = emptyMap()
        private set

    inner class CommonKeys       { var objectIdKey = 0;  var posXKey = 8;  var posYKey = 9 }
    inner class JoinFinishedKeys { var localObjectIdKey = 0; var posXKey = 8; var posYKey = 9 }
    inner class HarvestableKeys  { var typeNameKey = 1; var listKey = 2; var tierKey = 7; var enchantKey = 11 }
    inner class MobKeys          { var typeNameKey = 1; var tierKey = 7; var enchantKey = 11; var isBossKey = 50; var healthKey = 12 }
    inner class PlayerKeys       { var nameKey = 1; var guildKey = 3; var allianceKey = 4; var factionFlagKey = 23; var healthKey = 12; var mountKey = 26 }
    inner class ChestKeys        { var typeNameKey = 1; var rarityKey = 7 }
    inner class DungeonKeys      { var typeNameKey = 1; var rarityKey = 7 }
    inner class MistKeys         { var typeNameKey = 1; var rarityKey = 7 }
    inner class SilverKeys       { var typeNameKey = 1; var amountKey = 2 }

    val common       = CommonKeys()
    val joinFinished = JoinFinishedKeys()
    val harvestable  = HarvestableKeys()
    val mob          = MobKeys()
    val player       = PlayerKeys()
    val chest        = ChestKeys()
    val dungeon      = DungeonKeys()
    val mist         = MistKeys()
    val silver       = SilverKeys()

    fun load() {
        try {
            val json = context.assets.open("id_map.json").bufferedReader().readText()
            val root = Gson().fromJson(json, JsonObject::class.java)

            fun JsonObject.int(field: String, default: Int) = this[field]?.asInt ?: default

            root["common"]?.asJsonObject?.let { c ->
                common.objectIdKey = c.int("objectIdKey", 0)
                common.posXKey     = c.int("posXKey", 8)
                common.posYKey     = c.int("posYKey", 9)
            }
            root["joinFinished"]?.asJsonObject?.let { j ->
                joinFinished.localObjectIdKey = j.int("localObjectIdKey", 0)
                joinFinished.posXKey          = j.int("posXKey", 8)
                joinFinished.posYKey          = j.int("posYKey", 9)
            }
            root["harvestable"]?.asJsonObject?.let { h ->
                harvestable.typeNameKey = h.int("typeNameKey", 1)
                harvestable.listKey    = h.int("listKey", 2)
                harvestable.tierKey    = h.int("tierKey", 7)
                harvestable.enchantKey = h.int("enchantKey", 11)
            }
            root["mob"]?.asJsonObject?.let { m ->
                mob.typeNameKey = m.int("typeNameKey", 1)
                mob.tierKey     = m.int("tierKey", 7)
                mob.enchantKey  = m.int("enchantKey", 11)
                mob.isBossKey   = m.int("isBossKey", 50)
                mob.healthKey   = m.int("healthKey", 12)
            }
            root["player"]?.asJsonObject?.let { p ->
                player.nameKey        = p.int("nameKey", 1)
                player.guildKey       = p.int("guildKey", 3)
                player.allianceKey    = p.int("allianceKey", 4)
                player.factionFlagKey = p.int("factionFlagKey", 23)
                player.healthKey      = p.int("healthKey", 12)
                player.mountKey       = p.int("mountKey", 26)
            }
            root["coordinatePlanB"]?.asJsonObject?.let { pb ->
                coordinatePlanB = Pair(
                    pb["minValid"]?.asFloat ?: -32768f,
                    pb["maxValid"]?.asFloat ?: 32768f
                )
            }
            root["knownPrefixes"]?.asJsonObject?.let { kp ->
                knownPrefixes = kp.entrySet().associate { (k, v) ->
                    k to v.asJsonArray.map { it.asString }
                }
            }
            root["eventCodeSeeds"]?.asJsonObject?.let { es ->
                eventCodeSeeds = es.entrySet()
                    .filter { it.key.toIntOrNull() != null }
                    .associate { (k, v) -> k.toInt() to v.asString }
            }
        } catch (e: Exception) {
            android.util.Log.e("IdMapRepo", "Failed to load id_map.json: ${e.message}")
        }
    }
}
