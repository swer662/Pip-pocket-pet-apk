package com.pippocket.pet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlin.math.*
import kotlin.random.Random

enum class PipScreen(val title: String, val icon: ImageVector) {
    FACE("ПИП", Icons.Default.Face),
    CARE("УХОД", Icons.Default.Favorite),
    SOUND("ШУМОМЕР", Icons.Default.GraphicEq),
    BADGE_QR("БЕЙДЖ & QR", Icons.Default.QrCode2),
    TOOLS("УТИЛИТЫ", Icons.Default.Build),
    GAMES("ИГРЫ", Icons.Default.SportsEsports),
    DEBUG("ОТЛАДКА", Icons.Default.BugReport)
}

@Composable
fun PipPocketPetApp(
    viewModel: PetViewModel,
    onSetMaxBrightness: (Boolean) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val telemetry by viewModel.sensorHub.telemetry.collectAsState()

    var currentScreen by remember { mutableStateOf(PipScreen.FACE) }
    var showRadialWheel by remember { mutableStateOf(false) }
    var currentFps by remember { mutableIntStateOf(60) }

    // При выходе из утилит сбрасываем максимальную яркость фонарика
    LaunchedEffect(currentScreen) {
        if (currentScreen != PipScreen.TOOLS) {
            onSetMaxBrightness(false)
        }
    }

    // Если телефон перевернут экраном вниз — гасим экран в чистый AMOLED #000000 для экономии и сна
    if (telemetry.isFaceDown) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Zzz... (Экран в режиме сна FaceDown)",
                color = Color(0xFF263238),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Верхняя телеметрическая строка в стиле ретро-терминала
            RetroTopStatusBar(
                uiState = uiState,
                telemetry = telemetry,
                fps = currentFps,
                onOpenRadial = {
                    viewModel.haptics.tick()
                    showRadialWheel = !showRadialWheel
                }
            )

            // Основной контент активного экрана
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (currentScreen) {
                    PipScreen.FACE -> MainFaceScreen(
                        uiState = uiState,
                        telemetry = telemetry,
                        onPokeEye = viewModel::onEyePoked,
                        onPetStroke = viewModel::onPetStroked,
                        onDoubleTap = viewModel::onDoubleTapLove,
                        onFeedQuick = viewModel::feedPet,
                        onSleepQuick = viewModel::toggleSleepManual,
                        onOpenGames = { currentScreen = PipScreen.GAMES },
                        onFpsUpdate = { currentFps = it }
                    )
                    PipScreen.CARE -> PetCareScreen(uiState = uiState, viewModel = viewModel)
                    PipScreen.SOUND -> SoundMeterScreen(
                        uiState = uiState,
                        telemetry = telemetry,
                        onResetPeak = { viewModel.sensorHub.resetPeakDb() }
                    )
                    PipScreen.BADGE_QR -> BadgeAndQrScreen(
                        badge = uiState.badge,
                        onUpdateBadge = viewModel::updateBadge
                    )
                    PipScreen.TOOLS -> ToolsScreen(
                        uiState = uiState,
                        haptics = viewModel.haptics,
                        onSetMaxBrightness = onSetMaxBrightness
                    )
                    PipScreen.GAMES -> MiniGamesScreen(
                        uiState = uiState,
                        telemetry = telemetry,
                        viewModel = viewModel
                    )
                    PipScreen.DEBUG -> DebugDrawerScreen(
                        uiState = uiState,
                        telemetry = telemetry,
                        fps = currentFps,
                        viewModel = viewModel
                    )
                }
            }

            // Нижний док быстрого переключения экранов
            RetroBottomNavBar(
                current = currentScreen,
                onSelect = {
                    viewModel.haptics.tick()
                    currentScreen = it
                    showRadialWheel = false
                }
            )
        }

        // Круглое радиальное меню в стиле круглого экрана приборки
        AnimatedVisibility(
            visible = showRadialWheel,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            RadialWheelMenuOverlay(
                currentScreen = currentScreen,
                onSelectScreen = {
                    viewModel.haptics.tick()
                    currentScreen = it
                    showRadialWheel = false
                },
                onDismiss = { showRadialWheel = false }
            )
        }
    }
}

@Composable
private fun RetroTopStatusBar(
    uiState: PetUiState,
    telemetry: SensorTelemetry,
    fps: Int,
    onOpenRadial: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF070B10))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (uiState.currentMood.isReaction) Color(0xFFFF3366) else Color(0xFF00E676))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = uiState.currentMood.titleRu.uppercase(),
                color = Color(0xFF00F0FF),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 175.dp)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "${telemetry.soundDb.toInt()}dB",
                color = if (telemetry.soundDb > 75f) Color(0xFFFF3366) else Color(0xFF90A4AE),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Text(
                text = if (telemetry.isCharging) "⚡${telemetry.batteryPct}%" else "🔋${telemetry.batteryPct}%",
                color = if (telemetry.batteryPct <= 20) Color(0xFFFF3366) else Color(0xFF00E676),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Text(
                text = "${fps}FPS",
                color = Color(0xFFFFB300),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            IconButton(
                onClick = onOpenRadial,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Adjust,
                    contentDescription = "Радиальное меню",
                    tint = Color(0xFF00F0FF)
                )
            }
        }
    }
}

