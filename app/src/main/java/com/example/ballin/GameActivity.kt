package com.example.ballin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import com.example.ballin.model.Ball
import com.example.ballin.model.Cell
import com.example.ballin.model.CellType
import com.example.ballin.model.CollisionHandler
import com.example.ballin.model.Level
import com.example.ballin.model.LevelManager
import com.example.ballin.model.ObstacleType
import com.example.ballin.model.SoundManager
import com.example.ballin.ui.GameScreen
import com.example.ballin.ui.PauseScreen
import com.example.ballin.ui.theme.BallinTheme

class GameActivity : ComponentActivity(), SensorEventListener {

    private val gameThread = HandlerThread("GameThread").apply { start() }
    private val gameHandler = Handler(gameThread.looper)
    private val frameInterval = 16L // 60 FPS (16 ms)
    private var isRunning = true

    private lateinit var levelManager: LevelManager
    private lateinit var sensorManager: SensorManager
    private lateinit var collisionHandler: CollisionHandler
    private lateinit var soundManager: SoundManager
    private lateinit var backgroundMusic: MediaPlayer

    private var gyroscopeSensor: Sensor? = null
    private var ball by mutableStateOf(Ball(x = 0f, y = 0f, dx = 0f, dy = 0f, radius = 50f))
    @Volatile private var rotationX = 0f
    @Volatile private var rotationY = 0f

    private var score by mutableLongStateOf(0L)
    private var isPaused by mutableStateOf(false)
    private var useCameraBackground by mutableStateOf(false)

    private val gravityFactor = 0.5f
    private val dampingFactor = 0.999f

    private val gridWidth = 6
    private val gridHeight = 12
    private var cellSize by mutableFloatStateOf(0f)

    private var themeColor by mutableIntStateOf(Color.Transparent.toArgb())

    private var lightSensor: Sensor? = null
    private var lightLevel by mutableFloatStateOf(0f)

