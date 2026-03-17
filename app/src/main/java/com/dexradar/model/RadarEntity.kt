package com.dexradar.model

enum class EntityType {
    RESOURCE_FIBER,
    RESOURCE_ORE,
    RESOURCE_LOGS,
    RESOURCE_ROCK,
    RESOURCE_HIDE,
    RESOURCE_CROP,
    NORMAL_MOB,
    ENCHANTED_MOB,
    BOSS_MOB,
    PLAYER,
    HOSTILE_PLAYER,
    SILVER,
    MIST_WISP,
    CHEST,
    DUNGEON_PORTAL,
    UNKNOWN
}

data class RadarEntity(
    val id: Int,
    val type: EntityType,
    val worldX: Float,
    val worldY: Float,
    val typeName: String,   // raw type string e.g. "T4_FIBER@2"
    val tier: Int,          // 0 if not applicable
    val enchant: Int,       // 0-4
    val name: String        // player name, empty for non-players
)