@Composable
private fun RetroBottomNavBar(
    current: PipScreen,
    onSelect: (PipScreen) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF070B10))
            .border(1.dp, Color(0xFF1A2634))
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        PipScreen.entries.forEach { screen ->
            val selected = screen == current
            val tint = if (selected) Color(0xFF00F0FF) else Color(0xFF607D8B)
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(screen) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = screen.icon,
                    contentDescription = screen.title,
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = screen.title,
                    color = tint,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

/**
 * Радиальное колесное меню в стиле круглого экрана Pip-KeyPet
 */
@Composable
private fun RadialWheelMenuOverlay(
    currentScreen: PipScreen,
    onSelectScreen: (PipScreen) -> Unit,
    onDismiss: () -> Unit
) {
    val screens = PipScreen.entries
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.86f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.size(310.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color(0xFF00F0FF).copy(alpha = 0.35f),
                    radius = size.minDimension * 0.46f,
                    style = Stroke(
                        width = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                    )
                )
                drawCircle(
                    color = Color(0xFF00E676).copy(alpha = 0.2f),
                    radius = size.minDimension * 0.24f,
                    style = Stroke(width = 2f)
                )
            }

            // Центральная кнопка закрытия
            Surface(
                shape = CircleShape,
                color = Color(0xFF0D1520),
                border = BorderStroke(1.5.dp, Color(0xFF00F0FF)),
                modifier = Modifier
                    .size(86.dp)
                    .clickable { onDismiss() }
            ) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "PIP-OS",
                        color = Color(0xFF00F0FF),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "ЗАКРЫТЬ",
                        color = Color(0xFF90A4AE),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    )
                }
            }

            // Элементы по кругу
            screens.forEachIndexed { index, item ->
                val angleRad = (2.0 * PI * index / screens.size) - (PI / 2.0)
                val radiusDp = 115f
                val offsetX = (cos(angleRad) * radiusDp).dp
                val offsetY = (sin(angleRad) * radiusDp).dp
                val isSelected = item == currentScreen

                Surface(
                    shape = CircleShape,
                    color = if (isSelected) Color(0xFF00F0FF) else Color(0xFF101924),
                    border = BorderStroke(1.dp, if (isSelected) Color.White else Color(0xFF00F0FF)),
                    modifier = Modifier
                        .offset(x = offsetX, y = offsetY)
                        .size(64.dp)
                        .clickable { onSelectScreen(item) }
                ) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.title,
                            tint = if (isSelected) Color.Black else Color(0xFF00F0FF),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = item.title,
                            color = if (isSelected) Color.Black else Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// ЭКРАН 0: ГЛАВНЫЙ ЭКРАН С ЛИЦОМ ПИПА
// ============================================================================
@Composable
private fun MainFaceScreen(
    uiState: PetUiState,
    telemetry: SensorTelemetry,
    onPokeEye: (Boolean) -> Unit,
    onPetStroke: () -> Unit,
    onDoubleTap: () -> Unit,
    onFeedQuick: () -> Unit,
    onSleepQuick: () -> Unit,
    onOpenGames: () -> Unit,
    onFpsUpdate: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Огромное процедурное лицо на чисто черном AMOLED фоне
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            ProceduralFaceCanvas(
                mood = uiState.currentMood,
                tiltOffset = Offset(telemetry.tiltX, telemetry.tiltY),
                eyeColor = Color(uiState.eyeColorHex),
                onPokeEye = onPokeEye,
                onPetStroke = onPetStroke,
                onDoubleTap = onDoubleTap,
                onFpsUpdate = onFpsUpdate
            )
        }

        // Нижняя строка причины эмоции (`pet_mood_why`) и быстрых интеракций
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF060A0F))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "WHY: ${uiState.moodWhy}",
                color = Color(0xFFB0BEC5),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                RetroActionChip("🍪 ПОКОРМИТЬ", Color(0xFF00E676), onFeedQuick)
                RetroActionChip(
                    if (uiState.isSleepingManual) "☀️ РАЗБУДИТЬ" else "🌙 СПАТЬ",
                    Color(0xFF00F0FF),
                    onSleepQuick
                )
                RetroActionChip("🎮 ИГРАТЬ", Color(0xFFFFB300), onOpenGames)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Подсказка: погладь экран, тапни в глаз, подуй в микрофон или потряси телефон",
                color = Color(0xFF546E7A),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun RetroActionChip(label: String, color: Color, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, color.copy(alpha = 0.7f)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ============================================================================
// ЭКРАН 1: УХОД ЗА ПИПОМ (PET CARE) И ЛОГ СОСТОЯНИЙ
// ============================================================================
@Composable
private fun PetCareScreen(uiState: PetUiState, viewModel: PetViewModel) {
    val telemetry by viewModel.sensorHub.telemetry.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // Мини-превью глаз Пипа + статус
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1C2A38), RoundedCornerShape(12.dp))
                    .background(Color(0xFF070C12), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(110.dp, 74.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .border(1.dp, Color(0xFF00F0FF).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                ) {
                    ProceduralFaceCanvas(
                        mood = uiState.currentMood,
                        tiltOffset = Offset(telemetry.tiltX, telemetry.tiltY),
                        eyeColor = Color(uiState.eyeColorHex),
                        isMiniPreview = true
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ТЕКУЩАЯ ЭМОЦИЯ:",
                        color = Color(0xFF78909C),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                    Text(
                        text = uiState.currentMood.titleRu,
                        color = Color(0xFF00F0FF),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = uiState.moodWhy,
                        color = Color(0xFFCFD8DC),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }

        item {
            // Полоски потребностей
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1C2A38), RoundedCornerShape(12.dp))
                    .background(Color(0xFF070C12), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "БИОМЕТРИЯ И ПОТРЕБНОСТИ ПИПА",
                    color = Color(0xFF00E676),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                NeedProgressBar("🍪 Сытость (Еда)", uiState.needs.satiety, Color(0xFF00E676))
                NeedProgressBar("⚡ Бодрость (Сон)", uiState.needs.energy, Color(0xFF00F0FF))
                NeedProgressBar("🎮 Веселье (Игры)", uiState.needs.funLevel, Color(0xFFFFB300))
                NeedProgressBar("❤️ Внимание (Общение)", uiState.needs.social, Color(0xFFFF4081))
                NeedProgressBar("👟 Прогулка (Шаги: ${telemetry.sessionSteps})", uiState.needs.walk, Color(0xFF7C4DFF))

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    RetroActionChip("🍪 Покормить", Color(0xFF00E676)) { viewModel.feedPet() }
                    RetroActionChip("🤚 Погладить", Color(0xFFFF4081)) { viewModel.onPetStroked() }
                    RetroActionChip("😴 Сон", Color(0xFF00F0FF)) { viewModel.toggleSleepManual() }
                }
            }
        }

        item {
            // Выбор цвета свечения глаз на OLED-экране
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1C2A38), RoundedCornerShape(12.dp))
                    .background(Color(0xFF070C12), RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = "ЦВЕТ ЛЮМИНОФОРА ГЛАЗ",
                    color = Color(0xFF00F0FF),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                val palette = listOf(
                    0xFFF5F7FA to "WHITE",
                    0xFF00F0FF to "CYAN",
                    0xFF00E676 to "MATRIX",
                    0xFFFFB300 to "AMBER",
                    0xFFFF4081 to "NEON PINK"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    palette.forEach { (hex, name) ->
                        val active = uiState.eyeColorHex == hex
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(hex).copy(alpha = if (active) 0.25f else 0.08f))
                                .border(
                                    width = if (active) 2.dp else 1.dp,
                                    color = Color(hex),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable { viewModel.setEyeColor(hex) }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = name,
                                color = Color(hex),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "ЖУРНАЛ ПРИЧИН НАСТРОЕНИЯ (PET_MOOD_WHY LOG):",
                color = Color(0xFF90A4AE),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        items(uiState.moodHistory) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF080D14), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF16222F), RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.time,
                    color = Color(0xFFFFB300),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = entry.mood.titleRu,
                        color = Color(0xFF00F0FF),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = entry.reason,
                        color = Color(0xFFB0BEC5),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun NeedProgressBar(label: String, value: Float, color: Color) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("${value.toInt()}%", color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (value / 100f).coerceIn(0f, 1f) },
            color = color,
            trackColor = Color(0xFF1A2430),
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
        )
    }
}