    private var levelStartTime: Long = 0L
    private var accumulatedTime: Long = 0L

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                setupCamera()
            } else {
                Log.e("GameActivity", "Kamera nie została przyznana")
            }
        }


    private val grid: Array<Array<Cell>> = Array(gridHeight) {
        Array(gridWidth) { Cell() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Sprawdzenie uprawnień
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setupCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }


        // Wczytanie ID poziomu
        val levelId = intent.getIntExtra("LEVEL_ID", -1)
        if (levelId == -1) {
            finish()
            return
        }

        // Inicjalizacja levelManagera
        levelManager = LevelManager(this).apply {
            loadLevelsFromJson("levels.json")
        }

        // Obliczanie rozmiaru komórek
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val screenHeight = resources.displayMetrics.heightPixels.toFloat()
        cellSize = minOf(screenWidth / gridWidth, screenHeight / gridHeight)

        // Wczytanie poziomu
        val currentLevel = levelManager.getLevelById(levelId)
        if (currentLevel != null) {
            setupLevel(currentLevel)
            levelManager.setCurrentLevelById(currentLevel.id)
            updateThemeColor()
        } else {
            finish()
        }

        // Inicjalizacja sensorów
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        // Obsługa muzyki
        soundManager = SoundManager(this)

        val afd = assets.openFd("bg-synth.wav")
        backgroundMusic = MediaPlayer().apply {
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            isLooping = true
            prepare()
        }

        collisionHandler = CollisionHandler(ball, grid, cellSize, soundManager)
        startGameLoop()

        // Odczyt koloru z SharedPreferences
        val sharedPreferences = getSharedPreferences("GamePreferences", MODE_PRIVATE)
        val selectedBallDrawable = sharedPreferences.getInt("selected_ball_drawable", R.drawable.benson)
        val ballDrawable = AppCompatResources.getDrawable(this, selectedBallDrawable)
        ball.drawable = ballDrawable

        levelManager.addLevelChangeListener {
            updateThemeColor()
        }
        enableEdgeToEdge()
        window.statusBarColor = Color.Transparent.toArgb()

        // Ustawienie interfejsu
        setContent {
            BallinTheme {
                if (isPaused) {
                    PauseScreen(
                        onResumeClick = { resumeGame() },
                        onExitClick = {

                            val intent = Intent(this@GameActivity, MainActivity::class.java)
                            startActivity(intent)
                            finish()
                        },
                        onToggleCameraClick = { useCameraBackground = !useCameraBackground },
                        isCameraEnabled = useCameraBackground
                    )
                } else {
                    GameScreen(
                        ball = ball,
                        score = score,
                        grid = grid,
                        originalCellSize = cellSize,
                        onPauseClick = { pauseGame() },
                        useCameraBackground = useCameraBackground,
                        themeColor = themeColor,
                        selectedBallResource = selectedBallDrawable
                    )
                }
            }
        }
    }

    private fun updateThemeColor() {
        val nextLevel = levelManager.getCurrentLevel()

        // Pobieranie themeColor z poziomu
        val baseColor = nextLevel?.themeColor ?: Color.Transparent.toArgb()

        // Dostosowanie jasność koloru
        themeColor = adjustColorBrightness(baseColor, lightLevel)

        Log.d(
            "GameActivity",
            "Updated themeColor for level ${nextLevel?.id}: ${Integer.toHexString(themeColor)}"
        )
    }

    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            val previewView = PreviewView(this).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            preview.surfaceProvider = previewView.surfaceProvider
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupLevel(level: Level) {
        for (row in grid.indices) {
            for (col in grid[row].indices) {
                grid[row][col].type = CellType.EMPTY
            }
        }

        // Sprawdzenie startPosition
        if (level.startPosition.x !in 0 until gridWidth || level.startPosition.y !in 0 until gridHeight) {
            throw IllegalArgumentException("Start position out of bounds: ${level.startPosition}")
        }

        // Sprawdzenie goalPosition
        if (level.goalPosition.x !in 0 until gridWidth || level.goalPosition.y !in 0 until gridHeight) {
            throw IllegalArgumentException("Goal position out of bounds: ${level.goalPosition}")
        }

        ball.x = level.startPosition.x * cellSize + cellSize / 2
        ball.y = level.startPosition.y * cellSize + cellSize / 2
        grid[level.goalPosition.y][level.goalPosition.x].type = CellType.GOAL

        // Sprawdzenie zakresów przeszkód
        level.obstacles.forEach { obstacle ->
            if (obstacle.x !in 0 until gridWidth || obstacle.y !in 0 until gridHeight) {
                Log.e("GameActivity", "Obstacle out of bounds: $obstacle")
                return@forEach
            }

            when (obstacle.type) {
                ObstacleType.RECTANGLE -> grid[obstacle.y][obstacle.x].type = CellType.OBSTACLE_RECTANGLE
                ObstacleType.CIRCLE -> grid[obstacle.y][obstacle.x].type = CellType.OBSTACLE_CIRCLE
            }
        }

        grid[level.startPosition.y][level.startPosition.x].type = CellType.START
        accumulatedTime = 0L
        levelStartTime = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        backgroundMusic.start()
        if (!isPaused) {
            gyroscopeSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            lightSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        backgroundMusic.pause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isPaused && event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
            synchronized(this) {
                rotationX = event.values[1]
                rotationY = event.values[0]
            }
        }
        else if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
            lightLevel = event.values[0]
            Log.d("GameActivity", "Light sensor changed: $lightLevel lx")
            val currentLevelColor = levelManager.getCurrentLevel()?.themeColor ?: Color.White.toArgb()
            themeColor = adjustColorBrightness(currentLevelColor, lightLevel)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun adjustColorBrightness(baseColor: Int, lightLevel: Float): Int {
        val color = Color(baseColor)
        val brightnessFactor = when {
            lightLevel > 10000 -> 1.2f
            lightLevel > 5000 -> 1.1f
            lightLevel > 1000 -> 0.9f
            else -> 0.7f
        }

        val red = (color.red * brightnessFactor).coerceIn(0f, 1f)
        val green = (color.green * brightnessFactor).coerceIn(0f, 1f)
        val blue = (color.blue * brightnessFactor).coerceIn(0f, 1f)

        val adjustedColor = Color(red, green, blue).toArgb()
        Log.d("AdjustColor", "BaseColor: ${Integer.toHexString(baseColor)}, LightLevel: $lightLevel, " +
                "BrightnessFactor: $brightnessFactor, AdjustedColor: ${Integer.toHexString(adjustedColor)}")
        return adjustedColor
    }

    private fun onLevelComplete() {
        val currentTimeSpent = calculateScore()
        val currentLevelId = levelManager.getCurrentLevel()?.id
        if (currentLevelId != null) {
            updateBestTimeForLevel(currentLevelId, currentTimeSpent)
        }

        levelManager.nextLevel()
        val nextLevel = levelManager.getCurrentLevel()
        if (nextLevel != null) {
            setupLevel(nextLevel)
            collisionHandler.refresh()
            updateThemeColor()
        } else {
            val intent = Intent(this@GameActivity, MainActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    private fun startGameLoop() {
        gameHandler.post(object : Runnable {
            override fun run() {
                if (isRunning) {
                    updateGame()
                    gameHandler.postDelayed(this, frameInterval)
                }
            }
        })
    }

    private fun updateGame() {
        if (isPaused) return

        val (rotX, rotY) = synchronized(this) {
            val tmp = rotationX to rotationY
            rotationX = 0f
            rotationY = 0f
            tmp
        }

        ball.dx = (ball.dx + rotX * gravityFactor) * dampingFactor
        ball.dy = (ball.dy + rotY * gravityFactor) * dampingFactor
        ball.updatePosition(gridWidth, gridHeight, cellSize)

        collisionHandler.checkCollision {
            runOnUiThread { onLevelComplete() }
        }

        runOnUiThread {
            score = calculateScore()
        }
    }

    private fun pauseGame() {
        isPaused = true
        sensorManager.unregisterListener(this)
        if (levelStartTime > 0) {
            accumulatedTime += System.currentTimeMillis() - levelStartTime
        }
        levelStartTime = 0L
    }

    private fun resumeGame() {
        isPaused = false
        gyroscopeSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        levelStartTime = System.currentTimeMillis()
    }

    private fun calculateScore(): Long {
        val currentRunningTime = if (levelStartTime > 0) {
            System.currentTimeMillis() - levelStartTime
        } else {
            0L
        }
        return accumulatedTime + currentRunningTime
    }

    private fun updateBestTimeForLevel(levelId: Int, currentTime: Long) {
        val sharedPreferences = getSharedPreferences("GamePreferences", MODE_PRIVATE)
        val key = "best_time_level_$levelId"
        val oldBestTime = sharedPreferences.getLong(key, Long.MAX_VALUE)
        if (currentTime < oldBestTime) {
            sharedPreferences.edit().putLong(key, currentTime).apply()
            Log.d("GameActivity", "Nowy rekord dla poziomu $levelId: $currentTime ms")
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        backgroundMusic.release()
        soundManager.release()
    }
}
