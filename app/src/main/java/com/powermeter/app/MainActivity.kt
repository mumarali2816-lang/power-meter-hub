package com.powermeter.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// ==========================================
// 1. DATA MODELS
// ==========================================
data class LeaderboardEntry(
    val userId: String = "",
    val username: String = "Athlete",
    val country: String = "Global",
    val score: Int = 0,
    val peakIntensity: Float = 0f,
    val verified: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

data class UserProfile(
    val uid: String = "",
    val email: String = "",
    val username: String = "Guest User",
    val country: String = "US",
    val personalBest: Int = 0,
    val totalSessions: Int = 0,
    val globalRank: Int = 1
)

enum class SessionState { IDLE, RUNNING, PAUSED, STOPPED }

// ==========================================
// 2. MOTION PHYSICS & SENSOR ENGINE
// ==========================================
class MotionSensorEngine(context: Context, private val onMotionUpdate: (Float, Float) -> Unit) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastStrokeTime = 0L
    private var lastSign = 0
    private var emaAcceleration = 0f
    private val alpha = 0.25f // Low-pass filter smoothing

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        val rawZ = if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) event.values[1] - 9.81f else event.values[1]
        val rawX = event.values[0]
        val rawY = event.values[2]
        val rawMag = sqrt(rawX * rawX + rawY * rawY + rawZ * rawZ)

        // Exponential Moving Average to eliminate micro-vibrations
        emaAcceleration = alpha * rawMag + (1 - alpha) * emaAcceleration

        val now = SystemClock.elapsedRealtime()
        val currentSign = if (rawZ > 1.2f) 1 else if (rawZ < -1.2f) -1 else 0

        var motionPowerScore = 0f
        // Anti-cheat: Validate human cadence (strokes between 160ms and 1500ms apart)
        if (currentSign != 0 && currentSign != lastSign) {
            val delta = now - lastStrokeTime
            if (delta in 160..1500) {
                val intensityFactor = (emaAcceleration / 9.81f).coerceIn(0.5f, 5.0f)
                motionPowerScore = (intensityFactor * 180f)
                lastStrokeTime = now
                lastSign = currentSign
            } else if (delta < 160) {
                // High-frequency vibration penalty (electric massagers/artificial shaking)
                motionPowerScore = 0f
            }
        }
        onMotionUpdate(motionPowerScore, emaAcceleration)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