// ============================================================================
// ЭКРАН 2: ШУМОМЕР (SOUND METER) + ДЕТЕКТОР ДУНОВЕНИЯ
// ============================================================================
@Composable
private fun SoundMeterScreen(
    uiState: PetUiState,
    telemetry: SensorTelemetry,
    onResetPeak: () -> Unit
) {
    val dbHistory = remember { mutableStateListOf<Float>() }
    LaunchedEffect(telemetry.soundDb) {
        dbHistory.add(telemetry.soundDb)
        if (dbHistory.size > 48) dbHistory.removeAt(0)
    }

    val zoneColor = when {
        telemetry.soundDb < 45f -> Color(0xFF00E676)
        telemetry.soundDb < 72f -> Color(0xFFFFB300)
        else -> Color(0xFFFF3366)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "АКУСТИЧЕСКИЙ СКАНЕР И РЕАКЦИЯ ПИПА",
            color = Color(0xFF00F0FF),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        // Живое превью глаз Пипа, реагирующего на звук
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .background(Color.Black, RoundedCornerShape(12.dp))
                .border(1.dp, zoneColor.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
        ) {
            ProceduralFaceCanvas(
                mood = uiState.currentMood,
                tiltOffset = Offset(telemetry.tiltX, telemetry.tiltY),
                eyeColor = Color(uiState.eyeColorHex),
                isMiniPreview = true
            )
        }

        // Цифровой индикатор дБ
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ТЕКУЩИЙ ШУМ", color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Text(
                    text = "${telemetry.soundDb.toInt()} dB",
                    color = zoneColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ПИК (PEAK)", color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Text(
                    text = "${telemetry.peakDb.toInt()} dB",
                    color = Color(0xFF00F0FF),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onResetPeak) {
                    Text("СБРОС ПИКА", color = Color(0xFFFFB300), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
        }

        // Осциллограмма шума на Canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(Color(0xFF070C12), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF1C2A38), RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            val barW = size.width / 48f
            dbHistory.forEachIndexed { idx, db ->
                val norm = ((db - 20f) / 80f).coerceIn(0.05f, 1f)
                val barH = size.height * norm
                val c = when {
                    db < 45f -> Color(0xFF00E676)
                    db < 72f -> Color(0xFFFFB300)
                    else -> Color(0xFFFF3366)
                }
                drawRect(
                    color = c,
                    topLeft = Offset(idx * barW, size.height - barH),
                    size = Size((barW - 3f).coerceAtLeast(2f), barH)
                )
            }
        }

        // Статус дуновения и реакции
        val statusComment = when {
            telemetry.isBlowing -> "💨 РАСПОЗНАНО ДУНОВЕНИЕ В МИКРОФОН! Пип щурится от ветра!"
            telemetry.soundDb < 40f -> "🧘 Тишина (<40 dB): Пип переходит в умиротворенный режим Дзен."
            telemetry.soundDb < 72f -> "🎙 Комфортный звуковой фон: Пип внимательно слушает."
            else -> "🔊 ГРОМКИЙ ШУМ (>72 dB): Пип раздражен громкими звуками!"
        }
        Text(
            text = statusComment,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D1520), RoundedCornerShape(8.dp))
                .padding(12.dp)
        )
    }
}

