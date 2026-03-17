package com.dexradar.parser

import com.dexradar.data.IdMapRepository
import com.dexradar.logger.DiscoveryLogger
import com.dexradar.model.EntityType
import com.dexradar.model.RadarEntity
import com.dexradar.overlay.RadarOverlayService
import java.util.concurrent.ConcurrentHashMap

class EventDispatcher(
    private val idMap: IdMapRepository,
    private val logger: DiscoveryLogger
) {
    // ── State ──────────────────────────────────────────────────────────────
    @Volatile var localPlayerId: Int = -1
    @Volatile var localX: Float = 0f
    @Volatile var localY: Float = 0f

    // ConcurrentHashMap — VPN thread writes, render thread reads
    private val entities = ConcurrentHashMap<Int, RadarEntity>()

    // String-keyed dispatch — stable across Albion patches
    private val handlerMap = HashMap<String, (HashMap<Int, Any?>) -> Unit>()

    // Event code int -> name string (seeded + grown by DiscoveryLogger)
    private val eventCodeNames = HashMap<Int, String>()

    init {
        registerHandlers()
        seedEventCodeNames()
    }

    private fun registerHandlers() {
        handlerMap["JoinFinished"]                   = ::handleJoinFinished
        handlerMap["NewCharacter"]                   = ::handleNewCharacter
        handlerMap["Leave"]                          = ::handleLeave
        handlerMap["Move"]                           = ::handleMove
        handlerMap["ForcedMovement"]                 = ::handleMove
        handlerMap["NewSimpleHarvestableObject"]     = ::handleHarvestable
        handlerMap["NewSimpleHarvestableObjectList"] = ::handleHarvestableList
        handlerMap["NewHarvestableObject"]           = ::handleHarvestable
        handlerMap["NewMob"]                         = ::handleMob
        handlerMap["NewSilverObject"]                = ::handleSilver
        handlerMap["NewLootChest"]                   = ::handleChest
        handlerMap["NewTreasureChest"]               = ::handleChest
        handlerMap["NewMatchLootChestObject"]        = ::handleChest
        handlerMap["NewMistsCagedWisp"]              = ::handleMistWisp
        handlerMap["NewMistsWispSpawn"]              = ::handleMistWisp
        handlerMap["NewRandomDungeonExit"]           = ::handleDungeon
        handlerMap["NewExpeditionExit"]              = ::handleDungeon
        handlerMap["NewHellgateExitPortal"]          = ::handleDungeon
        handlerMap["NewMistsDungeonExit"]            = ::handleDungeon
        handlerMap["NewPortalEntrance"]              = ::handleDungeon
        handlerMap["NewPortalExit"]                  = ::handleDungeon
        handlerMap["ChangeCluster"]                  = ::handleChangeCluster
        handlerMap["HarvestFinished"]                = ::handleHarvestFinished
        handlerMap["HealthUpdate"]                   = { /* optional health update */ }
        handlerMap["InventoryMoveItem"]              = { /* DISCARD — suppress logger noise */ }
    }

    private fun seedEventCodeNames() {
        idMap.eventCodeSeeds.forEach { (code, name) -> eventCodeNames[code] = name }
    }

    // ── Main dispatch (called from PhotonParser on IO thread) ──────────────
    fun dispatch(params: HashMap<Int, Any?>) {
        val codeInt = params[252] as? Int ?: return
        val eventName = eventCodeNames[codeInt] ?: run {
            logger.logUnknownEvent(codeInt, params)
            return
        }
        logger.maybeLogAllParams(eventName, params)
        handlerMap[eventName]?.invoke(params)
            ?: logger.logDiscovery(eventName, -1, params)
    }

    fun registerEventCode(code: Int, name: String) { eventCodeNames[code] = name }
    fun getEntities(): Collection<RadarEntity> = entities.values

    // ── CRITICAL: JoinFinished — fires before NewCharacter on zone entry ───
    private fun handleJoinFinished(params: HashMap<Int, Any?>) {
        val objectId = getInt(params, idMap.joinFinished.localObjectIdKey) ?: run {
            logger.logDiscovery("JoinFinished-noId", -1, params); return
        }
        val posX = getFloatWithPlanB(params, idMap.joinFinished.posXKey) ?: run {
            logger.logDiscovery("JoinFinished-noPosX", objectId, params); return
        }
        val posY = getFloatWithPlanB(params, idMap.joinFinished.posYKey) ?: run {
            logger.logDiscovery("JoinFinished-noPosY", objectId, params); return
        }
        localPlayerId = objectId
        localX = posX
        localY = posY
        entities.clear()
        RadarOverlayService.clearAll()
        RadarOverlayService.setLocalPlayerPosition(posX, posY)
    }

    private fun handleNewCharacter(params: HashMap<Int, Any?>) {
        val objectId = getInt(params, idMap.common.objectIdKey) ?: return
        val posX = getFloatWithPlanB(params, idMap.common.posXKey) ?: run {
            logger.logDiscovery("NewCharacter-noPosX", objectId, params); return
        }
        val posY = getFloatWithPlanB(params, idMap.common.posYKey) ?: return

        if (objectId == localPlayerId) {
            localX = posX; localY = posY
            RadarOverlayService.setLocalPlayerPosition(posX, posY)
            return
        }

        val name = getString(params, idMap.player.nameKey) ?: ""
        // Bug 4 fix: skip faction filtering initially; show all as PLAYER until keys verified
        val entity = RadarEntity(objectId, EntityType.PLAYER, posX, posY, "", 0, 0, name)
        entities[objectId] = entity
        RadarOverlayService.addEntity(entity)
    }

    private fun handleLeave(params: HashMap<Int, Any?>) {
        val objectId = getInt(params, idMap.common.objectIdKey) ?: return
        entities.remove(objectId)
        RadarOverlayService.removeEntity(objectId)
    }

    private fun handleMove(params: HashMap<Int, Any?>) {
        val objectId = getInt(params, idMap.common.objectIdKey) ?: return
        val posX = getFloatWithPlanB(params, idMap.common.posXKey) ?: return
        val posY = getFloatWithPlanB(params, idMap.common.posYKey) ?: return

        if (objectId == localPlayerId) {
            localX = posX; localY = posY
            RadarOverlayService.setLocalPlayerPosition(posX, posY)
            return
        }
        entities[objectId]?.let {
            val updated = it.copy(worldX = posX, worldY = posY)
            entities[objectId] = updated
            RadarOverlayService.updatePosition(objectId, posX, posY)
        }
    }

    private fun handleHarvestable(params: HashMap<Int, Any?>) {
        val objectId = getInt(params, idMap.common.objectIdKey) ?: return
        val posX = getFloatWithPlanB(params, idMap.common.posXKey) ?: run {
            logger.logDiscovery("NewHarvestable-noPosX", objectId, params); return
        }
        val posY = getFloatWithPlanB(params, idMap.common.posYKey) ?: return

        var typeName = getString(params, idMap.harvestable.typeNameKey)
        if (typeName == null)
            typeName = planBScanTypeName(params, listOf("T1_","T2_","T3_","T4_","T5_","T6_","T7_","T8_"))
        if (typeName == null) {
            logger.logDiscovery("NewHarvestable-noType", objectId, params); return
        }

        val (tier, enchant) = parseTierEnchant(typeName)
        val entity = RadarEntity(objectId, classifyResource(typeName), posX, posY, typeName, tier, enchant, "")
        entities[objectId] = entity
        RadarOverlayService.addEntity(entity)
    }

    private fun handleHarvestableList(params: HashMap<Int, Any?>) {
        val list = params[idMap.harvestable.listKey]
        if (list is List<*>) {
            list.forEach { entry ->
                if (entry is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    handleHarvestable(entry as HashMap<Int, Any?>)
                }
            }
        } else {
            handleHarvestable(params)
        }
    }

    private fun handleMob(params: HashMap<Int, Any?>) {
        val objectId = getInt(params, idMap.common.objectIdKey) ?: return
        val posX = getFloatWithPlanB(params, idMap.common.posXKey) ?: run {
            logger.logDiscovery("NewMob-noPosX", objectId, params); return
        }
        val posY = getFloatWithPlanB(params, idMap.common.posYKey) ?: return

        var typeName = getString(params, idMap.mob.typeNameKey)
        if (typeName == null)
            typeName = planBScanTypeName(params, listOf("T1_","T2_","T3_","T4_","T5_","T6_","T7_","T8_"))
        if (typeName == null) {
            logger.logDiscovery("NewMob-noType", objectId, params); return
        }

        val (tier, enchant) = parseTierEnchant(typeName)
        val isBoss = getBool(params, idMap.mob.isBossKey) ?: false
        val entityType = if (isBoss) EntityType.BOSS_MOB else classifyMob(typeName)
        val entity = RadarEntity(objectId, entityType, posX, posY, typeName, tier, enchant, "")
        entities[objectId] = entity
        RadarOverlayService.addEntity(entity)
    }

    private fun handleSilver(params: HashMap<Int, Any?>) {
        val objectId = getInt(params, idMap.common.objectIdKey) ?: return
        val posX = getFloatWithPlanB(params, idMap.common.posXKey) ?: return
        val posY = getFloatWithPlanB(params, idMap.common.posYKey) ?: return
        val entity = RadarEntity(objectId, EntityType.SILVER, posX, posY, "SILVER", 0, 0, "")
        entities[objectId] = entity
        RadarOverlayService.addEntity(entity)
    }

    private fun handleChest(params: HashMap<Int, Any?>) {
        val objectId = getInt(params, idMap.common.objectIdKey) ?: return
        val posX = getFloatWithPlanB(params, idMap.common.posXKey) ?: return
        val posY = getFloatWithPlanB(params, idMap.common.posYKey) ?: return
        val typeName = getString(params, idMap.chest.typeNameKey) ?: "CHEST"
        val rarity   = getInt(params, idMap.chest.rarityKey) ?: 0
        val entity = RadarEntity(objectId, EntityType.CHEST, posX, posY, typeName, rarity, 0, "")
        entities[objectId] = entity
        RadarOverlayService.addEntity(entity)
    }

    private fun handleDungeon(params: HashMap<Int, Any?>) {
        val objectId = getInt(params, idMap.common.objectIdKey) ?: return
        val posX = getFloatWithPlanB(params, idMap.common.posXKey) ?: return
        val posY = getFloatWithPlanB(params, idMap.common.posYKey) ?: return
        val typeName = getString(params, idMap.dungeon.typeNameKey) ?: "DUNGEON"
        val rarity   = getInt(params, idMap.dungeon.rarityKey) ?: 0
        val entity = RadarEntity(objectId, EntityType.DUNGEON_PORTAL, posX, posY, typeName, rarity, 0, "")
        entities[objectId] = entity
        RadarOverlayService.addEntity(entity)
    }

    private fun handleMistWisp(params: HashMap<Int, Any?>) {
        val objectId = getInt(params, idMap.common.objectIdKey) ?: return
        val posX = getFloatWithPlanB(params, idMap.common.posXKey) ?: return
        val posY = getFloatWithPlanB(params, idMap.common.posYKey) ?: return
        val typeName = getString(params, idMap.mist.typeNameKey) ?: "MIST"
        val rarity   = getInt(params, idMap.mist.rarityKey) ?: 0
        val entity = RadarEntity(objectId, EntityType.MIST_WISP, posX, posY, typeName, rarity, 0, "")
        entities[objectId] = entity
        RadarOverlayService.addEntity(entity)
    }

    private fun handleChangeCluster(params: HashMap<Int, Any?>) {
        entities.clear()
        RadarOverlayService.clearAll()
        localPlayerId = -1  // CRITICAL: reset; wait for next JoinFinished
    }

    private fun handleHarvestFinished(params: HashMap<Int, Any?>) {
        val objectId = getInt(params, idMap.common.objectIdKey) ?: return
        entities.remove(objectId)
        RadarOverlayService.removeEntity(objectId)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun getInt(params: HashMap<Int, Any?>, key: Int): Int? {
        if (key == -1) return null
        return when (val v = params[key]) {
            is Int    -> v
            is Number -> v.toInt()
            else      -> null
        }
    }

    private fun getString(params: HashMap<Int, Any?>, key: Int): String? {
        if (key == -1) return null
        return params[key] as? String
    }

    private fun getBool(params: HashMap<Int, Any?>, key: Int): Boolean? {
        if (key == -1) return null
        return when (val v = params[key]) {
            is Boolean -> v
            is Int     -> v != 0
            is Byte    -> v != 0.toByte()
            else       -> null
        }
    }

    // Plan B: float range scanner for world coordinates [-32768, +32768]
    private fun getFloatWithPlanB(params: HashMap<Int, Any?>, key: Int): Float? {
        if (key != -1) {
            val direct = when (val v = params[key]) {
                is Float  -> v
                is Number -> v.toFloat()
                else      -> null
            }
            if (direct != null) return direct
        }
        return params.values
            .filterIsInstance<Float>()
            .firstOrNull { it >= idMap.coordinatePlanB.first && it <= idMap.coordinatePlanB.second }
    }

    // Plan B: scan all string params for type name prefix
    private fun planBScanTypeName(params: HashMap<Int, Any?>, prefixes: List<String>): String? =
        params.values
            .filterIsInstance<String>()
            .firstOrNull { v -> prefixes.any { p -> v.startsWith(p, ignoreCase = true) } }

    private fun parseTierEnchant(name: String): Pair<Int, Int> {
        val tier = Regex("^T(\\d)_").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val enchant = when {
            name.contains("@4") || name.endsWith(".4") -> 4
            name.contains("@3") || name.endsWith(".3") -> 3
            name.contains("@2") || name.endsWith(".2") -> 2
            name.contains("@1") || name.endsWith(".1") -> 1
            else -> 0
        }
        return Pair(tier, enchant)
    }

    private fun classifyResource(name: String): EntityType {
        val u = name.uppercase()
        return when {
            idMap.knownPrefixes["fiber"]!!.any { u.contains(it) } -> EntityType.RESOURCE_FIBER
            idMap.knownPrefixes["ore"]!!.any { u.contains(it) }   -> EntityType.RESOURCE_ORE
            idMap.knownPrefixes["logs"]!!.any { u.contains(it) }  -> EntityType.RESOURCE_LOGS
            idMap.knownPrefixes["rock"]!!.any { u.contains(it) }  -> EntityType.RESOURCE_ROCK
            idMap.knownPrefixes["hide"]!!.any { u.contains(it) }  -> EntityType.RESOURCE_HIDE
            idMap.knownPrefixes["crop"]!!.any { u.contains(it) }  -> EntityType.RESOURCE_CROP
            else -> EntityType.RESOURCE_FIBER
        }
    }

    private fun classifyMob(name: String): EntityType {
        val u = name.uppercase()
        return when {
            idMap.knownPrefixes["boss"]!!.any { u.contains(it) } -> EntityType.BOSS_MOB
            u.contains("ENCHANT") || u.contains("CORRUPT")       -> EntityType.ENCHANTED_MOB
            else -> EntityType.NORMAL_MOB
        }
    }
}
