package com.pippocket.pet

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class PetNeeds(
    val satiety: Float = 78f,   // Сытость (0..100)
    val energy: Float = 84f,    // Бодрость / Сон (0..100)
    val funLevel: Float = 72f,  // Веселье / Игры (0..100)
    val social: Float = 80f,    // Внимание / Общение (0..100)
    val walk: Float = 60f       // Прогулка / Движение (0..100)
)

data class OwnerBadge(
    val ownerName: String = "ALEX // CYBER-DEV",
    val ownerRole: String = "Senior Android Architect",
    val statusLine: String = "ONLINE • PIP-OS v2.6",
    val telegramUrl: String = "https://t.me/durov",
    val githubUrl: String = "https://github.com/JetBrains/kotlin",
    val customQrText: String = "PIP-POCKET-PET://COMPANION-UNIT-01"
)

data class MoodLogEntry(
    val time: String,
    val mood: PetMood,
    val reason: String
)

data class PetUiState(
    val currentMood: PetMood = PetMood.HAPPY,
    val baseMood: PetMood = PetMood.HAPPY,
    val moodWhy: String = "Все показатели в норме, Пип доволен жизнью",
    val needs: PetNeeds = PetNeeds(),
    val forcedDebugMood: PetMood? = null,
    val isSleepingManual: Boolean = false,
    val eyeColorHex: Long = 0xFFF5F7FA,
    val badge: OwnerBadge = OwnerBadge(),
    val moodHistory: List<MoodLogEntry> = emptyList(),
    val ticTacToeWins: Int = 0,
    val mazeClearedCount: Int = 0
)

class PetViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("pip_pocket_pet_prefs", Context.MODE_PRIVATE)
    val sensorHub = HardwareSensorHub(application.applicationContext)
    val haptics = HapticEngine(application.applicationContext)

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<PetUiState> = _uiState.asStateFlow()

    private var reactionResetJob: Job? = null
    private var lastInteractionMillis: Long = System.currentTimeMillis()
    private var lastGameOutcomeMood: PetMood? = null
    private var lastGameOutcomeExpiresAt: Long = 0L
    private var idleCycleIndex: Int = 0

    init {
        observeSensorEvents()
        startLifeLoop()
    }

    private fun observeSensorEvents() {
        viewModelScope.launch {
            sensorHub.events.collect { event ->
                when (event) {
                    is PetEvent.FaceDownChanged -> {
                        if (event.isFaceDown) {
                            triggerBaseReevaluation()
                        } else {
                            lastInteractionMillis = System.currentTimeMillis()
                            triggerBaseReevaluation()
                        }
                    }
                    PetEvent.ShakeDetected -> {
                        lastInteractionMillis = System.currentTimeMillis()
                        haptics.alertPanic()
                        triggerReaction(
                            PetMood.REACTION_DIZZY,
                            durationMs = 4200L,
                            reason = "Сработал ShakeDetector: телефон сильно потрясли!"
                        )
                    }
                    PetEvent.FreeFallDetected -> {
                        lastInteractionMillis = System.currentTimeMillis()
                        haptics.alertPanic()
                        triggerReaction(
                            PetMood.REACTION_FREEFALL,
                            durationMs = 3800L,
                            reason = "Невесомость / резкий рывок! Пип испугался падения!"
                        )
                    }
                    PetEvent.MicBlowDetected -> {
                        lastInteractionMillis = System.currentTimeMillis()
                        haptics.purr()
                        modifyNeeds(socialDelta = 2.5f, funDelta = 2f)
                        triggerReaction(
                            PetMood.REACTION_WIND_BLOW,
                            durationMs = 3400L,
                            reason = "В микрофон подули! Прищурился от потока ветра"
                        )
                    }
                    PetEvent.LoudClapDetected -> {
                        lastInteractionMillis = System.currentTimeMillis()
                        haptics.pokeBump()
                        triggerReaction(
                            PetMood.REACTION_STARTLED,
                            durationMs = 3000L,
                            reason = "Резкий громкий звук вокруг (>76 dB)!"
                        )
                    }
                    PetEvent.StepTaken -> {
                        modifyNeeds(walkDelta = 1.4f, energyDelta = -0.15f, funDelta = 0.4f)
                    }
                }
            }
        }
    }

    /**
     * Главный жизненный цикл питомца: распад потребностей, смена фоновых состояний и пересчет приоритетов
     */
    private fun startLifeLoop() {
        viewModelScope.launch {
            var tickCounter = 0
            while (true) {
                delay(3000L)
                tickCounter++

                val telemetry = sensorHub.telemetry.value
                val sleeping = _uiState.value.isSleepingManual || telemetry.isFaceDown

                // Изменение потребностей со временем
                _uiState.update { state ->
                    val n = state.needs
                    val updatedNeeds = if (sleeping) {
                        n.copy(
                            energy = (n.energy + 1.8f).coerceIn(0f, 100f),
                            satiety = (n.satiety - 0.25f).coerceIn(0f, 100f)
                        )
                    } else {
                        n.copy(
                            satiety = (n.satiety - 0.45f).coerceIn(0f, 100f),
                            energy = (n.energy - 0.35f + if (telemetry.isCharging) 1.2f else 0f).coerceIn(0f, 100f),
                            funLevel = (n.funLevel - 0.40f).coerceIn(0f, 100f),
                            social = (n.social - 0.38f).coerceIn(0f, 100f),
                            walk = (n.walk - 0.25f).coerceIn(0f, 100f)
                        )
                    }
                    state.copy(needs = updatedNeeds)
                }

                if (tickCounter % 4 == 0) {
                    idleCycleIndex = (idleCycleIndex + 1) % 5
                }

                triggerBaseReevaluation()
                if (tickCounter % 5 == 0) {
                    saveStateToPrefs()
                }
            }
        }
    }

    /**
     * Вызов мгновенной реакции (перебивает базовое настроение на 3-5 секунд)
     */
    fun triggerReaction(mood: PetMood, durationMs: Long = 3600L, reason: String) {
        if (_uiState.value.forcedDebugMood != null) return
        reactionResetJob?.cancel()
        appendMoodLog(mood, reason)
        _uiState.update {
            it.copy(currentMood = mood, moodWhy = "[Реакция] $reason")
        }
        reactionResetJob = viewModelScope.launch {
            delay(durationMs)
            triggerBaseReevaluation(forceOverwriteReaction = true)
        }
    }

    /**
     * Дерево приоритетов базового настроения (`pet_mood_why`)
     */
    private fun triggerBaseReevaluation(forceOverwriteReaction: Boolean = false) {
        val state = _uiState.value
        val forced = state.forcedDebugMood
        if (forced != null) {
            _uiState.update {
                it.copy(
                    currentMood = forced,
                    baseMood = forced,
                    moodWhy = "[Debug Lock] Принудительно включена эмоция: ${forced.titleRu}"
                )
            }
            return
        }

        val telemetry = sensorHub.telemetry.value
        val now = System.currentTimeMillis()
        val idleSeconds = (now - lastInteractionMillis) / 1000L
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isLateNight = hour >= 23 || hour < 6
        val n = state.needs

        // Вычисление по приоритетам (от 1 до 5)
        val (evaluatedMood, whyText) = when {
            // Приоритет 1: Сон (переворот экраном вниз, ручной сон, ночь или долго не трогали)
            telemetry.isFaceDown ->
                PetMood.SLEEP_DEEP to "Смартфон перевернут экраном вниз (FaceDown) — режим глубокого сна"
            state.isSleepingManual ->
                PetMood.SLEEP_DEEP to "Владелец уложил Пипа спать (восстановление бодрости)"
            n.energy < 14f ->
                PetMood.SLEEP_DEEP to "Бодрость упала ниже 14% — Пип вырубился от усталости"
            isLateNight && idleSeconds > 45 ->
                PetMood.SLEEP_DROWSY to "Ночное время ($hour:00) и тишина — Пип клюет носом"
            idleSeconds > 75 || n.energy < 28f ->
                PetMood.SLEEP_DROWSY to "Долго не трогали (${idleSeconds}с) или низкая бодрость — дремота"

            // Приоритет 2: Физиология устройства (батарея и шум)
            telemetry.isCharging ->
                PetMood.BATTERY_CHARGING to "Подключена зарядка (${telemetry.batteryPct}%) — Пип «кушает» электричество!"
            telemetry.batteryPct <= 20 ->
                PetMood.BATTERY_LOW to "Заряд батареи смартфона критический (${telemetry.batteryPct}%) — просится к розетке"
            telemetry.soundDb > 75f ->
                PetMood.NOISY_ANNOYED to "Вокруг слишком шумно (${telemetry.soundDb.toInt()} dB) — Пип морщится от шума"

            // Приоритет 3: Послевкусие событий (победа или проигрыш в мини-игре)
            lastGameOutcomeMood != null && now < lastGameOutcomeExpiresAt -> {
                val m = lastGameOutcomeMood!!
                if (m == PetMood.REACTION_WIN_CELEBRATE) {
                    PetMood.ECSTATIC to "Послевкусие недавней игры: радуется классной партии!"
                } else {
                    PetMood.REACTION_LOSE_SULK to "Послевкусие игры: всё ещё дуется после реванша"
                }
            }

            // Приоритет 4: Базовые потребности (шкалы 0..100)
            n.satiety < 12f ->
                PetMood.STARVING_ANGRY to "Сытость критическая (${n.satiety.toInt()}%) — голодный бунт!"
            n.satiety < 30f ->
                PetMood.HUNGRY to "Проголодался (Сытость ${n.satiety.toInt()}%) — строит щенячьи глазки"
            n.social < 26f ->
                PetMood.LONELY to "Не хватает внимания (${n.social.toInt()}%) — погладьте Пипа по экрану"
            n.funLevel < 26f ->
                PetMood.BORED to "Скучно (Веселье ${n.funLevel.toInt()}%) — хочет сыграть в мини-игру"
            n.walk < 22f ->
                PetMood.RESTLESS to "Засиделся на месте (Прогулка ${n.walk.toInt()}%) — нужно подвигаться или пройтись"

            // Приоритет 5: Спокойные фоновые состояния и счастье
            n.satiety > 82f && n.energy > 80f && n.funLevel > 82f && n.social > 82f ->
                PetMood.ECSTATIC to "Все потребности выше 80%! Пип в полном восторге"
            else -> {
                when (idleCycleIndex) {
                    0 -> PetMood.HAPPY to "Гармоничное состояние: сыт, бодр и дружелюбен"
                    1 -> PetMood.CURIOUS to "Фоновое состояние: с любопытством разглядывает мир вокруг"
                    2 -> PetMood.THINKING to "Фоновое состояние: задумался о квантовых вычислениях"
                    3 -> PetMood.MISCHIEVOUS to "Фоновое состояние: замышляет шалость с хитрой прищуркой"
                    else -> if (telemetry.soundDb < 40f) {
                        PetMood.ZEN to "Вокруг тишина (${telemetry.soundDb.toInt()} dB) — Пип медитирует в состоянии Дзен"
                    } else {
                        PetMood.SKEPTICAL to "Фоновое состояние: скептически приподнял бровь"
                    }
                }
            }
        }

        val isCurrentlyInReaction = state.currentMood.isReaction && reactionResetJob?.isActive == true
        val nextActiveMood = if (isCurrentlyInReaction && !forceOverwriteReaction) {
            state.currentMood
        } else {
            evaluatedMood
        }

        if (evaluatedMood != state.baseMood) {
            appendMoodLog(evaluatedMood, whyText)
        }

        _uiState.update {
            it.copy(
                baseMood = evaluatedMood,
                currentMood = nextActiveMood,
                moodWhy = if (nextActiveMood == evaluatedMood) whyText else it.moodWhy
            )
        }
    }

    // --- Интерактивные действия пользователя на экране и в меню ухода ---

    fun onEyePoked(isLeft: Boolean) {
        lastInteractionMillis = System.currentTimeMillis()
        haptics.pokeBump()
        modifyNeeds(socialDelta = 1f)
        val mood = if (isLeft) PetMood.REACTION_POKE_LEFT else PetMood.REACTION_POKE_RIGHT
        val side = if (isLeft) "левый" else "правый"
        triggerReaction(mood, 2800L, "Эй! Быстрый тап прямо в $side глаз!")
    }

    fun onPetStroked() {
        lastInteractionMillis = System.currentTimeMillis()
        haptics.purr()
        modifyNeeds(socialDelta = 8f, funDelta = 3f)
        triggerReaction(
            PetMood.REACTION_BLISS,
            durationMs = 4000L,
            reason = "Поглаживание по экрану — Пип жмурится от удовольствия и мурчит"
        )
    }

    fun onDoubleTapLove() {
        lastInteractionMillis = System.currentTimeMillis()
        haptics.purr()
        modifyNeeds(socialDelta = 6f, funDelta = 4f)
        triggerReaction(
            PetMood.REACTION_LOVE,
            durationMs = 3500L,
            reason = "Двойной тап — проявление любви к Пипу!"
        )
    }

    fun feedPet() {
        lastInteractionMillis = System.currentTimeMillis()
        haptics.victoryFanfare()
        _uiState.update { it.copy(isSleepingManual = false) }
        modifyNeeds(satietyDelta = 28f, energyDelta = 5f, socialDelta = 4f)
        triggerReaction(
            PetMood.REACTION_LOVE,
            durationMs = 3800L,
            reason = "Пип вкусно поел кибер-печенье (+28 Сытость)"
        )
    }

    fun toggleSleepManual() {
        lastInteractionMillis = System.currentTimeMillis()
        haptics.tick()
        val nextSleep = !_uiState.value.isSleepingManual
        _uiState.update { it.copy(isSleepingManual = nextSleep) }
        triggerBaseReevaluation(forceOverwriteReaction = true)
    }

    fun playBoostFromMiniGame(won: Boolean, gameName: String) {
        lastInteractionMillis = System.currentTimeMillis()
        modifyNeeds(funDelta = 22f, socialDelta = 10f, energyDelta = -4f, walkDelta = 5f)
        val outcomeMood = if (won) PetMood.REACTION_WIN_CELEBRATE else PetMood.REACTION_LOSE_SULK
        lastGameOutcomeMood = outcomeMood
        lastGameOutcomeExpiresAt = System.currentTimeMillis() + 25_000L

        if (won) {
            haptics.victoryFanfare()
            _uiState.update { it.copy(ticTacToeWins = it.ticTacToeWins + 1) }
            triggerReaction(
                PetMood.REACTION_WIN_CELEBRATE,
                durationMs = 4500L,
                reason = "Сыграли в «$gameName» — яркие эмоции и +22 к Веселью!"
            )
        } else {
            haptics.pokeBump()
            triggerReaction(
                PetMood.REACTION_LOSE_SULK,
                durationMs = 4200L,
                reason = "Пип уступил в «$gameName» и смешно дуется (+Веселье)"
            )
        }
    }

    fun onMazeCompleted() {
        lastInteractionMillis = System.currentTimeMillis()
        haptics.victoryFanfare()
        modifyNeeds(funDelta = 18f, walkDelta = 16f)
        _uiState.update { it.copy(mazeClearedCount = it.mazeClearedCount + 1) }
        triggerReaction(
            PetMood.REACTION_WIN_CELEBRATE,
            durationMs = 3800L,
            reason = "Гиро-лабиринт пройден наклонами телефона! (+Веселье, +Прогулка)"
        )
    }

    fun modifyNeeds(
        satietyDelta: Float = 0f,
        energyDelta: Float = 0f,
        funDelta: Float = 0f,
        socialDelta: Float = 0f,
        walkDelta: Float = 0f
    ) {
        _uiState.update { state ->
            val n = state.needs
            state.copy(
                needs = n.copy(
                    satiety = (n.satiety + satietyDelta).coerceIn(0f, 100f),
                    energy = (n.energy + energyDelta).coerceIn(0f, 100f),
                    funLevel = (n.funLevel + funDelta).coerceIn(0f, 100f),
                    social = (n.social + socialDelta).coerceIn(0f, 100f),
                    walk = (n.walk + walkDelta).coerceIn(0f, 100f)
                )
            )
        }
        saveStateToPrefs()
    }

    fun setNeedExact(needType: String, value: Float) {
        val v = value.coerceIn(0f, 100f)
        _uiState.update { state ->
            val n = state.needs
            val updated = when (needType) {
                "satiety" -> n.copy(satiety = v)
                "energy" -> n.copy(energy = v)
                "fun" -> n.copy(funLevel = v)
                "social" -> n.copy(social = v)
                "walk" -> n.copy(walk = v)
                else -> n
            }
            state.copy(needs = updated)
        }
        triggerBaseReevaluation(forceOverwriteReaction = true)
    }

    fun setDebugForcedMood(mood: PetMood?) {
        reactionResetJob?.cancel()
        _uiState.update { it.copy(forcedDebugMood = mood) }
        triggerBaseReevaluation(forceOverwriteReaction = true)
    }

    fun setEyeColor(hex: Long) {
        _uiState.update { it.copy(eyeColorHex = hex) }
        saveStateToPrefs()
    }

    fun updateBadge(newBadge: OwnerBadge) {
        _uiState.update { it.copy(badge = newBadge) }
        saveStateToPrefs()
    }

    private fun appendMoodLog(mood: PetMood, reason: String) {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val entry = MoodLogEntry(fmt.format(Date()), mood, reason)
        _uiState.update { state ->
            val currentList = state.moodHistory
            if (currentList.firstOrNull()?.mood == mood) return@update state
            state.copy(moodHistory = (listOf(entry) + currentList).take(18))
        }
    }

    private fun loadInitialState(): PetUiState {
        val needs = PetNeeds(
            satiety = prefs.getFloat("satiety", 78f),
            energy = prefs.getFloat("energy", 84f),
            funLevel = prefs.getFloat("funLevel", 72f),
            social = prefs.getFloat("social", 80f),
            walk = prefs.getFloat("walk", 60f)
        )
        val badge = OwnerBadge(
            ownerName = prefs.getString("ownerName", "ALEX // CYBER-DEV") ?: "ALEX // CYBER-DEV",
            ownerRole = prefs.getString("ownerRole", "Senior Android Architect") ?: "Senior Android Architect",
            statusLine = prefs.getString("statusLine", "ONLINE • PIP-OS v2.6") ?: "ONLINE • PIP-OS v2.6",
            telegramUrl = prefs.getString("telegramUrl", "https://t.me/durov") ?: "https://t.me/durov",
            githubUrl = prefs.getString("githubUrl", "https://github.com/JetBrains/kotlin") ?: "https://github.com/JetBrains/kotlin",
            customQrText = prefs.getString("customQrText", "PIP-POCKET-PET://COMPANION-UNIT-01") ?: "PIP-POCKET-PET://COMPANION-UNIT-01"
        )
        return PetUiState(
            needs = needs,
            eyeColorHex = prefs.getLong("eyeColorHex", 0xFFF5F7FA),
            badge = badge,
            ticTacToeWins = prefs.getInt("ticTacToeWins", 0),
            mazeClearedCount = prefs.getInt("mazeClearedCount", 0)
        )
    }

    private fun saveStateToPrefs() {
        val s = _uiState.value
        prefs.edit()
            .putFloat("satiety", s.needs.satiety)
            .putFloat("energy", s.needs.energy)
            .putFloat("funLevel", s.needs.funLevel)
            .putFloat("social", s.needs.social)
            .putFloat("walk", s.needs.walk)
            .putLong("eyeColorHex", s.eyeColorHex)
            .putString("ownerName", s.badge.ownerName)
            .putString("ownerRole", s.badge.ownerRole)
            .putString("statusLine", s.badge.statusLine)
            .putString("telegramUrl", s.badge.telegramUrl)
            .putString("githubUrl", s.badge.githubUrl)
            .putString("customQrText", s.badge.customQrText)
            .putInt("ticTacToeWins", s.ticTacToeWins)
            .putInt("mazeClearedCount", s.mazeClearedCount)
            .apply()
    }

    override fun onCleared() {
        super.onCleared()
        sensorHub.stopSensors()
    }
}