// ============================================================================
// ЭКРАН 3: ЦИФРОВОЙ БЕЙДЖ И ГЕНЕРАТОР QR-КОДОВ (ZXING MATRIX)
// ============================================================================
@Composable
private fun BadgeAndQrScreen(
    badge: OwnerBadge,
    onUpdateBadge: (OwnerBadge) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Telegram, 1 = GitHub, 2 = Визитка
    var isEditing by remember { mutableStateOf(false) }

    var nameInput by remember(badge) { mutableStateOf(badge.ownerName) }
    var roleInput by remember(badge) { mutableStateOf(badge.ownerRole) }
    var statusInput by remember(badge) { mutableStateOf(badge.statusLine) }
    var tgInput by remember(badge) { mutableStateOf(badge.telegramUrl) }
    var ghInput by remember(badge) { mutableStateOf(badge.githubUrl) }
    var customInput by remember(badge) { mutableStateOf(badge.customQrText) }

    val activeQrContent = when (selectedTab) {
        0 -> badge.telegramUrl
        1 -> badge.githubUrl
        else -> badge.customQrText
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // Кибер-карточка владельца
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF08101A), RoundedCornerShape(14.dp))
                    .border(1.5.dp, Color(0xFF00F0FF), RoundedCornerShape(14.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ID BADGE // PIP-KEYPET",
                        color = Color(0xFF00E676),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { isEditing = !isEditing }) {
                        Text(
                            text = if (isEditing) "ЗАКРЫТЬ" else "ИЗМЕНИТЬ",
                            color = Color(0xFFFFB300),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
                Text(
                    text = badge.ownerName,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = badge.ownerRole,
                    color = Color(0xFF00F0FF),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = badge.statusLine,
                    color = Color(0xFF90A4AE),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }

        if (isEditing) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0A1017), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF263238), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Имя / Никнейм") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = roleInput,
                        onValueChange = { roleInput = it },
                        label = { Text("Роль / Должность") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = statusInput,
                        onValueChange = { statusInput = it },
                        label = { Text("Статус") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tgInput,
                        onValueChange = { tgInput = it },
                        label = { Text("Ссылка Telegram") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = ghInput,
                        onValueChange = { ghInput = it },
                        label = { Text("Ссылка GitHub") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customInput,
                        onValueChange = { customInput = it },
                        label = { Text("Визитка / Кастомный текст") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            onUpdateBadge(
                                OwnerBadge(
                                    ownerName = nameInput,
                                    ownerRole = roleInput,
                                    statusLine = statusInput,
                                    telegramUrl = tgInput,
                                    githubUrl = ghInput,
                                    customQrText = customInput
                                )
                            )
                            isEditing = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("СОХРАНИТЬ ПРОФИЛЬ И QR", fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                RetroActionChip("TELEGRAM", if (selectedTab == 0) Color(0xFF00F0FF) else Color.Gray) { selectedTab = 0 }
                RetroActionChip("GITHUB", if (selectedTab == 1) Color(0xFF00E676) else Color.Gray) { selectedTab = 1 }
                RetroActionChip("ВИЗИТКА", if (selectedTab == 2) Color(0xFFFFB300) else Color.Gray) { selectedTab = 2 }
            }
        }

        item {
            // Матричный рендер QR-кода на Compose Canvas через ZXing
            QrCodeMatrixCanvas(
                content = activeQrContent.ifBlank { "https://t.me" },
                modifier = Modifier
                    .size(230.dp)
                    .background(Color.White, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = activeQrContent,
                color = Color(0xFF90A4AE),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun QrCodeMatrixCanvas(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) {
        runCatching {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 64, 64, hints)
        }.getOrNull()
    }

    Canvas(modifier = modifier) {
        val bitMatrix = matrix ?: return@Canvas
        val cols = bitMatrix.width
        val rows = bitMatrix.height
        val cellW = size.width / cols
        val cellH = size.height / rows

        for (y in 0 until rows) {
            for (x in 0 until cols) {
                if (bitMatrix.get(x, y)) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * cellW, y * cellH),
                        size = Size(ceil(cellW), ceil(cellH))
                    )
                }
            }
        }
    }
}

// ============================================================================
// ЭКРАН 4: ИНСТРУМЕНТЫ (СЕКУНДОМЕР, ТАЙМЕР, ЭКРАННЫЙ ФОНАРИК)
// ============================================================================
@Composable
private fun ToolsScreen(
    uiState: PetUiState,
    haptics: HapticEngine,
    onSetMaxBrightness: (Boolean) -> Unit
) {
    var toolTab by remember { mutableIntStateOf(0) } // 0 = Секундомер, 1 = Таймер, 2 = Фонарик

    // Состояние секундомера
    var swRunning by remember { mutableStateOf(false) }
    var swElapsedMs by remember { mutableLongStateOf(0L) }
    val swLaps = remember { mutableStateListOf<String>() }

    // Состояние таймера
    var timerRunning by remember { mutableStateOf(false) }
    var timerRemainingSec by remember { mutableIntStateOf(180) }

    // Состояние фонарика
    var torchActive by remember { mutableStateOf(false) }

    LaunchedEffect(swRunning) {
        var lastMark = System.currentTimeMillis()
        while (swRunning) {
            delay(30L)
            val now = System.currentTimeMillis()
            swElapsedMs += (now - lastMark)
            lastMark = now
        }
    }

    LaunchedEffect(timerRunning) {
        while (timerRunning && timerRemainingSec > 0) {
            delay(1000L)
            timerRemainingSec--
            if (timerRemainingSec == 0) {
                timerRunning = false
                haptics.victoryFanfare()
            }
        }
    }

    LaunchedEffect(torchActive, toolTab) {
        onSetMaxBrightness(torchActive && toolTab == 2)
    }

    if (torchActive && toolTab == 2) {
        // Полноэкранный фонарик с максимальной белой яркостью + крошечные щурящиеся глазки Пипа
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .clickable {
                    torchActive = false
                    onSetMaxBrightness(false)
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(150.dp, 85.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black)
                ) {
                    ProceduralFaceCanvas(
                        mood = PetMood.REACTION_WIND_BLOW,
                        tiltOffset = Offset.Zero,
                        isMiniPreview = true
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "ФОНАРИК MAX ЯРКОСТЬ (ТАПНИ ДЛЯ ВЫКЛЮЧЕНИЯ)",
                    color = Color.Black,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            RetroActionChip("⏱ СЕКУНДОМЕР", if (toolTab == 0) Color(0xFF00F0FF) else Color.Gray) { toolTab = 0 }
            RetroActionChip("⏳ ТАЙМЕР", if (toolTab == 1) Color(0xFF00E676) else Color.Gray) { toolTab = 1 }
            RetroActionChip("🔦 ФОНАРИК", if (toolTab == 2) Color(0xFFFFB300) else Color.Gray) { toolTab = 2 }
        }

        when (toolTab) {
            0 -> {
                val mins = (swElapsedMs / 60000)
                val secs = (swElapsedMs / 1000) % 60
                val millis = (swElapsedMs % 1000) / 10
                val formatted = String.format(Locale.US, "%02d:%02d.%02d", mins, secs, millis)

                Text(
                    text = formatted,
                    color = Color(0xFF00F0FF),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RetroActionChip(if (swRunning) "ПАУЗА" else "СТАРТ", Color(0xFF00E676)) {
                        haptics.tick()
                        swRunning = !swRunning
                    }
                    RetroActionChip("КРУГ", Color(0xFFFFB300)) {
                        if (swElapsedMs > 0L) {
                            haptics.tick()
                            swLaps.add(0, "Круг #${swLaps.size + 1}: $formatted")
                        }
                    }
                    RetroActionChip("СБРОС", Color(0xFFFF3366)) {
                        swRunning = false
                        swElapsedMs = 0L
                        swLaps.clear()
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(swLaps) { lap ->
                        Text(
                            text = lap,
                            color = Color(0xFFB0BEC5),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 3.dp)
                        )
                    }
                }
            }

            1 -> {
                val mins = timerRemainingSec / 60
                val secs = timerRemainingSec % 60
                Text(
                    text = String.format(Locale.US, "%02d:%02d", mins, secs),
                    color = Color(0xFF00E676),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RetroActionChip("+1 МИН", Color(0xFF00F0FF)) { timerRemainingSec += 60 }
                    RetroActionChip("+3 МИН", Color(0xFF00F0FF)) { timerRemainingSec += 180 }
                    RetroActionChip("СБРОС", Color(0xFFFF3366)) {
                        timerRunning = false
                        timerRemainingSec = 60
                    }
                }
                RetroActionChip(
                    if (timerRunning) "ОСТАНОВИТЬ ТАЙМЕР" else "ЗАПУСТИТЬ ТАЙМЕР",
                    Color(0xFF00E676)
                ) {
                    haptics.tick()
                    timerRunning = !timerRunning
                }
            }

            2 -> {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Режим заливки экрана максимальной белой яркостью (100% нит).",
                    color = Color(0xFFB0BEC5),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = {
                        haptics.tick()
                        torchActive = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300))
                ) {
                    Text(
                        text = "🔦 ВКЛЮЧИТЬ ЭКРАННЫЙ ФОНАРИК",
                        color = Color.Black,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ============================================================================
// ЭКРАН 5: МИНИ-ИГРЫ (INFINITE TIC-TAC-TOE, ГИРО-ЛАБИРИНТ, MAGIC 8-BALL & КОСТИ)
// ============================================================================
@Composable
private fun MiniGamesScreen(
    uiState: PetUiState,
    telemetry: SensorTelemetry,
    viewModel: PetViewModel
) {
    var gameTab by remember { mutableIntStateOf(0) } // 0 = Крестики-Нолики, 1 = Лабиринт, 2 = 8-Ball/Кости

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            RetroActionChip("❌⭕ 3-ХОДА X/O", if (gameTab == 0) Color(0xFF00F0FF) else Color.Gray) { gameTab = 0 }
            RetroActionChip("🌀 ГИРО-ЛАБИРИНТ", if (gameTab == 1) Color(0xFF00E676) else Color.Gray) { gameTab = 1 }
            RetroActionChip("🎱 ШАР СУДЬБЫ", if (gameTab == 2) Color(0xFFFFB300) else Color.Gray) { gameTab = 2 }
        }
        Spacer(modifier = Modifier.height(10.dp))

        when (gameTab) {
            0 -> InfiniteTicTacToeGame(uiState = uiState, telemetry = telemetry, viewModel = viewModel)
            1 -> GyroMazeGame(telemetry = telemetry, viewModel = viewModel)
            2 -> Magic8BallAndDiceGame(telemetry = telemetry, viewModel = viewModel)
        }
    }
}

/**
 * Игра 1: Бесконечные крестики-нолики (Infinite Tic-Tac-Toe) против Пипа.
 * У каждого игрока максимум 3 фигуры на поле; при постановке 4-й самая старая исчезает!
 */
@Composable
private fun InfiniteTicTacToeGame(
    uiState: PetUiState,
    telemetry: SensorTelemetry,
    viewModel: PetViewModel
) {
    val humanMoves = remember { mutableStateListOf<Int>() } // Очередь X (макс 3)
    val pipMoves = remember { mutableStateListOf<Int>() }   // Очередь O (макс 3)
    var isHumanTurn by remember { mutableStateOf(true) }
    var winnerText by remember { mutableStateOf<String?>(null) }
    var pipLocalMood by remember { mutableStateOf(PetMood.FOCUSED) }

    val winLines = remember {
        listOf(
            listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
            listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
            listOf(0, 4, 8), listOf(2, 4, 6)
        )
    }

    fun checkWin(moves: List<Int>): Boolean =
        winLines.any { line -> line.all { it in moves } }

    // Ход ИИ Пипа с эмоциональной реакцией
    LaunchedEffect(isHumanTurn, winnerText) {
        if (!isHumanTurn && winnerText == null) {
            pipLocalMood = PetMood.THINKING
            delay(520L)

            val occupied = (humanMoves + pipMoves).toSet()
            val freeCells = (0..8).filter { it !in occupied }

            if (freeCells.isNotEmpty()) {
                // Симуляция: какие клетки останутся у Пипа после постановки 4-й фигуры
                val futurePipBase = if (pipMoves.size >= 3) pipMoves.drop(1) else pipMoves.toList()
                val futureHumanBase = if (humanMoves.size >= 3) humanMoves.drop(1) else humanMoves.toList()

                // 1. Может ли Пип выиграть этим ходом?
                val winningCell = freeCells.firstOrNull { cand ->
                    checkWin(futurePipBase + cand)
                }
                // 2. Нужно ли блокировать победу человека?
                val blockingCell = freeCells.firstOrNull { cand ->
                    checkWin(futureHumanBase + cand)
                }
                val chosen = winningCell ?: blockingCell ?: if (4 in freeCells) 4 else freeCells.random()

                if (pipMoves.size >= 3) pipMoves.removeAt(0)
                pipMoves.add(chosen)
                viewModel.haptics.tick()

                if (checkWin(pipMoves)) {
                    winnerText = "ПИП ПОБЕДИЛ! 🤖"
                    pipLocalMood = PetMood.REACTION_WIN_CELEBRATE
                    viewModel.playBoostFromMiniGame(won = true, gameName = "Бесконечные Крестики-Нолики")
                } else {
                    pipLocalMood = if (blockingCell != null) PetMood.MISCHIEVOUS else PetMood.FOCUSED
                    isHumanTurn = true
                }
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Эмоциональное лицо Пипа над доской
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(95.dp)
                .background(Color.Black, RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF00F0FF).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
        ) {
            ProceduralFaceCanvas(
                mood = pipLocalMood,
                tiltOffset = Offset(telemetry.tiltX, telemetry.tiltY),
                eyeColor = Color(uiState.eyeColorHex),
                isMiniPreview = true
            )
        }

        Text(
            text = winnerText ?: if (isHumanTurn) "Твой ход (X) • Выделенная фигура исчезнет!" else "Пип думает над ходом (O)...",
            color = Color(0xFF00F0FF),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )

        // Сетка 3x3
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in 0..2) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (col in 0..2) {
                        val idx = row * 3 + col
                        val isX = idx in humanMoves
                        val isO = idx in pipMoves
                        val isOldestX = humanMoves.size == 3 && humanMoves.first() == idx
                        val isOldestO = pipMoves.size == 3 && pipMoves.first() == idx

                        Box(
                            modifier = Modifier
                                .size(82.dp)
                                .background(Color(0xFF0A121C), RoundedCornerShape(12.dp))
                                .border(
                                    width = if (isOldestX || isOldestO) 2.dp else 1.dp,
                                    color = when {
                                        isOldestX || isOldestO -> Color(0xFFFF3366)
                                        isX -> Color(0xFF00E676)
                                        isO -> Color(0xFF00F0FF)
                                        else -> Color(0xFF1E2D3D)
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable(enabled = isHumanTurn && winnerText == null && !isX && !isO) {
                                    if (humanMoves.size >= 3) humanMoves.removeAt(0)
                                    humanMoves.add(idx)
                                    viewModel.haptics.tick()
                                    if (checkWin(humanMoves)) {
                                        winnerText = "ТЫ ПОБЕДИЛ ПИПА! 🏆"
                                        pipLocalMood = PetMood.REACTION_LOSE_SULK
                                        viewModel.playBoostFromMiniGame(won = false, gameName = "Бесконечные Крестики-Нолики")
                                    } else {
                                        isHumanTurn = false
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                isX -> Text(
                                    text = if (isOldestX) "X⏳" else "X",
                                    color = Color(0xFF00E676).copy(alpha = if (isOldestX) 0.45f else 1f),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                isO -> Text(
                                    text = if (isOldestO) "O⏳" else "O",
                                    color = Color(0xFF00F0FF).copy(alpha = if (isOldestO) 0.45f else 1f),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        RetroActionChip("🔄 НОВАЯ ПАРТИЯ", Color(0xFFFFB300)) {
            humanMoves.clear()
            pipMoves.clear()
            winnerText = null
            isHumanTurn = true
            pipLocalMood = PetMood.FOCUSED
        }
    }
}

/**
 * Игра 2: Гиро-лабиринт (Maze), управляемый реальным наклоном смартфона
 */
@Composable
private fun GyroMazeGame(
    telemetry: SensorTelemetry,
    viewModel: PetViewModel
) {
    // Сетка лабиринта 9x9 (1 = стена, 0 = проход, 2 = портал финиша)
    val mazeGrid = remember {
        arrayOf(
            intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1),
            intArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 1),
            intArrayOf(1, 0, 1, 0, 1, 0, 1, 0, 1),
            intArrayOf(1, 0, 1, 0, 0, 0, 1, 0, 1),
            intArrayOf(1, 0, 1, 1, 1, 0, 1, 0, 1),
            intArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 1),
            intArrayOf(1, 1, 1, 0, 1, 1, 1, 0, 1),
            intArrayOf(1, 0, 0, 0, 0, 0, 1, 2, 1),
            intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1)
        )
    }

    var ballPos by remember { mutableStateOf(Offset(1.5f, 1.5f)) }
    var ballVel by remember { mutableStateOf(Offset.Zero) }
    var levelScore by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            withInfiniteAnimationFrameMillis {
                val ax = telemetry.tiltX * 0.018f
                val ay = telemetry.tiltY * 0.018f
                val vx = (ballVel.x + ax).coerceIn(-0.14f, 0.14f) * 0.92f
                val vy = (ballVel.y + ay).coerceIn(-0.14f, 0.14f) * 0.92f

                val nextX = ballPos.x + vx
                val nextY = ballPos.y + vy
                val r = 0.28f

                fun isWall(cx: Float, cy: Float): Boolean {
                    val gx = cx.toInt().coerceIn(0, 8)
                    val gy = cy.toInt().coerceIn(0, 8)
                    return mazeGrid[gy][gx] == 1
                }

                val canMoveX = !isWall(nextX - r, ballPos.y) && !isWall(nextX + r, ballPos.y)
                val canMoveY = !isWall(ballPos.x, nextY - r) && !isWall(ballPos.x, nextY + r)

                ballPos = Offset(
                    x = if (canMoveX) nextX else ballPos.x,
                    y = if (canMoveY) nextY else ballPos.y
                )
                ballVel = Offset(
                    x = if (canMoveX) vx else -vx * 0.4f,
                    y = if (canMoveY) vy else -vy * 0.4f
                )

                // Проверка достижения портала (клетка 7,7)
                if (mazeGrid[ballPos.y.toInt()][ballPos.x.toInt()] == 2) {
                    levelScore++
                    ballPos = Offset(1.5f, 1.5f)
                    ballVel = Offset.Zero
                    viewModel.onMazeCompleted()
                }
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "НАКЛОНЯЙ ТЕЛЕФОН! ПРОЙДЕНО ЛАБИРИНТОВ: $levelScore",
            color = Color(0xFF00E676),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )

        Canvas(
            modifier = Modifier
                .size(290.dp)
                .background(Color(0xFF050A0F), RoundedCornerShape(12.dp))
                .border(1.5.dp, Color(0xFF00F0FF), RoundedCornerShape(12.dp))
        ) {
            val cellW = size.width / 9f
            val cellH = size.height / 9f

            for (y in 0..8) {
                for (x in 0..8) {
                    when (mazeGrid[y][x]) {
                        1 -> drawRect(
                            color = Color(0xFF162636),
                            topLeft = Offset(x * cellW, y * cellH),
                            size = Size(cellW, cellH)
                        )
                        2 -> drawCircle(
                            color = Color(0xFF00E676),
                            radius = cellW * 0.36f,
                            center = Offset((x + 0.5f) * cellW, (y + 0.5f) * cellH)
                        )
                    }
                }
            }

            // Шарик игрока
            drawCircle(
                color = Color(0xFF00F0FF),
                radius = cellW * 0.32f,
                center = Offset(ballPos.x * cellW, ballPos.y * cellH)
            )
        }
    }
}

/**
 * Игра 3: Шар судьбы (Magic 8-Ball), Кубик D6 и Монетка (срабатывают от тряски смартфона!)
 */
@Composable
private fun Magic8BallAndDiceGame(
    telemetry: SensorTelemetry,
    viewModel: PetViewModel
) {
    val answers = remember {
        listOf(
            "БЕЗУСЛОВНО ДА! ✨",
            "ПИП УВЕРЕН НА 100%! 🤖",
            "ЗВЕЗДЫ ГОВОРЯТ «ДА» 🌟",
            "ПОПРОБУЙ ЕЩЁ РАЗ 🔄",
            "ЛУЧШЕ СНАЧАЛА ПОКОРМИ МЕНЯ 🍪",
            "ДАЖЕ НЕ ДУМАЙ! ❌",
            "ШАНСЫ ВЫСОКИЕ 🚀",
            "СПРОСИ ПОСЛЕ ОБЕДА 😴"
        )
    }

    var oracleResult by remember { mutableStateOf("Потряси телефон или нажми кнопку!") }
    var diceA by remember { mutableIntStateOf(6) }
    var diceB by remember { mutableIntStateOf(6) }
    var coinSide by remember { mutableStateOf("ОРЕЛ 🦅") }

    fun rollEverything() {
        viewModel.haptics.victoryFanfare()
        oracleResult = answers.random()
        diceA = Random.nextInt(1, 7)
        diceB = Random.nextInt(1, 7)
        coinSide = if (Random.nextBoolean()) "ОРЕЛ 🦅" else "РЕШКА 🪙"
        viewModel.modifyNeeds(funDelta = 6f, socialDelta = 4f)
    }

    // Срабатывание при реальной тряске смартфона
    LaunchedEffect(Unit) {
        viewModel.sensorHub.events.collect { ev ->
            if (ev is PetEvent.ShakeDetected) {
                rollEverything()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A131E), RoundedCornerShape(14.dp))
                .border(1.5.dp, Color(0xFFFFB300), RoundedCornerShape(14.dp))
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🎱 ОТВЕТ ШАРА СУДЬБЫ ПИПА:", color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = oracleResult,
                    color = Color(0xFFFFB300),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF0A131E), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF00F0FF), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("КОСТИ 2xD6", color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Text(
                    text = "[$diceA] + [$diceB] = ${diceA + diceB}",
                    color = Color(0xFF00F0FF),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                modifier = Modifier
                    .background(Color(0xFF0A131E), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF00E676), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("КИБЕР-МОНЕТКА", color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Text(
                    text = coinSide,
                    color = Color(0xFF00E676),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Button(
            onClick = { rollEverything() },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F0FF))
        ) {
            Text(
                text = "🎲 ПОТРЯСТИ / БРОСИТЬ ЖРЕБИЙ",
                color = Color.Black,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ============================================================================
// ЭКРАН 6: ПАНЕЛЬ ОТЛАДКИ (DEVELOPER / DEBUG DRAWER)
// ============================================================================
@Composable
private fun DebugDrawerScreen(
    uiState: PetUiState,
    telemetry: SensorTelemetry,
    fps: Int,
    viewModel: PetViewModel
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "DEVELOPER TELEMETRY & EMOTION OVERRIDE",
                color = Color(0xFFFFB300),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(
                text = buildString {
                    appendLine("FPS: $fps | G-Force: ${"%.2f".format(telemetry.totalG)}G | FaceDown: ${telemetry.isFaceDown}")
                    appendLine("Accel XYZ: (${"%.1f".format(telemetry.accelX)}, ${"%.1f".format(telemetry.accelY)}, ${"%.1f".format(telemetry.accelZ)})")
                    appendLine("Mic: ${"%.1f".format(telemetry.soundDb)} dB (Peak ${"%.1f".format(telemetry.peakDb)}) | Blow: ${telemetry.isBlowing}")
                    append("Battery: ${telemetry.batteryPct}% (Charging=${telemetry.isCharging}) | Steps: ${telemetry.sessionSteps}")
                },
                color = Color(0xFF00E676),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF070C12), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF1C2A38), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            )
        }

        item {
            // Ползунки ручной подкрутки потребностей
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF070C12), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF1C2A38), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text("РУЧНАЯ НАСТРОЙКА ПОТРЕБНОСТЕЙ (0..100):", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                DebugSliderRow("Сытость", uiState.needs.satiety) { viewModel.setNeedExact("satiety", it) }
                DebugSliderRow("Бодрость", uiState.needs.energy) { viewModel.setNeedExact("energy", it) }
                DebugSliderRow("Веселье", uiState.needs.funLevel) { viewModel.setNeedExact("fun", it) }
                DebugSliderRow("Внимание", uiState.needs.social) { viewModel.setNeedExact("social", it) }
                DebugSliderRow("Прогулка", uiState.needs.walk) { viewModel.setNeedExact("walk", it) }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "КАТАЛОГ 28 ЭМОЦИЙ (ТАП ДЛЯ ТЕСТА):",
                    color = Color(0xFF00F0FF),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                RetroActionChip("АВТО-РЕЖИМ", Color(0xFF00E676)) {
                    viewModel.setDebugForcedMood(null)
                }
            }
        }

        items(PetMood.entries.chunked(2)) { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pair.forEach { mood ->
                    val isLocked = uiState.forcedDebugMood == mood
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isLocked) Color(0xFF00F0FF) else Color(0xFF0B131E))
                            .border(
                                1.dp,
                                if (mood.isReaction) Color(0xFFFF4081) else Color(0xFF1E2D3D),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { viewModel.setDebugForcedMood(mood) }
                            .padding(8.dp)
                    ) {
                        Column {
                            Text(
                                text = mood.name,
                                color = if (isLocked) Color.Black else Color(0xFF00F0FF),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = mood.titleRu,
                                color = if (isLocked) Color.Black else Color(0xFFB0BEC5),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugSliderRow(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label: ${value.toInt()}",
            color = Color(0xFFB0BEC5),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.width(105.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f)
        )
    }
}
