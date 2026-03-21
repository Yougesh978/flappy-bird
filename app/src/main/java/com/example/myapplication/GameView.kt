package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.min

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private var thread: GameThread

    private var bgImage: Bitmap
    private var birdFrames = arrayOfNulls<Bitmap>(3)
    private lateinit var pipeTop: Bitmap
    private lateinit var pipeBottom: Bitmap
    private var gameoverImage: Bitmap
    private var customFont: Typeface? = null

    private val V_WIDTH = 1000f
    private val V_HEIGHT = 1800f
    private var bgWidthCached = 0f

    enum class GameState { WAITING, STARTED, GAMEOVER }
    enum class LevelResult { NONE, COMPLETE, FAILED }
    enum class TrapType { NET, MINE }

    private var gameState = GameState.WAITING
    private var levelResult = LevelResult.NONE
    private var frames = 0
    private var score = 0
    private var level = 1
    private var pipesThisLevel = 0
    private var currentFrame = 0
    private var flappyY = V_HEIGHT * 0.32f
    private var flappyV = 0f
    private var nextFlapFrame = 50
    private var nextItemSpawnFrame = 90
    private var wormBoostFrames = 0
    private var lastTrapBonus = 0

    private val BIRD_WIDTH = 100f
    private val BIRD_HEIGHT = 70f
    private val FLAPPY_X = 250f
    private val PIPE_WIDTH = 120f
    private val MIN_GAP = 250f
    private val WORM_SIZE = 60f
    private val TRAP_SIZE = 50f

    private var touchTargetY = V_HEIGHT / 2
    private var pendingTouchTargetY = V_HEIGHT / 2
    private var isTouching = false
    private val LOCK_ZONE_DIST = 150f
    private val PIPE_MOVE_SPEED = 18f
    private val PIPE_PULL_LIMIT = 110f
    private val SAFE_EDGE_MARGIN = 170f

    class Pipe(var x: Float, var gapY: Float, var passed: Boolean = false)
    class Worm(var x: Float, var y: Float, var collected: Boolean = false)
    class Trap(var x: Float, var y: Float, val type: TrapType, var active: Boolean = true)

    private val pipes = ArrayList<Pipe>()
    private val worms = ArrayList<Worm>()
    private val traps = ArrayList<Trap>()

    private val targetPipes: Int
        get() = when (level.coerceAtMost(8)) {
            1 -> 5
            2 -> 6
            3 -> 7
            4 -> 8
            5 -> 9
            6 -> 10
            7 -> 11
            else -> 12
        }

    private val currentPipeSpeed: Float
        get() = when (level.coerceAtMost(8)) {
            1 -> 5f
            2 -> 5.5f
            3 -> 6f
            4 -> 6.5f
            5 -> 7f
            6 -> 7.5f
            7 -> 8f
            else -> 8.5f
        }

    private val currentPipeGap: Float
        get() = when (level.coerceAtMost(8)) {
            1 -> 420f
            2 -> 400f
            3 -> 380f
            4 -> 360f
            5 -> 340f
            6 -> 320f
            7 -> 300f
            else -> 280f
        }

    private val currentSpawnInterval: Int
        get() = when (level.coerceAtMost(8)) {
            1 -> 110
            2 -> 100
            3 -> 95
            4 -> 90
            5 -> 85
            6 -> 80
            7 -> 75
            else -> 70
        }

    private val birdAIStrength: Float
        get() = when (level.coerceAtMost(8)) {
            1 -> 0.45f
            2 -> 0.55f
            3 -> 0.65f
            4 -> 0.75f
            5 -> 0.85f
            6 -> 0.92f
            7 -> 0.97f
            else -> 1.0f
        }

    private val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private val textPaint = Paint()
    private val wormBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 105, 150) }
    private val wormDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 60, 100) }
    private val wormEyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val wormPupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val netPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(180, 180, 180)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val minePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 50, 50) }
    private val mineOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(180, 0, 0)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val boostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 200, 0)
        style = Paint.Style.FILL
    }
    private val overlayPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }

    init {
        holder.addCallback(this)
        thread = GameThread(holder, this)

        val assetManager = context.assets

        val rawBg = BitmapFactory.decodeStream(assetManager.open("images/background.png"))
        val bgScale = V_HEIGHT / rawBg.height.toFloat()
        val targetBgWidth = (rawBg.width * bgScale).toInt()
        bgImage = Bitmap.createScaledBitmap(rawBg, targetBgWidth, V_HEIGHT.toInt(), true)
        rawBg.recycle()
        bgWidthCached = targetBgWidth.toFloat()

        val rawF1 = BitmapFactory.decodeStream(assetManager.open("images/flappy1.png"))
        birdFrames[0] = Bitmap.createScaledBitmap(rawF1, BIRD_WIDTH.toInt(), BIRD_HEIGHT.toInt(), true)
        rawF1.recycle()
        val rawF2 = BitmapFactory.decodeStream(assetManager.open("images/flappy2.png"))
        birdFrames[1] = Bitmap.createScaledBitmap(rawF2, BIRD_WIDTH.toInt(), BIRD_HEIGHT.toInt(), true)
        rawF2.recycle()
        val rawF3 = BitmapFactory.decodeStream(assetManager.open("images/flappy3.png"))
        birdFrames[2] = Bitmap.createScaledBitmap(rawF3, BIRD_WIDTH.toInt(), BIRD_HEIGHT.toInt(), true)
        rawF3.recycle()

        val rawPipe = BitmapFactory.decodeStream(assetManager.open("images/pipe-red.png"))
        pipeBottom = Bitmap.createScaledBitmap(rawPipe, PIPE_WIDTH.toInt(), V_HEIGHT.toInt(), true)
        val matrix = android.graphics.Matrix()
        matrix.postScale(1f, -1f)
        pipeTop = Bitmap.createBitmap(pipeBottom, 0, 0, pipeBottom.width, pipeBottom.height, matrix, true)
        rawPipe.recycle()

        val rawGo = BitmapFactory.decodeStream(assetManager.open("images/gameover.png"))
        val goW = (rawGo.width * 2f).toInt()
        val goH = (rawGo.height * 2f).toInt()
        gameoverImage = Bitmap.createScaledBitmap(rawGo, goW, goH, true)
        rawGo.recycle()

        try {
            customFont = Typeface.createFromAsset(assetManager, "fonts/TiltPrism-Regular.ttf")
        } catch (_: Exception) {
        }

        textPaint.apply {
            color = Color.WHITE
            textSize = 100f
            customFont?.let { typeface = it }
            setShadowLayer(5f, 5f, 5f, Color.BLACK)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!thread.running) {
            thread = GameThread(holder, this)
            thread.running = true
            thread.start()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        var retry = true
        thread.running = false
        while (retry) {
            try {
                thread.join()
                retry = false
            } catch (_: InterruptedException) {
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scaleY = V_HEIGHT / height.toFloat()
        val virtualY = event.y * scaleY

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                when (gameState) {
                    GameState.WAITING -> {
                        gameState = GameState.STARTED
                        pendingTouchTargetY = virtualY
                        touchTargetY = clampGapCenter(virtualY)
                        isTouching = true
                    }

                    GameState.STARTED -> {
                        pendingTouchTargetY = virtualY
                        isTouching = true
                    }

                    GameState.GAMEOVER -> {
                        if (levelResult == LevelResult.COMPLETE) {
                            level++
                        }
                        resetGame()
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (gameState == GameState.STARTED) {
                    pendingTouchTargetY = virtualY
                    isTouching = true
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun resetGame() {
        flappyY = V_HEIGHT * 0.32f
        flappyV = 0f
        pipes.clear()
        worms.clear()
        traps.clear()
        score = 0
        pipesThisLevel = 0
        frames = 0
        levelResult = LevelResult.NONE
        currentFrame = 0
        nextFlapFrame = 50
        nextItemSpawnFrame = 90
        wormBoostFrames = 0
        lastTrapBonus = 0
        touchTargetY = V_HEIGHT / 2
        pendingTouchTargetY = V_HEIGHT / 2
        isTouching = false
        gameState = GameState.WAITING
    }

    fun update() {
        frames++

        if (gameState == GameState.WAITING || gameState == GameState.STARTED) {
            if (frames % 6 == 0) {
                currentFrame = (currentFrame + 1) % 3
            }
        }

        if (gameState != GameState.STARTED) {
            return
        }

        updateBird()

        if (gameState != GameState.STARTED) {
            return
        }

        updatePipeControl()
        spawnObjectsIfNeeded()
        spawnItemsIfNeeded()
        movePipesAndScore()
        moveWorms()
        moveTraps()
        checkPipeCollisions()
    }

    private fun updateBird() {
        flappyY += flappyV
        flappyV += if (wormBoostFrames > 0) 1.6f else 0.8f

        if (wormBoostFrames > 0) {
            wormBoostFrames--
        }

        if (frames >= nextFlapFrame) {
            flappyV = if (wormBoostFrames > 0) -22f else -13.5f
            nextFlapFrame = frames + 28 + (Math.random() * 18).toInt()
        }

        val nearestPipe = findNearestPipeAhead()
        if (nearestPipe != null) {
            val birdCenterY = flappyY + BIRD_HEIGHT / 2
            val diff = nearestPipe.gapY - birdCenterY
            val strength = birdAIStrength

            if (diff < -45f && flappyV > -10f && Math.random() < min(1f, strength + 0.08f).toDouble()) {
                val aiImpulse = if (wormBoostFrames > 0) -14f else -(5.5f + strength * 5.5f)
                flappyV = min(flappyV, aiImpulse)
                nextFlapFrame = frames + 14 + (Math.random() * 14).toInt()
            } else if (diff > 55f && flappyV < 8f && Math.random() < (0.45f + strength * 0.45f).toDouble()) {
                flappyV += 1.2f + strength * 1.2f
                nextFlapFrame = frames + 10 + (Math.random() * 18).toInt()
            }
        }

        val imminentPipe = findImminentPipe()
        if (imminentPipe != null) {
            val gapHalf = maxOf(currentPipeGap, MIN_GAP) / 2
            val birdCenterY = flappyY + BIRD_HEIGHT / 2
            val topDanger = imminentPipe.gapY - gapHalf - birdCenterY
            val bottomDanger = birdCenterY - (imminentPipe.gapY + gapHalf)

            if (topDanger > -60f && topDanger < 55f && flappyV > -16f) {
                flappyV -= 3.2f + birdAIStrength * 2.2f
            }
            if (bottomDanger > -60f && bottomDanger < 55f && flappyV < 16f) {
                flappyV += 3f + birdAIStrength * 2.2f
            }
        }

        val nearbyTrap = findRelevantTrapAhead()
        if (nearbyTrap != null) {
            val trapCenterY = nearbyTrap.y + TRAP_SIZE / 2
            val trapDiff = trapCenterY - (flappyY + BIRD_HEIGHT / 2)
            val avoidImpulse = 0.6f + birdAIStrength
            flappyV += if (trapDiff >= 0f) -avoidImpulse else avoidImpulse
        } else {
            val nearbyWorm = findRelevantWormAhead()
            if (nearbyWorm != null && wormBoostFrames <= 0) {
                val wormCenterY = nearbyWorm.y + WORM_SIZE / 2
                val wormDiff = wormCenterY - (flappyY + BIRD_HEIGHT / 2)
                if (wormDiff < -40f && flappyV > -6f) {
                    flappyV -= 0.9f
                } else if (wormDiff > 40f && flappyV < 6f) {
                    flappyV += 0.9f
                }
            }
        }

        if (flappyY < -BIRD_HEIGHT || flappyY > V_HEIGHT) {
            finishLevel(birdTrapped = true)
        }
    }

    private fun updatePipeControl() {
        if (!isTouching) {
            return
        }

        touchTargetY = clampGapCenter(pendingTouchTargetY)

        val controllablePipe = findControllablePipe() ?: return
        val distToPipe = controllablePipe.x - FLAPPY_X
        if (distToPipe <= LOCK_ZONE_DIST) {
            return
        }

        val baselineGapY = idealGapForPipe(controllablePipe)
        val constrainedTargetY = touchTargetY.coerceIn(
            baselineGapY - PIPE_PULL_LIMIT,
            baselineGapY + PIPE_PULL_LIMIT
        )

        val diff = constrainedTargetY - controllablePipe.gapY
        if (abs(diff) < 1.5f) {
            return
        }

        val move = diff.coerceIn(-PIPE_MOVE_SPEED, PIPE_MOVE_SPEED)
        controllablePipe.gapY = clampGapCenter(controllablePipe.gapY + move)
    }

    private fun spawnObjectsIfNeeded() {
        if (frames % currentSpawnInterval != 0) {
            return
        }

        val gap = maxOf(currentPipeGap, MIN_GAP)
        val minGapCenter = gap / 2 + 100f
        val maxGapCenter = V_HEIGHT - gap / 2 - 100f
        val gapY = minGapCenter + Math.random().toFloat() * (maxGapCenter - minGapCenter)
        val spawnX = V_WIDTH + 100f

        pipes.add(Pipe(spawnX, gapY))
    }

    private fun spawnItemsIfNeeded() {
        if (frames < nextItemSpawnFrame) {
            return
        }

        if (Math.random() < 0.35) {
            worms.add(Worm(V_WIDTH + 160f, randomFlightLane(WORM_SIZE) - WORM_SIZE / 2))
        }

        if (level >= 3 && Math.random() < trapSpawnChance()) {
            val trapType = if (level >= 5 && Math.random() < 0.35) TrapType.MINE else TrapType.NET
            traps.add(Trap(V_WIDTH + 200f, randomFlightLane(TRAP_SIZE) - TRAP_SIZE / 2, trapType))
        }

        nextItemSpawnFrame = frames + 65 + (Math.random() * 45).toInt()
    }

    private fun movePipesAndScore() {
        val speed = currentPipeSpeed
        val iterator = pipes.iterator()
        while (iterator.hasNext()) {
            val pipe = iterator.next()

            if (!pipe.passed && FLAPPY_X > pipe.x + PIPE_WIDTH) {
                pipe.passed = true
                score++
                pipesThisLevel++
                if (pipesThisLevel >= targetPipes) {
                    finishLevel(birdTrapped = false)
                }
            }

            pipe.x -= speed
            if (pipe.x + PIPE_WIDTH < -100f) {
                iterator.remove()
            }
        }
    }

    private fun moveWorms() {
        val speed = currentPipeSpeed
        val birdCX = FLAPPY_X + BIRD_WIDTH / 2
        val birdCY = flappyY + BIRD_HEIGHT / 2
        val iterator = worms.iterator()

        while (iterator.hasNext()) {
            val worm = iterator.next()
            worm.x -= speed

            if (!worm.collected) {
                val wormCX = worm.x + WORM_SIZE / 2
                val wormCY = worm.y + WORM_SIZE / 2
                val dx = birdCX - wormCX
                val dy = birdCY - wormCY
                val radius = BIRD_WIDTH / 2 + WORM_SIZE / 2
                if (dx * dx + dy * dy < radius * radius) {
                    worm.collected = true
                    wormBoostFrames = 120
                }
            }

            if (worm.collected || worm.x + WORM_SIZE < -50f) {
                iterator.remove()
            }
        }
    }

    private fun moveTraps() {
        val speed = currentPipeSpeed
        val birdCX = FLAPPY_X + BIRD_WIDTH / 2
        val birdCY = flappyY + BIRD_HEIGHT / 2
        val iterator = traps.iterator()

        while (iterator.hasNext()) {
            val trap = iterator.next()
            trap.x -= speed

            if (trap.active) {
                val trapCX = trap.x + TRAP_SIZE / 2
                val trapCY = trap.y + TRAP_SIZE / 2
                val dx = birdCX - trapCX
                val dy = birdCY - trapCY
                val radius = BIRD_WIDTH / 2 + TRAP_SIZE / 2
                if (dx * dx + dy * dy < radius * radius) {
                    trap.active = false
                    when (trap.type) {
                        TrapType.NET -> {
                            flappyV *= 0.5f
                            nextFlapFrame = frames + 40 + (Math.random() * 30).toInt()
                        }

                        TrapType.MINE -> finishLevel(birdTrapped = true)
                    }
                }
            }

            if (trap.x + TRAP_SIZE < -50f) {
                iterator.remove()
            }
        }
    }

    private fun checkPipeCollisions() {
        val gap = maxOf(currentPipeGap, MIN_GAP)
        val bx1 = FLAPPY_X + 15f
        val by1 = flappyY + 15f
        val bx2 = FLAPPY_X + BIRD_WIDTH - 15f
        val by2 = flappyY + BIRD_HEIGHT - 15f

        for (pipe in pipes) {
            val topLeft = pipe.x + 5f
            val topRight = pipe.x + PIPE_WIDTH - 5f
            val topBottom = pipe.gapY - gap / 2
            val bottomTop = pipe.gapY + gap / 2
            val bottomRight = pipe.x + PIPE_WIDTH - 5f

            if (bx1 < topRight && bx2 > topLeft && by1 < topBottom) {
                finishLevel(birdTrapped = true)
                return
            }

            if (bx1 < bottomRight && bx2 > topLeft && by2 > bottomTop) {
                finishLevel(birdTrapped = true)
                return
            }
        }
    }

    private fun findNearestPipeAhead(): Pipe? {
        var nearest: Pipe? = null
        var minDist = Float.MAX_VALUE
        for (pipe in pipes) {
            val dist = pipe.x + PIPE_WIDTH / 2 - FLAPPY_X
            if (dist > -50f && dist < minDist) {
                minDist = dist
                nearest = pipe
            }
        }
        return nearest
    }

    private fun findControllablePipe(): Pipe? {
        var nearest: Pipe? = null
        var minDist = Float.MAX_VALUE
        for (pipe in pipes) {
            val dist = pipe.x - FLAPPY_X
            if (dist > -100f && dist < minDist) {
                minDist = dist
                nearest = pipe
            }
        }
        return nearest
    }

    private fun findImminentPipe(): Pipe? {
        var nearest: Pipe? = null
        var minDist = Float.MAX_VALUE
        for (pipe in pipes) {
            val dist = pipe.x - FLAPPY_X
            if (dist in -20f..190f && dist < minDist) {
                minDist = dist
                nearest = pipe
            }
        }
        return nearest
    }

    private fun clampGapCenter(value: Float): Float {
        val gap = maxOf(currentPipeGap, MIN_GAP)
        val minCenter = gap / 2 + SAFE_EDGE_MARGIN
        val maxCenter = V_HEIGHT - gap / 2 - SAFE_EDGE_MARGIN
        return value.coerceIn(minCenter, maxCenter)
    }

    private fun idealGapForPipe(pipe: Pipe): Float {
        val futureBirdY = flappyY + flappyV * 6f
        val drift = ((pipe.x - FLAPPY_X) / 16f).coerceIn(-55f, 55f)
        return clampGapCenter(futureBirdY + BIRD_HEIGHT / 2 + drift)
    }

    private fun randomFlightLane(size: Float): Float {
        val margin = 180f
        val minCenter = margin + size / 2
        val maxCenter = V_HEIGHT - margin - size / 2
        return minCenter + Math.random().toFloat() * (maxCenter - minCenter)
    }

    private fun trapSpawnChance(): Double {
        return when (level.coerceAtMost(8)) {
            3 -> 0.16
            4 -> 0.22
            5 -> 0.28
            6 -> 0.34
            7 -> 0.4
            else -> 0.45
        }
    }

    private fun findRelevantTrapAhead(): Trap? {
        var nearest: Trap? = null
        var minDist = Float.MAX_VALUE
        for (trap in traps) {
            if (!trap.active) continue
            val dist = trap.x - FLAPPY_X
            if (dist in 0f..260f && dist < minDist) {
                minDist = dist
                nearest = trap
            }
        }
        return nearest
    }

    private fun findRelevantWormAhead(): Worm? {
        var nearest: Worm? = null
        var minDist = Float.MAX_VALUE
        for (worm in worms) {
            if (worm.collected) continue
            val dist = worm.x - FLAPPY_X
            if (dist in 0f..220f && dist < minDist) {
                minDist = dist
                nearest = worm
            }
        }
        return nearest
    }

    private fun finishLevel(birdTrapped: Boolean) {
        if (gameState != GameState.STARTED) {
            return
        }

        lastTrapBonus = if (birdTrapped && wormBoostFrames > 0) 5 else 0
        levelResult = if (birdTrapped && pipesThisLevel < targetPipes) {
            LevelResult.COMPLETE
        } else {
            LevelResult.FAILED
        }
        gameState = GameState.GAMEOVER
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        val scaleX = width.toFloat() / V_WIDTH
        val scaleY = height.toFloat() / V_HEIGHT

        canvas.save()
        canvas.scale(scaleX, scaleY)

        val bgOffset = if (gameState != GameState.GAMEOVER) (frames * 3f) % bgWidthCached else 0f
        canvas.drawBitmap(bgImage, -bgOffset, 0f, paint)
        canvas.drawBitmap(bgImage, bgWidthCached - bgOffset, 0f, paint)

        val gap = maxOf(currentPipeGap, MIN_GAP)
        for (pipe in pipes) {
            canvas.drawBitmap(pipeTop, pipe.x, pipe.gapY - gap / 2 - pipeTop.height, paint)
            canvas.drawBitmap(pipeBottom, pipe.x, pipe.gapY + gap / 2, paint)
        }

        for (trap in traps) {
            if (!trap.active) continue
            when (trap.type) {
                TrapType.NET -> {
                    val left = trap.x
                    val top = trap.y
                    val right = trap.x + TRAP_SIZE
                    val bottom = trap.y + TRAP_SIZE
                    val step = TRAP_SIZE / 4f
                    for (i in 0..4) {
                        canvas.drawLine(left + step * i, top, left + step * i, bottom, netPaint)
                        canvas.drawLine(left, top + step * i, right, top + step * i, netPaint)
                    }
                }

                TrapType.MINE -> {
                    val cx = trap.x + TRAP_SIZE / 2
                    val cy = trap.y + TRAP_SIZE / 2
                    val r = TRAP_SIZE * 0.35f
                    canvas.drawCircle(cx, cy, r, minePaint)
                    canvas.drawCircle(cx, cy, r, mineOutlinePaint)
                    canvas.drawLine(cx, cy - r - 8f, cx, cy + r + 8f, mineOutlinePaint)
                    canvas.drawLine(cx - r - 8f, cy, cx + r + 8f, cy, mineOutlinePaint)
                }
            }
        }

        for (worm in worms) {
            val floatOffset = kotlin.math.sin(frames * 0.1).toFloat() * 10f
            val cx = worm.x
            val cy = worm.y + floatOffset
            val r = WORM_SIZE / 2f
            canvas.drawCircle(cx + r * 0.3f, cy + r * 1.3f, r * 0.55f, wormDarkPaint)
            canvas.drawCircle(cx + r * 0.3f, cy + r * 1.3f, r * 0.45f, wormBodyPaint)
            canvas.drawCircle(cx + r * 0.8f, cy + r * 0.9f, r * 0.55f, wormDarkPaint)
            canvas.drawCircle(cx + r * 0.8f, cy + r * 0.9f, r * 0.45f, wormBodyPaint)
            canvas.drawCircle(cx + r * 1.35f, cy + r * 0.6f, r * 0.65f, wormDarkPaint)
            canvas.drawCircle(cx + r * 1.35f, cy + r * 0.6f, r * 0.55f, wormBodyPaint)
            canvas.drawCircle(cx + r * 1.55f, cy + r * 0.35f, r * 0.22f, wormEyePaint)
            canvas.drawCircle(cx + r * 1.6f, cy + r * 0.33f, r * 0.11f, wormPupilPaint)
        }

        val birdBitmap = birdFrames[currentFrame]
        if (birdBitmap != null) {
            canvas.save()
            var rotation = flappyV * 2.5f
            rotation = rotation.coerceIn(-25f, 90f)
            if (gameState == GameState.WAITING) rotation = 0f
            canvas.rotate(rotation, FLAPPY_X + BIRD_WIDTH / 2, flappyY + BIRD_HEIGHT / 2)

            if (wormBoostFrames > 0) {
                val auraR = BIRD_WIDTH * 0.8f + kotlin.math.sin(frames * 0.2).toFloat() * 8f
                canvas.drawCircle(FLAPPY_X + BIRD_WIDTH / 2, flappyY + BIRD_HEIGHT / 2, auraR, boostPaint)
            }

            canvas.drawBitmap(birdBitmap, FLAPPY_X, flappyY, paint)
            canvas.restore()
        }

        if (gameState == GameState.WAITING) {
            customFont?.let { textPaint.typeface = it }
            textPaint.setShadowLayer(8f, 4f, 4f, Color.argb(220, 0, 0, 0))
            textPaint.textSize = 80f
            textPaint.color = Color.WHITE
            canvas.drawText("DRAG TO MOVE PIPES", V_WIDTH / 2, V_HEIGHT * 0.40f, textPaint)
            textPaint.textSize = 60f
            textPaint.color = Color.rgb(255, 220, 70)
            canvas.drawText("TRAP THE BIRD!", V_WIDTH / 2, V_HEIGHT * 0.46f, textPaint)
            textPaint.textSize = 50f
            textPaint.color = Color.WHITE
            canvas.drawText("TAP TO START", V_WIDTH / 2, V_HEIGHT * 0.52f, textPaint)
            textPaint.textSize = 45f
            canvas.drawText("LEVEL $level  -  TARGET: $targetPipes", V_WIDTH / 2, V_HEIGHT * 0.58f, textPaint)
            textPaint.setShadowLayer(5f, 5f, 5f, Color.BLACK)
            textPaint.textSize = 100f
        } else {
            textPaint.textSize = 100f
            textPaint.color = Color.WHITE
            canvas.drawText("ESCAPES: $score", V_WIDTH / 2, 180f, textPaint)

            textPaint.textSize = 50f
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("LVL $level", 30f, 80f, textPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("TARGET $targetPipes", V_WIDTH - 30f, 80f, textPaint)
            textPaint.textAlign = Paint.Align.CENTER

            if (wormBoostFrames > 0) {
                textPaint.textSize = 45f
                textPaint.color = Color.rgb(255, 200, 0)
                canvas.drawText("BIRD BOOSTED", V_WIDTH / 2, 260f, textPaint)
                textPaint.color = Color.WHITE
            }

            textPaint.textSize = 100f
        }

        if (gameState == GameState.GAMEOVER) {
            canvas.drawRect(0f, 0f, V_WIDTH, V_HEIGHT, overlayPaint)

            val goX = V_WIDTH / 2f - gameoverImage.width / 2f
            val goY = V_HEIGHT / 2f - gameoverImage.height / 2f - 200f
            canvas.drawBitmap(gameoverImage, goX, goY, paint)

            when (levelResult) {
                LevelResult.COMPLETE -> {
                    textPaint.textSize = 90f
                    textPaint.color = Color.rgb(50, 255, 50)
                    canvas.drawText("LEVEL COMPLETE!", V_WIDTH / 2, V_HEIGHT / 2 + 30f, textPaint)
                    textPaint.textSize = 55f
                    textPaint.color = Color.WHITE
                    canvas.drawText("Bird trapped after $score escapes", V_WIDTH / 2, V_HEIGHT / 2 + 110f, textPaint)
                    if (lastTrapBonus > 0) {
                        textPaint.textSize = 45f
                        textPaint.color = Color.rgb(255, 200, 0)
                        canvas.drawText("WORM BONUS +$lastTrapBonus", V_WIDTH / 2, V_HEIGHT / 2 + 180f, textPaint)
                    }
                }

                LevelResult.FAILED -> {
                    textPaint.textSize = 90f
                    textPaint.color = Color.rgb(255, 80, 80)
                    canvas.drawText("LEVEL FAILED", V_WIDTH / 2, V_HEIGHT / 2 + 30f, textPaint)
                    textPaint.textSize = 55f
                    textPaint.color = Color.WHITE
                    canvas.drawText("Bird escaped $score times", V_WIDTH / 2, V_HEIGHT / 2 + 110f, textPaint)
                }

                LevelResult.NONE -> Unit
            }

            textPaint.textSize = 60f
            textPaint.color = Color.rgb(255, 215, 0)
            canvas.drawText("LEVEL $level", V_WIDTH / 2, V_HEIGHT / 2 + 260f, textPaint)

            if (frames % 60 < 30) {
                textPaint.textSize = 70f
                textPaint.color = Color.WHITE
                val tapMsg = if (levelResult == LevelResult.COMPLETE) "TAP FOR NEXT LEVEL" else "TAP TO RETRY"
                canvas.drawText(tapMsg, V_WIDTH / 2, V_HEIGHT / 2 + 350f, textPaint)
            }
        }

        textPaint.textSize = 100f
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER

        canvas.restore()
    }
}