// ==========================================
// 3. VIEWMODEL & BUSINESS LOGIC
// ==========================================
class PowerMeterViewModel : ViewModel() {
    private val _sessionState = MutableStateFlow(SessionState.IDLE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _currentPower = MutableStateFlow(0f)
    val currentPower: StateFlow<Float> = _currentPower.asStateFlow()

    private val _peakIntensity = MutableStateFlow(0f)
    val peakIntensity: StateFlow<Float> = _peakIntensity.asStateFlow()

    private val _totalPoints = MutableStateFlow(0)
    val totalPoints: StateFlow<Int> = _totalPoints.asStateFlow()

    private val _durationSeconds = MutableStateFlow(0)
    val durationSeconds: StateFlow<Int> = _durationSeconds.asStateFlow()

    private val _leaderboard = MutableStateFlow<List<LeaderboardEntry>>(emptyList())
    val leaderboard: StateFlow<List<LeaderboardEntry>> = _leaderboard.asStateFlow()

    private val _userProfile = MutableStateFlow(UserProfile())
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    private val _isDemoMode = MutableStateFlow(false)
    val isDemoMode: StateFlow<Boolean> = _isDemoMode.asStateFlow()

    private var timerJob: Job? = null
    private var powerDecayJob: Job? = null
    private var firebaseAuth: FirebaseAuth? = null
    private var firestore: FirebaseFirestore? = null

    fun initializeBackend(context: Context) {
        try {
            FirebaseApp.initializeApp(context)
            firebaseAuth = FirebaseAuth.getInstance()
            firestore = FirebaseFirestore.getInstance()
            _isDemoMode.value = false
            fetchLeaderboard()
        } catch (e: Exception) {
            _isDemoMode.value = true
            loadDemoLeaderboard()
        }
    }

    fun handleMotion(scoreDelta: Float, rawAcceleration: Float) {
        if (_sessionState.value != SessionState.RUNNING) return

        if (scoreDelta > 0f) {
            val powerEstimate = (scoreDelta * 3.5f).coerceIn(10f, 999f)
            _currentPower.value = powerEstimate
            _totalPoints.value += (powerEstimate / 10).toInt()
            if (rawAcceleration > _peakIntensity.value) {
                _peakIntensity.value = rawAcceleration
            }
        }
    }

    fun startSession() {
        _sessionState.value = SessionState.RUNNING
        startTimer()
        startPowerDecay()
    }

    fun pauseSession() {
        _sessionState.value = SessionState.PAUSED
        timerJob?.cancel()
        powerDecayJob?.cancel()
    }

    fun resumeSession() {
        startSession()
    }

    fun stopSession() {
        _sessionState.value = SessionState.STOPPED
        timerJob?.cancel()
        powerDecayJob?.cancel()
        saveSessionResults()
    }

    fun resetSession() {
        _sessionState.value = SessionState.IDLE
        timerJob?.cancel()
        powerDecayJob?.cancel()
        _currentPower.value = 0f
        _peakIntensity.value = 0f
        _totalPoints.value = 0
        _durationSeconds.value = 0
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_sessionState.value == SessionState.RUNNING) {
                delay(1000L)
                _durationSeconds.value += 1
            }
        }
    }

    private fun startPowerDecay() {
        powerDecayJob?.cancel()
        powerDecayJob = viewModelScope.launch {
            while (_sessionState.value == SessionState.RUNNING) {
                delay(120L)
                if (_currentPower.value > 0f) {
                    _currentPower.value = max(0f, _currentPower.value - 18f)
                }
            }
        }
    }

    private fun saveSessionResults() {
        val finalScore = _totalPoints.value
        val currentBest = _userProfile.value.personalBest
        val newBest = max(finalScore, currentBest)
        _userProfile.value = _userProfile.value.copy(
            personalBest = newBest,
            totalSessions = _userProfile.value.totalSessions + 1
        )

        viewModelScope.launch {
            if (!_isDemoMode.value && firestore != null && firebaseAuth?.currentUser != null) {
                try {
                    val uid = firebaseAuth!!.currentUser!!.uid
                    val entry = LeaderboardEntry(
                        userId = uid,
                        username = _userProfile.value.username,
                        country = _userProfile.value.country,
                        score = newBest,
                        peakIntensity = _peakIntensity.value,
                        verified = true,
                        timestamp = System.currentTimeMillis()
                    )
                    firestore!!.collection("leaderboard").document(uid).set(entry).await()
                    fetchLeaderboard()
                } catch (_: Exception) {}
            } else {
                updateDemoLeaderboard(newBest)
            }
        }
    }

    private fun fetchLeaderboard() {
        viewModelScope.launch {
            try {
                firestore?.collection("leaderboard")
                    ?.orderBy("score", Query.Direction.DESCENDING)
                    ?.limit(100)
                    ?.get()
                    ?.addOnSuccessListener { snapshot ->
                        val list = snapshot.toObjects(LeaderboardEntry::class.java)
                        _leaderboard.value = list
                    }
            } catch (_: Exception) {
                loadDemoLeaderboard()
            }
        }
    }

    private fun loadDemoLeaderboard() {
        _leaderboard.value = listOf(
            LeaderboardEntry("1", "AeroViper", "DE", 18450, 48.2f, true),
            LeaderboardEntry("2", "KineticsGod", "US", 16200, 42.1f, true),
            LeaderboardEntry("3", "TitanRider", "JP", 14980, 39.5f, true),
            LeaderboardEntry("4", "VelocityX", "CA", 13110, 36.2f, true),
            LeaderboardEntry("5", "FlashPulse", "FR", 11250, 31.8f, true)
        )
    }

    private fun updateDemoLeaderboard(userBest: Int) {
        val updated = _leaderboard.value.toMutableList()
        val existingIndex = updated.indexOfFirst { it.userId == "demo_user" }
        val entry = LeaderboardEntry("demo_user", _userProfile.value.username, _userProfile.value.country, userBest, _peakIntensity.value, false)
        if (existingIndex >= 0) {
            updated[existingIndex] = entry
        } else {
            updated.add(entry)
        }
        _leaderboard.value = updated.sortedByDescending { it.score }
    }
}

