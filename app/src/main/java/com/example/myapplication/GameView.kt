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
    private val PIPE_GAP = 350f
    private val PIPE_SPEED = 7f
    
    // Graphics
    private val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private val textPaint = Paint()

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
        score = 0
        frames = 0
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
            
            // Generate pipes
            if (frames % 90 == 0) { // Every 1.5 seconds at 60fps
                val minGapCenter = 400f
                val maxGapCenter = V_HEIGHT - 400f
                val gapY = minGapCenter + Math.random().toFloat() * (maxGapCenter - minGapCenter)
                pipes.add(Pipe(V_WIDTH + 100f, gapY))
            }

            // Move pipes and check score
            val iterator = pipes.iterator()
            while (iterator.hasNext()) {
                val pipe = iterator.next()
                
                // Score trigger
                if (!pipe.passed && FLAPPY_X > pipe.x + PIPE_WIDTH) {
                    score++
                    pipe.passed = true
                    if (score > highscore) highscore = score
                }
                
                pipe.x -= PIPE_SPEED
                
                if (pipe.x + PIPE_WIDTH < -100f) {
                    iterator.remove()
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
            
            for (pipe in pipes) {
                val tx1 = pipe.x + 5f
                val ty1 = 0f
                val tx2 = pipe.x + PIPE_WIDTH - 5f
                val ty2 = pipe.gapY - PIPE_GAP / 2
                
                val btx1 = pipe.x + 5f
                val bty1 = pipe.gapY + PIPE_GAP / 2
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
        for (pipe in pipes) {
            canvas.drawBitmap(pipeTop, pipe.x, pipe.gapY - PIPE_GAP / 2 - pipeTop.height, paint)
            canvas.drawBitmap(pipeBottom, pipe.x, pipe.gapY + PIPE_GAP / 2, paint)
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
            textPaint.textSize = 120f
            canvas.drawText(score.toString(), V_WIDTH / 2, 200f, textPaint)
            textPaint.textSize = 60f
            canvas.drawText("HI $highscore", V_WIDTH / 2, 280f, textPaint)
            textPaint.textSize = 100f
        }

        if (gameState == GameState.GAMEOVER) {
            val goX = V_WIDTH / 2f - gameoverImage.width / 2f
            val goY = V_HEIGHT / 2f - gameoverImage.height / 2f - 100f
            canvas.drawBitmap(gameoverImage, goX, goY, paint)
            
            if (frames % 60 < 30) {
                canvas.drawText("TAP TO RESTART", V_WIDTH / 2, V_HEIGHT / 2 + 100f, textPaint)
            }
        }
        
        canvas.restore()
    }
}
