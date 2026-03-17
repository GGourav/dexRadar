package com.dexradar.overlay

import android.content.Context
import android.graphics.*
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.dexradar.model.EntityType
import com.dexradar.model.RadarEntity

class RadarSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private val MAX_WORLD_RANGE = 200f
    private var renderThread: Thread? = null
    @Volatile private var running = false

    // Paints — create once, reuse every frame
    private val bgPaint = Paint().apply { color = Color.argb(180, 0, 0, 0); style = Paint.Style.FILL }
    private val borderPaint = Paint().apply {
        color = Color.argb(128, 255, 255, 255)
        style = Paint.Style.STROKE; strokeWidth = 1.5f; isAntiAlias = true
    }
    private val ringPaint = Paint().apply {
        color = Color.argb(60, 255, 255, 255)
        style = Paint.Style.STROKE; strokeWidth = 1f; isAntiAlias = true
    }
    private val selfPaint = Paint().apply { color = Color.CYAN; style = Paint.Style.FILL; isAntiAlias = true }
    private val entityPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val ringEntityPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }

    // Cached prefs booleans — refreshed once per frame
    private val prefs = context.getSharedPreferences("dexradar_prefs", Context.MODE_PRIVATE)

    init {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSPARENT)
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        renderThread = Thread { renderLoop() }.also { it.isDaemon = true; it.start() }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) { running = false }
    override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}

    fun stopRendering() { running = false }

    private fun renderLoop() {
        while (running) {
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas() ?: continue
                drawRadar(canvas)
            } finally {
                canvas?.let { holder.unlockCanvasAndPost(it) }
            }
            Thread.sleep(33) // ~30 FPS
        }
    }

    private fun drawRadar(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val cx = width / 2f
        val cy = height / 2f
        val radarRadius = minOf(cx, cy) - 4f
        val scale = radarRadius / MAX_WORLD_RANGE

        val localX = RadarOverlayService.getLocalPlayerX()
        val localY = RadarOverlayService.getLocalPlayerY()

        // Background + border
        canvas.drawCircle(cx, cy, radarRadius, bgPaint)
        canvas.drawCircle(cx, cy, radarRadius, borderPaint)

        // Range rings
        canvas.drawCircle(cx, cy, radarRadius * 0.25f, ringPaint)
        canvas.drawCircle(cx, cy, radarRadius * 0.50f, ringPaint)
        canvas.drawCircle(cx, cy, radarRadius * 0.75f, ringPaint)

        // Crosshairs
        canvas.drawLine(cx - radarRadius, cy, cx + radarRadius, cy, ringPaint)
        canvas.drawLine(cx, cy - radarRadius, cx, cy + radarRadius, ringPaint)

        // Draw entities
        // Cache prefs reads once per frame — do NOT call getBoolean() per entity
        val showPlayer   = prefs.getBoolean("playerDot", true)
        val showFiber    = prefs.getBoolean("harvestingFiber", true)
        val showOre      = prefs.getBoolean("harvestingOre", true)
        val showWood     = prefs.getBoolean("harvestingWood", true)
        val showRock     = prefs.getBoolean("harvestingRock", true)
        val showHide     = prefs.getBoolean("harvestingHide", true)
        val showEnemy    = prefs.getBoolean("mobEnemy", true)
        val showBoss     = prefs.getBoolean("mobBoss", true)
        val showChest    = prefs.getBoolean("chest", true)
        val showDungeon  = prefs.getBoolean("dungeon", true)
        val showMist     = prefs.getBoolean("mist", true)

        for (entity in RadarOverlayService.getEntities()) {
            if (!shouldDraw(entity, showPlayer, showFiber, showOre, showWood,
                    showRock, showHide, showEnemy, showBoss, showChest, showDungeon, showMist)) continue

            val dx = (entity.worldX - localX) * scale
            val dy = (entity.worldY - localY) * scale

            // Clip to radar circle
            if (dx * dx + dy * dy > radarRadius * radarRadius) continue

            val sx = cx + dx
            val sy = cy - dy  // Invert Y: game Y+ = North = screen up

            drawEntity(canvas, entity, sx, sy)
        }

        // Self — always at centre
        canvas.drawCircle(cx, cy, 5f, selfPaint)
    }

    private fun drawEntity(canvas: Canvas, entity: RadarEntity, sx: Float, sy: Float) {
        when (entity.type) {
            EntityType.RESOURCE_FIBER,
            EntityType.RESOURCE_ORE,
            EntityType.RESOURCE_LOGS,
            EntityType.RESOURCE_ROCK,
            EntityType.RESOURCE_HIDE,
            EntityType.RESOURCE_CROP  -> drawResource(canvas, entity, sx, sy)
            EntityType.NORMAL_MOB     -> drawCircle(canvas, sx, sy, 4f, Color.parseColor("#4CAF50"))
            EntityType.ENCHANTED_MOB  -> drawCircle(canvas, sx, sy, 6f, Color.parseColor("#9C27B0"))
            EntityType.BOSS_MOB       -> drawCircle(canvas, sx, sy, 8f, Color.parseColor("#FF9800"))
            EntityType.PLAYER         -> drawCircle(canvas, sx, sy, 5f, Color.WHITE)
            EntityType.HOSTILE_PLAYER -> drawCircle(canvas, sx, sy, 6f, Color.RED)
            EntityType.SILVER         -> drawCircle(canvas, sx, sy, 3f, Color.parseColor("#FFEB3B"))
            EntityType.CHEST          -> drawSquare(canvas, sx, sy, 5f, Color.parseColor("#FFC107"))
            EntityType.DUNGEON_PORTAL -> drawTriangle(canvas, sx, sy, 6f, rarityColor(entity.tier))
            EntityType.MIST_WISP      -> drawDiamond(canvas, sx, sy, 5f, rarityColor(entity.tier))
            EntityType.UNKNOWN        -> {}
        }
    }

    private fun drawResource(canvas: Canvas, entity: RadarEntity, sx: Float, sy: Float) {
        val alpha = minOf(100 + entity.tier * 20, 255)
        drawCircle(canvas, sx, sy, 4f, Color.argb(alpha, 50, 120, 220))
        val ringColor = when (entity.enchant) {
            1 -> Color.parseColor("#4CAF50")
            2 -> Color.parseColor("#1565C0")
            3 -> Color.parseColor("#7B1FA2")
            4 -> Color.parseColor("#F9A825")
            else -> return
        }
        ringEntityPaint.color = ringColor
        canvas.drawCircle(sx, sy, 6f, ringEntityPaint)
    }

    private fun drawCircle(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        entityPaint.color = color; canvas.drawCircle(x, y, r, entityPaint)
    }

    private fun drawSquare(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        entityPaint.color = color; canvas.drawRect(x - r, y - r, x + r, y + r, entityPaint)
    }

    private fun drawTriangle(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        entityPaint.color = color
        val path = Path().apply { moveTo(x, y - r); lineTo(x + r, y + r); lineTo(x - r, y + r); close() }
        canvas.drawPath(path, entityPaint)
    }

    private fun drawDiamond(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        entityPaint.color = color
        val path = Path().apply { moveTo(x, y - r); lineTo(x + r, y); lineTo(x, y + r); lineTo(x - r, y); close() }
        canvas.drawPath(path, entityPaint)
    }

    private fun rarityColor(rarity: Int): Int = when (rarity) {
        1    -> Color.parseColor("#4CAF50")
        2    -> Color.parseColor("#2196F3")
        3    -> Color.parseColor("#9C27B0")
        4    -> Color.parseColor("#F9A825")
        else -> Color.GRAY
    }

    private fun shouldDraw(
        entity: RadarEntity,
        showPlayer: Boolean, showFiber: Boolean, showOre: Boolean,
        showWood: Boolean, showRock: Boolean, showHide: Boolean,
        showEnemy: Boolean, showBoss: Boolean, showChest: Boolean,
        showDungeon: Boolean, showMist: Boolean
    ): Boolean = when (entity.type) {
        EntityType.PLAYER, EntityType.HOSTILE_PLAYER -> showPlayer
        EntityType.RESOURCE_FIBER, EntityType.RESOURCE_CROP -> showFiber
        EntityType.RESOURCE_ORE   -> showOre
        EntityType.RESOURCE_LOGS  -> showWood
        EntityType.RESOURCE_ROCK  -> showRock
        EntityType.RESOURCE_HIDE  -> showHide
        EntityType.NORMAL_MOB, EntityType.ENCHANTED_MOB -> showEnemy
        EntityType.BOSS_MOB       -> showBoss
        EntityType.CHEST          -> showChest
        EntityType.DUNGEON_PORTAL -> showDungeon
        EntityType.MIST_WISP      -> showMist
        EntityType.SILVER         -> true
        EntityType.UNKNOWN        -> false
    }
}