// ==========================================
// 4. JETPACK COMPOSE UI
// ==========================================
val DarkBg = Color(0xFF0C0F17)
val CardBg = Color(0xFF161B26)
val ElectricBlue = Color(0xFF00E5FF)
val NeonPurple = Color(0xFFB388FF)
val ElectricPink = Color(0xFFFF4081)

class MainActivity : ComponentActivity() {
    private var sensorEngine: MotionSensorEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: PowerMeterViewModel = viewModel()
            LaunchedEffect(Unit) {
                viewModel.initializeBackend(applicationContext)
                sensorEngine = MotionSensorEngine(applicationContext) { scoreDelta, rawAcc ->
                    viewModel.handleMotion(scoreDelta, rawAcc)
                }
            }

            val sessionState by viewModel.sessionState.collectAsState()
            DisposableEffect(sessionState) {
                if (sessionState == SessionState.RUNNING) {
                    sensorEngine?.start()
                } else {
                    sensorEngine?.stop()
                }
                onDispose { sensorEngine?.stop() }
            }

            PowerMeterApp(viewModel)
        }
    }
}

@Composable
fun PowerMeterApp(viewModel: PowerMeterViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        containerColor = DarkBg,
        bottomBar = {
            NavigationBar(containerColor = CardBg) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Bolt, "Challenge", tint = if (selectedTab == 0) ElectricBlue else Color.Gray) },
                    label = { Text("Challenge", color = if (selectedTab == 0) ElectricBlue else Color.Gray) },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Leaderboard, "Ranks", tint = if (selectedTab == 1) ElectricBlue else Color.Gray) },
                    label = { Text("Leaderboard", color = if (selectedTab == 1) ElectricBlue else Color.Gray) },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Person, "Profile", tint = if (selectedTab == 2) ElectricBlue else Color.Gray) },
                    label = { Text("Profile", color = if (selectedTab == 2) ElectricBlue else Color.Gray) },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> ChallengeScreen(viewModel)
                1 -> LeaderboardScreen(viewModel)
                2 -> ProfileScreen(viewModel)
            }
        }
    }
}

