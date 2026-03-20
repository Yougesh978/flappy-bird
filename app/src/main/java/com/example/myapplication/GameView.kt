package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private var thread: GameThread
    
    // Assets
    private var bgImage: Bitmap
    private var birdFrames = arrayOfNulls<Bitmap>(3)
    private lateinit var pipeTop: Bitmap
    private lateinit var pipeBottom: Bitmap
    private var gameoverImage: Bitmap
    private var customFont: Typeface? = null
    
    // Virtual resolution (logic uses this, drawn scaled to screen)
    private val V_WIDTH = 1000f
    private val V_HEIGHT = 1800f
    private var bgWidthCached = 0f
    
    // Game State
    enum class GameState { WAITING, STARTED, GAMEOVER }
    private var gameState = GameState.WAITING
    private var frames = 0
    private var score = 0
    private var highscore = 0
    
    // Level System
    private var level = 1
    private var levelUpTimer = 0  // frames remaining to show "LEVEL UP!"
    
    // Difficulty computed from level (capped at level 6)
    private val currentPipeSpeed: Float
        get() = when (level.coerceAtMost(6)) {
            1 -> 6f; 2 -> 7f; 3 -> 8f; 4 -> 9.5f; 5 -> 11f; else -> 12f
        }
    private val currentPipeGap: Float
        get() = when (level.coerceAtMost(6)) {
            1 -> 420f; 2 -> 380f; 3 -> 340f; 4 -> 310f; 5 -> 280f; else -> 250f
        }
    private val currentSpawnInterval: Int
        get() = when (level.coerceAtMost(6)) {
            1 -> 100; 2 -> 90; 3 -> 80; 4 -> 72; 5 -> 64; else -> 56
        }
    
    // Flappy
    private var flappyY = V_HEIGHT / 2
    private var flappyV = 0f
    private var currentFrame = 0
    private val BIRD_WIDTH = 100f
    private val BIRD_HEIGHT = 70f
    private val FLAPPY_X = 250f
    
    // Pipes
    class Pipe(var x: Float, var gapY: Float, var passed: Boolean = false)
    private val pipes = ArrayList<Pipe>()
    private val PIPE_WIDTH = 120f
    
    // Worms (collectibles)
    private val WORM_SIZE = 60f
    class Worm(var x: Float, var y: Float, var collected: Boolean = false)
    private val worms = ArrayList<Worm>()
    
    // Graphics
    private val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private val textPaint = Paint()
    // Worm draw paints (pre-allocated, no GC in draw loop)
    private val wormBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 105, 150) }
    private val wormDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 60, 100) }
    private val wormEyePaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val wormPupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    init {
        holder.addCallback(this)
        thread = GameThread(holder, this)
        
        // Load Bitmaps
        val assetManager = context.assets
        
        // Load original background
        val rawBg = BitmapFactory.decodeStream(assetManager.open("images/background.png"))
        
        // Pre-scale background ONCE to V_HEIGHT to prevent massive performance drops on draw()
        val bgScale = V_HEIGHT / rawBg.height.toFloat()
        val targetBgWidth = (rawBg.width * bgScale).toInt()
        bgImage = Bitmap.createScaledBitmap(rawBg, targetBgWidth, V_HEIGHT.toInt(), true)
        rawBg.recycle() // Free memory
        
        bgWidthCached = targetBgWidth.toFloat()
        
        // Pre-scale Bird
        val rawF1 = BitmapFactory.decodeStream(assetManager.open("images/flappy1.png"))
        birdFrames[0] = Bitmap.createScaledBitmap(rawF1, BIRD_WIDTH.toInt(), BIRD_HEIGHT.toInt(), true)
        rawF1.recycle()
        
        val rawF2 = BitmapFactory.decodeStream(assetManager.open("images/flappy2.png"))
        birdFrames[1] = Bitmap.createScaledBitmap(rawF2, BIRD_WIDTH.toInt(), BIRD_HEIGHT.toInt(), true)
        rawF2.recycle()
        
        val rawF3 = BitmapFactory.decodeStream(assetManager.open("images/flappy3.png"))
        birdFrames[2] = Bitmap.createScaledBitmap(rawF3, BIRD_WIDTH.toInt(), BIRD_HEIGHT.toInt(), true)
        rawF3.recycle()

        // Pre-scale Pipes — use full V_HEIGHT so pipes always cover the entire screen
        val rawPipe = BitmapFactory.decodeStream(assetManager.open("images/pipe-red.png"))
        pipeBottom = Bitmap.createScaledBitmap(rawPipe, PIPE_WIDTH.toInt(), V_HEIGHT.toInt(), true)
        
        val matrix = android.graphics.Matrix()
        matrix.postScale(1f, -1f)
        pipeTop = Bitmap.createBitmap(pipeBottom, 0, 0, pipeBottom.width, pipeBottom.height, matrix, true)
        rawPipe.recycle()

        // Pre-scale Game Over
        val rawGo = BitmapFactory.decodeStream(assetManager.open("images/gameover.png"))
        val goW = (rawGo.width * 2f).toInt()
        val goH = (rawGo.height * 2f).toInt()
        gameoverImage = Bitmap.createScaledBitmap(rawGo, goW, goH, true)
        rawGo.recycle()
        
        try {
            customFont = Typeface.createFromAsset(assetManager, "fonts/TiltPrism-Regular.ttf")
        } catch (e: Exception) {
            e.printStackTrace()
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

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        var retry = true
        thread.running = false
        while (retry) {
            try {
                thread.join()
                retry = false
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            when (gameState) {
                GameState.WAITING -> {
                    gameState = GameState.STARTED
                    flappyV = -18f
                }
                GameState.STARTED -> {
                    flappyV = -18f
                }
                GameState.GAMEOVER -> {
                    gameState = GameState.WAITING
                    resetGame()
                }
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun resetGame() {
        flappyY = V_HEIGHT / 2
        flappyV = 0f
        pipes.clear()
        worms.clear()
        score = 0
        frames = 0
        level = 1
        levelUpTimer = 0
    }

    fun update() {
        frames++
        
        // Flap animation
        if (gameState == GameState.WAITING || gameState == GameState.STARTED) {
            if (frames % 6 == 0) {
                currentFrame = (currentFrame + 1) % 3
            }
        }

        if (gameState == GameState.STARTED) {
            flappyY += flappyV
            flappyV += 0.8f // Gravity
            
            // Level-up timer countdown
            if (levelUpTimer > 0) levelUpTimer--
            
            // Generate pipes based on current level's spawn interval
            if (frames % currentSpawnInterval == 0) {
                val gap = currentPipeGap
                val minGapCenter = gap / 2 + 100f
                val maxGapCenter = V_HEIGHT - gap / 2 - 100f
                val gapY = minGapCenter + Math.random().toFloat() * (maxGapCenter - minGapCenter)
                pipes.add(Pipe(V_WIDTH + 100f, gapY))
                
                // 40% chance to spawn a worm in the gap
                if (Math.random() < 0.4) {
                    worms.add(Worm(V_WIDTH + 100f + PIPE_WIDTH / 2 - WORM_SIZE / 2, gapY - WORM_SIZE / 2))
                }
            }

            // Move pipes and check score
            val speed = currentPipeSpeed
            val iterator = pipes.iterator()
            while (iterator.hasNext()) {
                val pipe = iterator.next()
                
                // Score trigger + level check
                if (!pipe.passed && FLAPPY_X > pipe.x + PIPE_WIDTH) {
                    score++
                    pipe.passed = true
                    if (score > highscore) highscore = score
                    
                    // Level up every 5 points
                    val newLevel = (score / 5) + 1
                    if (newLevel > level) {
                        level = newLevel
                        levelUpTimer = 120 // show "LEVEL UP!" for 2 seconds
                    }
                }
                
                pipe.x -= speed
                
                if (pipe.x + PIPE_WIDTH < -100f) {
                    iterator.remove()
                }
            }
            
            // Move worms and check collection
            val birdCX = FLAPPY_X + BIRD_WIDTH / 2
            val birdCY = flappyY + BIRD_HEIGHT / 2
            val wormIterator = worms.iterator()
            while (wormIterator.hasNext()) {
                val worm = wormIterator.next()
                worm.x -= speed
                
                // Check if bird collected the worm (distance-based)
                if (!worm.collected) {
                    val wormCX = worm.x + WORM_SIZE / 2
                    val wormCY = worm.y + WORM_SIZE / 2
                    val dx = birdCX - wormCX
                    val dy = birdCY - wormCY
                    if (dx * dx + dy * dy < (BIRD_WIDTH / 2 + WORM_SIZE / 2) * (BIRD_WIDTH / 2 + WORM_SIZE / 2)) {
                        worm.collected = true
                        score += 3
                        if (score > highscore) highscore = score
                        // Check level up from bonus points too
                        val newLevel = (score / 5) + 1
                        if (newLevel > level) {
                            level = newLevel
                            levelUpTimer = 120
                        }
                    }
                }
                
                // Remove off-screen worms
                if (worm.x + WORM_SIZE < -50f || worm.collected) {
                    wormIterator.remove()
                }
            }

            // Collision check manually without object allocation
            val bx1 = FLAPPY_X + 15f
            val by1 = flappyY + 15f
            val bx2 = FLAPPY_X + BIRD_WIDTH - 15f
            val by2 = flappyY + BIRD_HEIGHT - 15f
            
            if (flappyY > V_HEIGHT || flappyY < 0) {
                gameState = GameState.GAMEOVER
            }
            
            val gap = currentPipeGap
            for (pipe in pipes) {
                val tx1 = pipe.x + 5f
                val ty1 = 0f
                val tx2 = pipe.x + PIPE_WIDTH - 5f
                val ty2 = pipe.gapY - gap / 2
                
                val btx1 = pipe.x + 5f
                val bty1 = pipe.gapY + gap / 2
                val btx2 = pipe.x + PIPE_WIDTH - 5f
                val bty2 = V_HEIGHT
                
                if (bx1 < tx2 && bx2 > tx1 && by1 < ty2 && by2 > ty1) {
                    gameState = GameState.GAMEOVER
                }
                if (bx1 < btx2 && bx2 > btx1 && by1 < bty2 && by2 > bty1) {
                    gameState = GameState.GAMEOVER
                }
            }
        }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        
        val scaleX = width.toFloat() / V_WIDTH
        val scaleY = height.toFloat() / V_HEIGHT
        
        canvas.save()
        canvas.scale(scaleX, scaleY)
        
        // Draw Pre-Scaled Background
        val bgOffset = if (gameState != GameState.GAMEOVER) (frames * 3f) % bgWidthCached else 0f
        
        // Draw 2 times to create infinite scrolling
        canvas.drawBitmap(bgImage, -bgOffset, 0f, paint)
        canvas.drawBitmap(bgImage, bgWidthCached - bgOffset, 0f, paint)

        // Draw Pipes
        val gap = currentPipeGap
        for (pipe in pipes) {
            canvas.drawBitmap(pipeTop, pipe.x, pipe.gapY - gap / 2 - pipeTop.height, paint)
            canvas.drawBitmap(pipeBottom, pipe.x, pipe.gapY + gap / 2, paint)
        }
        
        // Draw Worms using Canvas shapes (no bitmap = no background issue)
        for (worm in worms) {
            val floatOffset = Math.sin((frames * 0.1).toDouble()).toFloat() * 10f
            val cx = worm.x
            val cy = worm.y + floatOffset
            val r = WORM_SIZE / 2f
            // Body segments
            canvas.drawCircle(cx + r * 0.3f, cy + r * 1.3f, r * 0.55f, wormDarkPaint)
            canvas.drawCircle(cx + r * 0.3f, cy + r * 1.3f, r * 0.45f, wormBodyPaint)
            canvas.drawCircle(cx + r * 0.8f, cy + r * 0.9f, r * 0.55f, wormDarkPaint)
            canvas.drawCircle(cx + r * 0.8f, cy + r * 0.9f, r * 0.45f, wormBodyPaint)
            // Head
            canvas.drawCircle(cx + r * 1.35f, cy + r * 0.6f, r * 0.65f, wormDarkPaint)
            canvas.drawCircle(cx + r * 1.35f, cy + r * 0.6f, r * 0.55f, wormBodyPaint)
            // Eye
            canvas.drawCircle(cx + r * 1.55f, cy + r * 0.35f, r * 0.22f, wormEyePaint)
            canvas.drawCircle(cx + r * 1.6f, cy + r * 0.33f, r * 0.11f, wormPupilPaint)
        }

        // Draw Bird
        val bmap = birdFrames[currentFrame]
        if (bmap != null) {
            canvas.save()
            var rotation = flappyV * 2.5f
            if (rotation < -25f) rotation = -25f
            if (rotation > 90f) rotation = 90f
            if (gameState == GameState.WAITING) rotation = 0f
            canvas.rotate(rotation, FLAPPY_X + BIRD_WIDTH / 2, flappyY + BIRD_HEIGHT / 2)
            canvas.drawBitmap(bmap, FLAPPY_X, flappyY, paint)
            canvas.restore()
        }

        // Draw Texts
        if (gameState == GameState.WAITING) {
            canvas.drawText("TAP TO START", V_WIDTH / 2, V_HEIGHT / 2 - 200f, textPaint)
        } else {
            // Score
            textPaint.textSize = 120f
            textPaint.color = Color.WHITE
            canvas.drawText(score.toString(), V_WIDTH / 2, 200f, textPaint)
            // High score
            textPaint.textSize = 60f
            canvas.drawText("HI $highscore", V_WIDTH / 2, 280f, textPaint)
            // Level indicator
            textPaint.textSize = 50f
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("LVL $level", 30f, 80f, textPaint)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 100f
        }
        
        // Level-up flash
        if (levelUpTimer > 0) {
            val alpha = if (levelUpTimer > 60) 255 else (levelUpTimer * 255 / 60)
            val pulse = 1f + Math.sin((frames * 0.15).toDouble()).toFloat() * 0.08f
            textPaint.textSize = 130f * pulse
            textPaint.color = Color.rgb(255, 215, 0) // Gold
            textPaint.alpha = alpha
            canvas.drawText("LEVEL $level!", V_WIDTH / 2, V_HEIGHT / 2 - 50f, textPaint)
            textPaint.color = Color.WHITE
            textPaint.alpha = 255
            textPaint.textSize = 100f
        }

        if (gameState == GameState.GAMEOVER) {
            val goX = V_WIDTH / 2f - gameoverImage.width / 2f
            val goY = V_HEIGHT / 2f - gameoverImage.height / 2f - 100f
            canvas.drawBitmap(gameoverImage, goX, goY, paint)
            
            // Show level reached
            textPaint.textSize = 70f
            textPaint.color = Color.rgb(255, 215, 0)
            canvas.drawText("LEVEL $level", V_WIDTH / 2, V_HEIGHT / 2 + 60f, textPaint)
            textPaint.color = Color.WHITE
            
            if (frames % 60 < 30) {
                textPaint.textSize = 80f
                canvas.drawText("TAP TO RESTART", V_WIDTH / 2, V_HEIGHT / 2 + 160f, textPaint)
            }
            textPaint.textSize = 100f
        }
        
        canvas.restore()
    }
    
    // Strips green-ish background pixels and makes them transparent
    private fun removeGreenBackground(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val px = pixels[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            // If green is the dominant channel by a clear margin, make transparent
            if (g > r + 40 && g > b + 40) {
                pixels[i] = 0x00000000.toInt() // fully transparent
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }
}