@Composable
fun ChallengeScreen(viewModel: PowerMeterViewModel) {
    val state by viewModel.sessionState.collectAsState()
    val power by viewModel.currentPower.collectAsState()
    val peak by viewModel.peakIntensity.collectAsState()
    val totalPoints by viewModel.totalPoints.collectAsState()
    val duration by viewModel.durationSeconds.collectAsState()
    val isDemo by viewModel.isDemoMode.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "POWER METER",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black, color = ElectricBlue)
            )
            Text(
                "GLOBAL POWER CHALLENGE",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp, color = NeonPurple)
            )
            if (isDemo) {
                Surface(
                    color = ElectricPink.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        "Demo / Local Offline Mode",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = ElectricPink,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        CircularPowerMeterGauge(power = power)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            MetricCard(title = "Points", value = "$totalPoints")
            MetricCard(title = "Max G-Force", value = String.format("%.1f G", peak / 9.81f))
            MetricCard(title = "Duration", value = String.format("%02d:%02d", duration / 60, duration % 60))
        }

        Surface(
            color = Color.White.copy(alpha = 0.05f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Notice: Score represents motion estimation, not scientific physical watts. Hold phone firmly to avoid accidents.",
                modifier = Modifier.padding(8.dp),
                color = Color.Gray,
                fontSize = 10.sp,
                textAlign = TextAlign.Center
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            when (state) {
                SessionState.IDLE -> {
                    Button(
                        onClick = { viewModel.startSession() },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(26.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = DarkBg)
                        Spacer(Modifier.width(8.dp))
                        Text("START CHALLENGE", color = DarkBg, fontWeight = FontWeight.Bold)
                    }
                }
                SessionState.RUNNING -> {
                    Button(
                        onClick = { viewModel.pauseSession() },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonPurple)
                    ) {
                        Icon(Icons.Default.Pause, null)
                        Text("Pause")
                    }
                    Button(
                        onClick = { viewModel.stopSession() },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricPink)
                    ) {
                        Icon(Icons.Default.Stop, null)
                        Text("Stop")
                    }
                }
                SessionState.PAUSED -> {
                    Button(
                        onClick = { viewModel.resumeSession() },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Text("Resume")
                    }
                    Button(
                        onClick = { viewModel.stopSession() },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricPink)
                    ) {
                        Icon(Icons.Default.Stop, null)
                        Text("Finish")
                    }
                }
                SessionState.STOPPED -> {
                    Button(
                        onClick = { viewModel.resetSession() },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(25.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = DarkBg)
                        Spacer(Modifier.width(8.dp))
                        Text("NEW SESSION", color = DarkBg, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun CircularPowerMeterGauge(power: Float) {
    val animatedProgress by animateFloatAsState(
        targetValue = (power / 1000f).coerceIn(0f, 1f),
        animationSpec = tween(150),
        label = "Gauge"
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(260.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 22.dp.toPx()
            val diameter = size.minDimension - stroke
            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)

            // Background Track
            drawArc(
                color = Color.DarkGray.copy(alpha = 0.3f),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            // Dynamic Gradient Meter
            drawArc(
                brush = Brush.sweepGradient(listOf(ElectricBlue, NeonPurple, ElectricPink)),
                startAngle = 135f,
                sweepAngle = 270f * animatedProgress,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${power.toInt()}",
                color = Color.White,
                fontSize = 58.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                "MOTION POWER INDEX",
                color = ElectricBlue,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
fun MetricCard(title: String, value: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.width(105.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.Gray, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun LeaderboardScreen(viewModel: PowerMeterViewModel) {
    val list by viewModel.leaderboard.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("GLOBAL RANKINGS", style = MaterialTheme.typography.titleLarge.copy(color = ElectricBlue, fontWeight = FontWeight.Bold))
        Text("Verified world motion power records", style = MaterialTheme.typography.labelSmall.copy(color = Color.Gray))
        Spacer(Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(list) { index, entry ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(32.dp).background(
                                    when (index) {
                                        0 -> Color(0xFFFFD700)
                                        1 -> Color(0xFFC0C0C0)
                                        2 -> Color(0xFFCD7F32)
                                        else -> Color.DarkGray
                                    },
                                    CircleShape
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("${index + 1}", color = DarkBg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(entry.username, color = Color.White, fontWeight = FontWeight.Bold)
                                    if (entry.verified) {
                                        Spacer(Modifier.width(4.dp))
                                        Icon(Icons.Default.CheckCircle, "Verified", tint = ElectricBlue, modifier = Modifier.size(14.dp))
                                    }
                                }
                                Text(entry.country, color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                        Text("${entry.score} pts", color = NeonPurple, fontWeight = FontWeight.Black, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(viewModel: PowerMeterViewModel) {
    val profile by viewModel.userProfile.collectAsState()
    val isDemo by viewModel.isDemoMode.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(90.dp).background(Brush.linearGradient(listOf(ElectricBlue, NeonPurple)), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Person, contentDescription = null, tint = DarkBg, modifier = Modifier.size(50.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(profile.username, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(if (isDemo) "Mode: Local Guest" else profile.email, color = Color.Gray, fontSize = 13.sp)

        Spacer(Modifier.height(24.dp))

        Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ATHLETE STATISTICS", color = ElectricBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                HorizontalDivider(color = Color.DarkGray)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Personal Best Score", color = Color.Gray)
                    Text("${profile.personalBest} pts", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total Sessions Run", color = Color.Gray)
                    Text("${profile.totalSessions}", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Global Division", color = Color.Gray)
                    Text("Division 1 (${profile.country})", color = NeonPurple, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
