package com.pippocket.pet

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.*
import kotlin.random.Random

/**
 * 28 состояний/эмоций Пипа:
 * 10 мгновенных реакций (Reactions) + 18 базовых состояний (Base Moods)
 */
enum class PetMood(val titleRu: String, val isReaction: Boolean) {
    // --- МГНОВЕННЫЕ РЕАКЦИИ (перебивают базовое настроение на 3-5 сек) ---
    REACTION_BLISS("Блаженство (Поглаживание)", true),
    REACTION_POKE_LEFT("Попал в левый глаз!", true),
    REACTION_POKE_RIGHT("Попал в правый глаз!", true),
    REACTION_DIZZY("Головокружение (Тряска)", true),
    REACTION_FREEFALL("Паника! Свободное падение!", true),
    REACTION_WIND_BLOW("Встречный ветер (Дуновение)", true),
    REACTION_STARTLED("Испуг от резкого звука!", true),
    REACTION_LOVE("Обожание (Вкусняшка/Любовь)", true),
    REACTION_WIN_CELEBRATE("Триумф! Победа в игре!", true),
    REACTION_LOSE_SULK("Обида от проигрыша", true),

    // --- БАЗОВЫЕ СОСТОЯНИЯ (вычисляются по дереву приоритетов) ---
    SLEEP_DEEP("Глубокий сон", false),
    SLEEP_DROWSY("Дремота (Сонливость)", false),
    BATTERY_LOW("Разряжен (<20% батареи)", false),
    BATTERY_CHARGING("Питание от сети (Зарядка)", false),
    STARVING_ANGRY("Голодная злость!", false),
    HUNGRY("Голодный (Просит еду)", false),
    LONELY("Одиноко (Нехватка внимания)", false),
    BORED("Скука (Хочет играть)", false),
    RESTLESS("Непоседа (Мало шагов)", false),
    NOISY_ANNOYED("Раздражен громким шумом", false),
    FOCUSED("Сфокусирован", false),
    ECSTATIC("Восторг (Идеальный уход)", false),
    HAPPY("Счастлив и бодр", false),
    CURIOUS("Любопытство", false),
    THINKING("Задумчивость", false),
    MISCHIEVOUS("Хитрость", false),
    SKEPTICAL("Скепсис", false),
    ZEN("Дзен и спокойствие", false)
}

/**
 * Параметрическая геометрия одного глаза
 */
data class EyeTarget(
    val widthScale: Float = 1.0f,       // Множитель ширины (0.2 .. 1.6)
    val heightScale: Float = 1.0f,      // Множитель высоты (0.05 .. 1.6)
    val offsetX: Float = 0f,            // Смещение взгляда по X (-1.0 .. 1.0)
    val offsetY: Float = 0f,            // Смещение взгляда по Y (-1.0 .. 1.0)
    val topRadius: Float = 0.42f,       // Скругление верхних углов (0.0 .. 0.5)
    val bottomRadius: Float = 0.42f,    // Скругление нижних углов (0.0 .. 0.5)
    val topLidClose: Float = 0.0f,      // Прикрытие верхнего века (0.0 .. 1.0)
    val topLidSlope: Float = 0.0f,      // Наклон верхнего века (-1.0 наружу .. +1.0 внутрь)
    val bottomLidClose: Float = 0.0f,   // Прикрытие нижнего века (0.0 .. 1.0)
    val bottomLidSlope: Float = 0.0f,   // Наклон нижнего века (-1.0 .. +1.0)
    val happyArch: Float = 0.0f,        // Дуга радости снизу ^ ^ (0.0 .. 1.0)
    val squishX: Float = 1.0f,          // Эластичное сжатие по X
    val squishY: Float = 1.0f,          // Эластичное сжатие по Y
    val pupilScale: Float = 0.0f        // Блик/зрачок внутри глаза (0 = чистый ретро-глаз, >0 = блик)
)

/**
 * Композитная цель для обоих глаз и процедурных частиц вокруг лица
 */
data class FaceTarget(
    val left: EyeTarget = EyeTarget(),
    val right: EyeTarget = EyeTarget(),
    val jitterAmount: Float = 0f,       // Дрожание глаз (паника, злость)
    val bounceSpeed: Float = 0f,        // Частота покачивания вверх-вниз
    val bounceAmp: Float = 0f,          // Амплитуда покачивания
    val showHearts: Boolean = false,
    val showZzz: Boolean = false,
    val showSweat: Boolean = false,
    val showDizzy: Boolean = false,
    val showAngerVein: Boolean = false,
    val showWindLines: Boolean = false,
    val showLightning: Boolean = false,
    val showSparks: Boolean = false,
    val showBlush: Boolean = false
)

/**
 * Одномерная физическая пружина с затуханием (Гука + Демпфер)
 */
class Spring1D(
    var value: Float,
    var target: Float = value,
    var velocity: Float = 0f
) {
    fun update(dt: Float, stiffness: Float = 180f, damping: Float = 18f) {
        val safeDt = dt.coerceIn(0.001f, 0.033f)
        val force = -stiffness * (value - target) - damping * velocity
        velocity += force * safeDt
        value += velocity * safeDt
    }

    fun impulse(deltaVelocity: Float) {
        velocity += deltaVelocity
    }
}

/**
 * Пружинное состояние одного глаза (все параметры интерполируются через физику)
 */
class EyeSpringState(initial: EyeTarget = EyeTarget()) {
    val widthScale = Spring1D(initial.widthScale)
    val heightScale = Spring1D(initial.heightScale)
    val offsetX = Spring1D(initial.offsetX)
    val offsetY = Spring1D(initial.offsetY)
    val topRadius = Spring1D(initial.topRadius)
    val bottomRadius = Spring1D(initial.bottomRadius)
    val topLidClose = Spring1D(initial.topLidClose)
    val topLidSlope = Spring1D(initial.topLidSlope)
    val bottomLidClose = Spring1D(initial.bottomLidClose)
    val bottomLidSlope = Spring1D(initial.bottomLidSlope)
    val happyArch = Spring1D(initial.happyArch)
    val squishX = Spring1D(initial.squishX)
    val squishY = Spring1D(initial.squishY)
    val pupilScale = Spring1D(initial.pupilScale)

    fun setTarget(
        t: EyeTarget,
        extraX: Float = 0f,
        extraY: Float = 0f,
        blinkFactor: Float = 0f
    ) {
        widthScale.target = t.widthScale * (1f + blinkFactor * 0.18f)
        heightScale.target = (t.heightScale * (1f - blinkFactor * 0.92f)).coerceAtLeast(0.04f)
        offsetX.target = (t.offsetX + extraX).coerceIn(-1.35f, 1.35f)
        offsetY.target = (t.offsetY + extraY).coerceIn(-1.35f, 1.35f)
        topRadius.target = t.topRadius
        bottomRadius.target = t.bottomRadius
        topLidClose.target = t.topLidClose
        topLidSlope.target = t.topLidSlope
        bottomLidClose.target = t.bottomLidClose
        bottomLidSlope.target = t.bottomLidSlope
        happyArch.target = t.happyArch * (1f - blinkFactor)
        squishX.target = t.squishX
        squishY.target = t.squishY
        pupilScale.target = t.pupilScale
    }

    fun step(dt: Float, stiffness: Float = 190f, damping: Float = 19f) {
        widthScale.update(dt, stiffness, damping)
        heightScale.update(dt, stiffness * 1.2f, damping * 1.1f)
        offsetX.update(dt, stiffness * 0.9f, damping)
        offsetY.update(dt, stiffness * 0.9f, damping)
        topRadius.update(dt, stiffness, damping)
        bottomRadius.update(dt, stiffness, damping)
        topLidClose.update(dt, stiffness * 1.15f, damping)
        topLidSlope.update(dt, stiffness, damping)
        bottomLidClose.update(dt, stiffness * 1.15f, damping)
        bottomLidSlope.update(dt, stiffness, damping)
        happyArch.update(dt, stiffness, damping)
        squishX.update(dt, stiffness * 1.4f, damping * 0.8f)
        squishY.update(dt, stiffness * 1.4f, damping * 0.8f)
        pupilScale.update(dt, stiffness, damping)
    }

    fun snapshot(): EyeTarget = EyeTarget(
        widthScale = widthScale.value,
        heightScale = heightScale.value,
        offsetX = offsetX.value,
        offsetY = offsetY.value,
        topRadius = topRadius.value,
        bottomRadius = bottomRadius.value,
        topLidClose = topLidClose.value,
        topLidSlope = topLidSlope.value,
        bottomLidClose = bottomLidClose.value,
        bottomLidSlope = bottomLidSlope.value,
        happyArch = happyArch.value,
        squishX = squishX.value,
        squishY = squishY.value,
        pupilScale = pupilScale.value
    )
}

/**
 * Каталог всех 28 эмоций Пипа с точной геометрией правого и левого глаза
 */
object MoodCatalog {
    fun getTarget(mood: PetMood): FaceTarget = when (mood) {
        // 1. Блаженство от поглаживания
        PetMood.REACTION_BLISS -> FaceTarget(
            left = EyeTarget(widthScale = 1.12f, heightScale = 0.78f, happyArch = 0.85f, topRadius = 0.5f),
            right = EyeTarget(widthScale = 1.12f, heightScale = 0.78f, happyArch = 0.85f, topRadius = 0.5f),
            bounceSpeed = 4.5f, bounceAmp = 0.08f, showHearts = true, showBlush = true
        )
        // 2. Попал пальцем в левый глаз
        PetMood.REACTION_POKE_LEFT -> FaceTarget(
            left = EyeTarget(widthScale = 1.25f, heightScale = 0.14f, topLidClose = 0.4f, bottomLidClose = 0.4f, squishX = 1.2f, squishY = 0.4f),
            right = EyeTarget(widthScale = 1.05f, heightScale = 1.15f, offsetX = -0.45f, topLidSlope = 0.65f, topLidClose = 0.22f),
            jitterAmount = 0.04f, showAngerVein = true
        )
        // 3. Попал пальцем в правый глаз
        PetMood.REACTION_POKE_RIGHT -> FaceTarget(
            left = EyeTarget(widthScale = 1.05f, heightScale = 1.15f, offsetX = 0.45f, topLidSlope = 0.65f, topLidClose = 0.22f),
            right = EyeTarget(widthScale = 1.25f, heightScale = 0.14f, topLidClose = 0.4f, bottomLidClose = 0.4f, squishX = 1.2f, squishY = 0.4f),
            jitterAmount = 0.04f, showAngerVein = true
        )
        // 4. Головокружение от тряски
        PetMood.REACTION_DIZZY -> FaceTarget(
            left = EyeTarget(widthScale = 1.3f, heightScale = 1.3f, topRadius = 0.5f, bottomRadius = 0.5f, offsetY = -0.2f),
            right = EyeTarget(widthScale = 0.65f, heightScale = 0.65f, topRadius = 0.5f, bottomRadius = 0.5f, offsetY = 0.25f),
            jitterAmount = 0.06f, showDizzy = true
        )
        // 5. Свободное падение / паника
        PetMood.REACTION_FREEFALL -> FaceTarget(
            left = EyeTarget(widthScale = 1.38f, heightScale = 1.48f, topRadius = 0.5f, bottomRadius = 0.5f, pupilScale = 0.35f),
            right = EyeTarget(widthScale = 1.38f, heightScale = 1.48f, topRadius = 0.5f, bottomRadius = 0.5f, pupilScale = 0.35f),
            jitterAmount = 0.14f, showSweat = true
        )
        // 6. Дуновение в микрофон (ветер в лицо)
        PetMood.REACTION_WIND_BLOW -> FaceTarget(
            left = EyeTarget(widthScale = 1.18f, heightScale = 0.42f, topLidClose = 0.35f, bottomLidClose = 0.35f, topLidSlope = -0.25f, offsetY = 0.15f),
            right = EyeTarget(widthScale = 1.18f, heightScale = 0.42f, topLidClose = 0.35f, bottomLidClose = 0.35f, topLidSlope = -0.25f, offsetY = 0.15f),
            jitterAmount = 0.05f, showWindLines = true
        )
        // 7. Испуг от хлопка
        PetMood.REACTION_STARTLED -> FaceTarget(
            left = EyeTarget(widthScale = 1.25f, heightScale = 1.35f, offsetY = -0.35f, topRadius = 0.48f, bottomRadius = 0.48f, pupilScale = 0.28f),
            right = EyeTarget(widthScale = 1.25f, heightScale = 1.35f, offsetY = -0.35f, topRadius = 0.48f, bottomRadius = 0.48f, pupilScale = 0.28f),
            jitterAmount = 0.09f, showSweat = true
        )
        // 8. Любовь / Вкусняшка
        PetMood.REACTION_LOVE -> FaceTarget(
            left = EyeTarget(widthScale = 1.15f, heightScale = 1.22f, happyArch = 0.55f, topRadius = 0.48f, pupilScale = 0.4f),
            right = EyeTarget(widthScale = 1.15f, heightScale = 1.22f, happyArch = 0.55f, topRadius = 0.48f, pupilScale = 0.4f),
            bounceSpeed = 6f, bounceAmp = 0.12f, showHearts = true, showBlush = true
        )
        // 9. Победа в мини-игре
        PetMood.REACTION_WIN_CELEBRATE -> FaceTarget(
            left = EyeTarget(widthScale = 1.18f, heightScale = 0.95f, happyArch = 0.9f, offsetY = -0.15f),
            right = EyeTarget(widthScale = 1.18f, heightScale = 0.95f, happyArch = 0.9f, offsetY = -0.15f),
            bounceSpeed = 8.5f, bounceAmp = 0.18f, showSparks = true, showBlush = true
        )
        // 10. Обида после проигрыша
        PetMood.REACTION_LOSE_SULK -> FaceTarget(
            left = EyeTarget(widthScale = 1.0f, heightScale = 0.72f, offsetX = -0.5f, offsetY = 0.2f, topLidClose = 0.38f, topLidSlope = 0.55f, bottomLidClose = 0.2f),
            right = EyeTarget(widthScale = 1.0f, heightScale = 0.72f, offsetX = -0.5f, offsetY = 0.2f, topLidClose = 0.38f, topLidSlope = 0.55f, bottomLidClose = 0.2f),
            showAngerVein = true
        )
        // 11. Глубокий сон
        PetMood.SLEEP_DEEP -> FaceTarget(
            left = EyeTarget(widthScale = 1.05f, heightScale = 0.12f, offsetY = 0.35f, topRadius = 0.5f, bottomRadius = 0.5f),
            right = EyeTarget(widthScale = 1.05f, heightScale = 0.12f, offsetY = 0.35f, topRadius = 0.5f, bottomRadius = 0.5f),
            bounceSpeed = 1.4f, bounceAmp = 0.04f, showZzz = true
        )
        // 12. Дремота
        PetMood.SLEEP_DROWSY -> FaceTarget(
            left = EyeTarget(widthScale = 1.0f, heightScale = 0.65f, offsetY = 0.2f, topLidClose = 0.58f, topLidSlope = -0.2f),
            right = EyeTarget(widthScale = 1.0f, heightScale = 0.65f, offsetY = 0.2f, topLidClose = 0.58f, topLidSlope = -0.2f),
            bounceSpeed = 1.8f, bounceAmp = 0.05f, showZzz = true
        )
        // 13. Низкий заряд батареи (<20%)
        PetMood.BATTERY_LOW -> FaceTarget(
            left = EyeTarget(widthScale = 0.95f, heightScale = 0.75f, offsetY = 0.28f, topLidClose = 0.48f, topLidSlope = -0.65f),
            right = EyeTarget(widthScale = 0.95f, heightScale = 0.75f, offsetY = 0.28f, topLidClose = 0.48f, topLidSlope = -0.65f),
            jitterAmount = 0.02f, showSweat = true
        )
        // 14. Зарядка подключена
        PetMood.BATTERY_CHARGING -> FaceTarget(
            left = EyeTarget(widthScale = 1.12f, heightScale = 1.12f, happyArch = 0.45f, pupilScale = 0.32f),
            right = EyeTarget(widthScale = 1.12f, heightScale = 1.12f, happyArch = 0.45f, pupilScale = 0.32f),
            bounceSpeed = 4.0f, bounceAmp = 0.07f, showLightning = true, showBlush = true
        )
        // 15. Голодная злость
        PetMood.STARVING_ANGRY -> FaceTarget(
            left = EyeTarget(widthScale = 1.1f, heightScale = 0.88f, topLidClose = 0.38f, topLidSlope = 0.85f, bottomLidClose = 0.18f),
            right = EyeTarget(widthScale = 1.1f, heightScale = 0.88f, topLidClose = 0.38f, topLidSlope = 0.85f, bottomLidClose = 0.18f),
            jitterAmount = 0.045f, showAngerVein = true
        )
        // 16. Голодный (щенячьи глаза)
        PetMood.HUNGRY -> FaceTarget(
            left = EyeTarget(widthScale = 1.12f, heightScale = 1.18f, offsetY = -0.22f, topLidClose = 0.22f, topLidSlope = -0.55f, pupilScale = 0.38f),
            right = EyeTarget(widthScale = 1.12f, heightScale = 1.18f, offsetY = -0.22f, topLidClose = 0.22f, topLidSlope = -0.55f, pupilScale = 0.38f),
            showSweat = true
        )
        // 17. Одиноко
        PetMood.LONELY -> FaceTarget(
            left = EyeTarget(widthScale = 0.95f, heightScale = 0.92f, offsetY = 0.22f, topLidClose = 0.35f, topLidSlope = -0.75f, bottomLidClose = 0.1f),
            right = EyeTarget(widthScale = 0.95f, heightScale = 0.92f, offsetY = 0.22f, topLidClose = 0.35f, topLidSlope = -0.75f, bottomLidClose = 0.1f),
            showSweat = true
        )
        // 18. Скука
        PetMood.BORED -> FaceTarget(
            left = EyeTarget(widthScale = 1.05f, heightScale = 0.72f, offsetY = -0.15f, topLidClose = 0.45f, topRadius = 0.2f),
            right = EyeTarget(widthScale = 1.05f, heightScale = 0.72f, offsetY = -0.15f, topLidClose = 0.45f, topRadius = 0.2f)
        )
        // 19. Непоседа (нужна прогулка)
        PetMood.RESTLESS -> FaceTarget(
            left = EyeTarget(widthScale = 1.08f, heightScale = 1.12f, offsetX = 0.35f, pupilScale = 0.25f),
            right = EyeTarget(widthScale = 1.08f, heightScale = 1.12f, offsetX = 0.35f, pupilScale = 0.25f),
            bounceSpeed = 6.5f, bounceAmp = 0.09f
        )
        // 20. Раздражен шумом
        PetMood.NOISY_ANNOYED -> FaceTarget(
            left = EyeTarget(widthScale = 1.12f, heightScale = 0.58f, topLidClose = 0.32f, topLidSlope = 0.6f, bottomLidClose = 0.32f),
            right = EyeTarget(widthScale = 1.12f, heightScale = 0.58f, topLidClose = 0.32f, topLidSlope = 0.6f, bottomLidClose = 0.32f),
            jitterAmount = 0.035f, showAngerVein = true
        )
        // 21. Сфокусирован
        PetMood.FOCUSED -> FaceTarget(
            left = EyeTarget(widthScale = 1.08f, heightScale = 0.85f, topLidClose = 0.24f, topLidSlope = 0.35f, bottomLidClose = 0.18f),
            right = EyeTarget(widthScale = 1.08f, heightScale = 0.85f, topLidClose = 0.24f, topLidSlope = 0.35f, bottomLidClose = 0.18f)
        )
        // 22. Восторг
        PetMood.ECSTATIC -> FaceTarget(
            left = EyeTarget(widthScale = 1.15f, heightScale = 1.15f, happyArch = 0.65f, pupilScale = 0.35f),
            right = EyeTarget(widthScale = 1.15f, heightScale = 1.15f, happyArch = 0.65f, pupilScale = 0.35f),
            bounceSpeed = 5.2f, bounceAmp = 0.1f, showSparks = true, showBlush = true
        )
        // 23. Счастлив (Базовая норма)
        PetMood.HAPPY -> FaceTarget(
            left = EyeTarget(widthScale = 1.0f, heightScale = 1.0f, happyArch = 0.22f, topRadius = 0.44f, bottomRadius = 0.44f),
            right = EyeTarget(widthScale = 1.0f, heightScale = 1.0f, happyArch = 0.22f, topRadius = 0.44f, bottomRadius = 0.44f),
            bounceSpeed = 2.6f, bounceAmp = 0.04f
        )
        // 24. Любопытство (асимметричные глаза)
        PetMood.CURIOUS -> FaceTarget(
            left = EyeTarget(widthScale = 1.18f, heightScale = 1.24f, offsetY = -0.08f, topRadius = 0.48f, pupilScale = 0.25f),
            right = EyeTarget(widthScale = 0.88f, heightScale = 0.78f, offsetY = 0.08f, topLidClose = 0.22f, bottomLidClose = 0.15f),
            bounceSpeed = 2.2f, bounceAmp = 0.04f
        )
        // 25. Задумчивость
        PetMood.THINKING -> FaceTarget(
            left = EyeTarget(widthScale = 0.96f, heightScale = 0.82f, offsetX = 0.42f, offsetY = -0.35f, topLidClose = 0.25f, bottomLidClose = 0.25f),
            right = EyeTarget(widthScale = 1.05f, heightScale = 1.08f, offsetX = 0.42f, offsetY = -0.35f, topLidSlope = -0.25f)
        )
        // 26. Хитрость
        PetMood.MISCHIEVOUS -> FaceTarget(
            left = EyeTarget(widthScale = 1.08f, heightScale = 0.74f, offsetX = 0.3f, topLidClose = 0.28f, topLidSlope = 0.48f, happyArch = 0.45f),
            right = EyeTarget(widthScale = 1.08f, heightScale = 0.74f, offsetX = 0.3f, topLidClose = 0.28f, topLidSlope = 0.48f, happyArch = 0.45f)
        )
        // 27. Скепсис (одна бровь приподнята)
        PetMood.SKEPTICAL -> FaceTarget(
            left = EyeTarget(widthScale = 1.04f, heightScale = 0.68f, topLidClose = 0.42f, topLidSlope = 0.2f, bottomLidClose = 0.18f),
            right = EyeTarget(widthScale = 1.08f, heightScale = 1.18f, offsetY = -0.12f, topLidSlope = -0.45f)
        )
        // 28. Дзен
        PetMood.ZEN -> FaceTarget(
            left = EyeTarget(widthScale = 1.05f, heightScale = 0.62f, happyArch = 0.55f, topLidClose = 0.25f),
            right = EyeTarget(widthScale = 1.05f, heightScale = 0.62f, happyArch = 0.55f, topLidClose = 0.25f),
            bounceSpeed = 1.5f, bounceAmp = 0.035f
        )
    }
}

/**
 * Главный процедурный Canvas-рендерер лица Пипа (60-120 FPS).
 * Включает физику пружин, авто-моргание, саккады, слежение за пальцем,
 * параллакс от гироскопа/акселерометра и распознавание поглаживаний/тапов.
 */
@Composable
fun ProceduralFaceCanvas(
    mood: PetMood,
    tiltOffset: Offset,
    eyeColor: Color = Color(0xFFF5F7FA),
    accentColor: Color = Color(0xFF00F0FF),
    modifier: Modifier = Modifier,
    isMiniPreview: Boolean = false,
    onPokeEye: (isLeft: Boolean) -> Unit = {},
    onPetStroke: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    onFpsUpdate: (Int) -> Unit = {}
) {
    val leftSpring = remember { EyeSpringState() }
    val rightSpring = remember { EyeSpringState() }

    // Состояния живого поведения в покое
    var saccadeOffset by remember { mutableStateOf(Offset.Zero) }
    var nextSaccadeTime by remember { mutableLongStateOf(0L) }
    var nextBlinkTime by remember { mutableLongStateOf(0L) }
    var blinkEndTime by remember { mutableLongStateOf(0L) }

    // Взаимодействие с тачскрином
    var touchTargetOffset by remember { mutableStateOf<Offset?>(null) }
    var strokeDistanceAccum by remember { mutableFloatStateOf(0f) }
    var lastDragSignX by remember { mutableIntStateOf(0) }
    var directionReversals by remember { mutableIntStateOf(0) }

    // Время анимации и счетчик FPS
    var animTimeSec by remember { mutableFloatStateOf(0f) }
    var leftSnapshot by remember { mutableStateOf(EyeTarget()) }
    var rightSnapshot by remember { mutableStateOf(EyeTarget()) }
    val faceTarget = remember(mood) { MoodCatalog.getTarget(mood) }

    LaunchedEffect(mood) {
        // Небольшой упругий импульс при смене эмоции
        leftSpring.heightScale.impulse(2.4f)
        rightSpring.heightScale.impulse(2.4f)
    }

    LaunchedEffect(Unit) {
        var lastFrameNanos = 0L
        var fpsFrames = 0
        var fpsLastMarkMillis = 0L

        while (true) {
            withInfiniteAnimationFrameMillis { frameMillis ->
                val nowNanos = frameMillis * 1_000_000L
                val dt = if (lastFrameNanos == 0L) 0.016f else ((nowNanos - lastFrameNanos) / 1_000_000_000f)
                lastFrameNanos = nowNanos
                animTimeSec += dt.coerceIn(0.001f, 0.05f)

                // FPS мониторинг
                fpsFrames++
                if (frameMillis - fpsLastMarkMillis >= 1000L) {
                    onFpsUpdate(fpsFrames)
                    fpsFrames = 0
                    fpsLastMarkMillis = frameMillis
                }

                // 1. Автоматическое моргание со случайным интервалом (2.2 .. 5.8 сек)
                if (nextBlinkTime == 0L) {
                    nextBlinkTime = frameMillis + Random.nextLong(2200L, 5500L)
                }
                if (frameMillis >= nextBlinkTime && mood != PetMood.SLEEP_DEEP) {
                    blinkEndTime = frameMillis + 135L
                    nextBlinkTime = frameMillis + Random.nextLong(2400L, 6200L)
                }
                val isBlinking = frameMillis < blinkEndTime
                val blinkFactor = if (isBlinking) 1f else 0f

                // 2. Случайные саккады (быстрые микродвижения взгляда)
                if (frameMillis >= nextSaccadeTime) {
                    saccadeOffset = if (mood == PetMood.SLEEP_DEEP || mood == PetMood.SLEEP_DROWSY) {
                        Offset.Zero
                    } else if (mood == PetMood.RESTLESS) {
                        Offset(Random.nextFloat() * 1.3f - 0.65f, Random.nextFloat() * 0.6f - 0.3f)
                    } else {
                        Offset(Random.nextFloat() * 0.56f - 0.28f, Random.nextFloat() * 0.36f - 0.18f)
                    }
                    val delay = if (mood == PetMood.RESTLESS) Random.nextLong(350L, 900L) else Random.nextLong(1200L, 3600L)
                    nextSaccadeTime = frameMillis + delay
                }

                // 3. Суммарное смещение взгляда (тач + наклон телефона + саккады + покачивание)
                val lookX: Float
                val lookY: Float
                val touch = touchTargetOffset
                if (touch != null) {
                    lookX = touch.x
                    lookY = touch.y
                } else {
                    val bounceY = if (faceTarget.bounceSpeed > 0f) {
                        sin(animTimeSec * faceTarget.bounceSpeed) * faceTarget.bounceAmp
                    } else 0f
                    lookX = saccadeOffset.x + (tiltOffset.x * 0.45f)
                    lookY = saccadeOffset.y + (tiltOffset.y * 0.45f) + bounceY
                }

                // Головокружение: вращение глаз по кругу в противофазе
                val dizzyLeftX = if (faceTarget.showDizzy) cos(animTimeSec * 7f) * 0.35f else 0f
                val dizzyLeftY = if (faceTarget.showDizzy) sin(animTimeSec * 7f) * 0.35f else 0f

                leftSpring.setTarget(
                    t = faceTarget.left,
                    extraX = lookX + dizzyLeftX,
                    extraY = lookY + dizzyLeftY,
                    blinkFactor = blinkFactor
                )
                rightSpring.setTarget(
                    t = faceTarget.right,
                    extraX = lookX - dizzyLeftX,
                    extraY = lookY - dizzyLeftY,
                    blinkFactor = blinkFactor
                )

                leftSpring.step(dt)
                rightSpring.step(dt)

                leftSnapshot = leftSpring.snapshot()
                rightSnapshot = rightSpring.snapshot()
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (isMiniPreview) Modifier else Modifier
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { onDoubleTap() },
                            onTap = { tapPos ->
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val eyeSpacing = w * 0.21f
                                val leftCenter = Offset(w * 0.5f - eyeSpacing, h * 0.46f)
                                val rightCenter = Offset(w * 0.5f + eyeSpacing, h * 0.46f)
                                val hitRadius = w * 0.18f

                                when {
                                    (tapPos - leftCenter).getDistance() <= hitRadius -> {
                                        leftSpring.squishY.impulse(-8f)
                                        leftSpring.squishX.impulse(5f)
                                        onPokeEye(true)
                                    }
                                    (tapPos - rightCenter).getDistance() <= hitRadius -> {
                                        rightSpring.squishY.impulse(-8f)
                                        rightSpring.squishX.impulse(5f)
                                        onPokeEye(false)
                                    }
                                    else -> {
                                        // Короткий перевод взгляда в точку касания
                                        val normX = ((tapPos.x - w * 0.5f) / (w * 0.4f)).coerceIn(-1f, 1f)
                                        val normY = ((tapPos.y - h * 0.46f) / (h * 0.4f)).coerceIn(-1f, 1f)
                                        saccadeOffset = Offset(normX * 0.75f, normY * 0.75f)
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                strokeDistanceAccum = 0f
                                directionReversals = 0
                                lastDragSignX = 0
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                touchTargetOffset = Offset(
                                    ((pos.x - w * 0.5f) / (w * 0.35f)).coerceIn(-1f, 1f),
                                    ((pos.y - h * 0.46f) / (h * 0.35f)).coerceIn(-1f, 1f)
                                )
                            },
                            onDragEnd = {
                                touchTargetOffset = null
                            },
                            onDragCancel = {
                                touchTargetOffset = null
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val pos = change.position
                                touchTargetOffset = Offset(
                                    ((pos.x - w * 0.5f) / (w * 0.35f)).coerceIn(-1f, 1f),
                                    ((pos.y - h * 0.46f) / (h * 0.35f)).coerceIn(-1f, 1f)
                                )

                                // Распознавание поглаживания (свайпы туда-сюда или длинное поглаживание)
                                strokeDistanceAccum += dragAmount.getDistance()
                                val signX = if (abs(dragAmount.x) > 3f) dragAmount.x.sign.toInt() else lastDragSignX
                                if (lastDragSignX != 0 && signX != 0 && signX != lastDragSignX) {
                                    directionReversals++
                                }
                                if (signX != 0) lastDragSignX = signX

                                if (directionReversals >= 2 || strokeDistanceAccum > w * 0.85f) {
                                    strokeDistanceAccum = 0f
                                    directionReversals = 0
                                    onPetStroke()
                                }
                            }
                        )
                    }
            )
    ) {
        val canvasW = size.width
        val canvasH = size.height
        val baseEyeW = canvasW * 0.25f
        val baseEyeH = (canvasW * 0.31f).coerceAtMost(canvasH * 0.48f)
        val eyeSpacing = canvasW * 0.205f
        val centerY = canvasH * 0.47f

        val jitterX = if (faceTarget.jitterAmount > 0f) {
            (Random.nextFloat() - 0.5f) * 2f * faceTarget.jitterAmount * baseEyeW
        } else 0f
        val jitterY = if (faceTarget.jitterAmount > 0f) {
            (Random.nextFloat() - 0.5f) * 2f * faceTarget.jitterAmount * baseEyeH
        } else 0f

        val leftCenter = Offset(
            x = canvasW * 0.5f - eyeSpacing + leftSnapshot.offsetX * (baseEyeW * 0.38f) + jitterX,
            y = centerY + leftSnapshot.offsetY * (baseEyeH * 0.35f) + jitterY
        )
        val rightCenter = Offset(
            x = canvasW * 0.5f + eyeSpacing + rightSnapshot.offsetX * (baseEyeW * 0.38f) - jitterX,
            y = centerY + rightSnapshot.offsetY * (baseEyeH * 0.35f) + jitterY
        )

        // Фоновый румянец на щечках при радости/любви
        if (faceTarget.showBlush) {
            drawOval(
                color = Color(0xFFFF4081).copy(alpha = 0.28f),
                topLeft = Offset(leftCenter.x - baseEyeW * 0.55f, leftCenter.y + baseEyeH * 0.48f),
                size = Size(baseEyeW * 0.65f, baseEyeH * 0.22f)
            )
            drawOval(
                color = Color(0xFFFF4081).copy(alpha = 0.28f),
                topLeft = Offset(rightCenter.x - baseEyeW * 0.1f, rightCenter.y + baseEyeH * 0.48f),
                size = Size(baseEyeW * 0.65f, baseEyeH * 0.22f)
            )
        }

        // Отрисовка левого и правого глаза
        drawProceduralEye(
            center = leftCenter,
            baseW = baseEyeW,
            baseH = baseEyeH,
            eye = leftSnapshot,
            isLeftEye = true,
            eyeColor = eyeColor
        )
        drawProceduralEye(
            center = rightCenter,
            baseW = baseEyeW,
            baseH = baseEyeH,
            eye = rightSnapshot,
            isLeftEye = false,
            eyeColor = eyeColor
        )

        // Отрисовка процедурных оверлеев эмоций
        drawEmotionOverlays(
            faceTarget = faceTarget,
            animTime = animTimeSec,
            leftCenter = leftCenter,
            rightCenter = rightCenter,
            baseEyeW = baseEyeW,
            baseEyeH = baseEyeH,
            accentColor = accentColor
        )
    }
}

/**
 * Процедурная отрисовка одного глаза с независимыми радиусами скругления,
 * наклоном верхнего/нижнего века, арочным прищуром и ретро-сканлайнами.
 */
private fun DrawScope.drawProceduralEye(
    center: Offset,
    baseW: Float,
    baseH: Float,
    eye: EyeTarget,
    isLeftEye: Boolean,
    eyeColor: Color
) {
    val w = (baseW * eye.widthScale * eye.squishX).coerceAtLeast(8f)
    val h = (baseH * eye.heightScale * eye.squishY).coerceAtLeast(4f)
    val rect = Rect(
        left = center.x - w * 0.5f,
        top = center.y - h * 0.5f,
        right = center.x + w * 0.5f,
        bottom = center.y + h * 0.5f
    )

    val minSide = min(w, h)
    val topR = CornerRadius((minSide * eye.topRadius).coerceAtLeast(2f))
    val botR = CornerRadius((minSide * eye.bottomRadius).coerceAtLeast(2f))

    val eyePath = Path().apply {
        addRoundRect(
            RoundRect(
                rect = rect,
                topLeft = topR,
                topRight = topR,
                bottomRight = botR,
                bottomLeft = botR
            )
        )
    }

    clipPath(eyePath) {
        // 1. Заливка основного тела глаза
        drawRect(
            color = eyeColor,
            topLeft = rect.topLeft,
            size = rect.size
        )

        // 2. Тонкие горизонтальные CRT-сканлайны для стиля ретро-приборки
        val stepY = (h / 14f).coerceAtLeast(4f)
        var scanY = rect.top
        while (scanY < rect.bottom) {
            drawLine(
                color = Color.Black.copy(alpha = 0.12f),
                start = Offset(rect.left, scanY),
                end = Offset(rect.right, scanY),
                strokeWidth = 1.5f
            )
            scanY += stepY
        }

        // 3. Зрачок / блик, если активен
        if (eye.pupilScale > 0.02f) {
            val glintRadius = minSide * 0.22f * eye.pupilScale
            drawCircle(
                color = Color.Black.copy(alpha = 0.85f),
                radius = glintRadius * 1.45f,
                center = Offset(center.x + w * 0.06f, center.y - h * 0.06f)
            )
            drawCircle(
                color = eyeColor,
                radius = glintRadius * 0.65f,
                center = Offset(center.x + w * 0.14f, center.y - h * 0.14f)
            )
        }

        // 4. Верхнее веко (скошенная маска цвета OLED-фона #000000)
        // Положительный topLidSlope наклоняет веко к переносице (злость \ /),
        // отрицательный — к краям лица (грусть / \).
        if (eye.topLidClose > 0.01f || abs(eye.topLidSlope) > 0.02f) {
            val slopeDelta = eye.topLidSlope * h * 0.48f
            val baseCutY = rect.top + h * eye.topLidClose
            val leftEdgeY = if (isLeftEye) baseCutY - slopeDelta * 0.5f else baseCutY + slopeDelta * 0.5f
            val rightEdgeY = if (isLeftEye) baseCutY + slopeDelta * 0.5f else baseCutY - slopeDelta * 0.5f

            val topMask = Path().apply {
                moveTo(rect.left - 4f, rect.top - h)
                lineTo(rect.right + 4f, rect.top - h)
                lineTo(rect.right + 4f, rightEdgeY)
                lineTo(rect.left - 4f, leftEdgeY)
                close()
            }
            drawPath(topMask, Color.Black)
        }

        // 5. Нижнее веко (скошенная маска)
        if (eye.bottomLidClose > 0.01f || abs(eye.bottomLidSlope) > 0.02f) {
            val slopeDelta = eye.bottomLidSlope * h * 0.45f
            val baseCutY = rect.bottom - h * eye.bottomLidClose
            val leftEdgeY = if (isLeftEye) baseCutY - slopeDelta * 0.5f else baseCutY + slopeDelta * 0.5f
            val rightEdgeY = if (isLeftEye) baseCutY + slopeDelta * 0.5f else baseCutY - slopeDelta * 0.5f

            val bottomMask = Path().apply {
                moveTo(rect.left - 4f, rect.bottom + h)
                lineTo(rect.right + 4f, rect.bottom + h)
                lineTo(rect.right + 4f, rightEdgeY)
                lineTo(rect.left - 4f, leftEdgeY)
                close()
            }
            drawPath(bottomMask, Color.Black)
        }

        // 6. Арочный прищур счастья снизу (^ ^)
        if (eye.happyArch > 0.02f) {
            val archHeight = h * eye.happyArch * 0.88f
            val archPath = Path().apply {
                moveTo(rect.left - w * 0.15f, rect.bottom + 4f)
                quadraticBezierTo(
                    center.x,
                    rect.bottom - archHeight * 1.35f,
                    rect.right + w * 0.15f,
                    rect.bottom + 4f
                )
                lineTo(rect.right + w * 0.15f, rect.bottom + h)
                lineTo(rect.left - w * 0.15f, rect.bottom + h)
                close()
            }
            drawPath(archPath, Color.Black)
        }
    }
}

/**
 * Процедурные векторные эффекты вокруг глаз (Zzz, сердечки, капли пота, ветер, молния, искры)
 */
private fun DrawScope.drawEmotionOverlays(
    faceTarget: FaceTarget,
    animTime: Float,
    leftCenter: Offset,
    rightCenter: Offset,
    baseEyeW: Float,
    baseEyeH: Float,
    accentColor: Color
) {
    val midX = (leftCenter.x + rightCenter.x) * 0.5f
    val topY = min(leftCenter.y, rightCenter.y) - baseEyeH * 0.65f

    // 1. Всплывающие сердечки
    if (faceTarget.showHearts) {
        for (i in 0..2) {
            val phase = (animTime * 0.85f + i * 0.33f) % 1f
            val hx = midX + sin(animTime * 3f + i * 2.1f) * baseEyeW * 0.7f
            val hy = topY - phase * baseEyeH * 0.7f
            val scale = (1f - phase * 0.4f) * baseEyeW * 0.16f
            val alpha = (1f - phase).coerceIn(0f, 1f)
            drawVectorHeart(Offset(hx, hy), scale, Color(0xFFFF3366).copy(alpha = alpha))
        }
    }

    // 2. Сонные буквы Zzz
    if (faceTarget.showZzz) {
        for (i in 0..2) {
            val phase = (animTime * 0.45f + i * 0.33f) % 1f
            val zx = rightCenter.x + baseEyeW * 0.35f + phase * baseEyeW * 0.55f
            val zy = topY - phase * baseEyeH * 0.75f
            val zSize = baseEyeW * (0.12f + phase * 0.12f)
            val alpha = (1f - phase).coerceIn(0f, 1f)
            val zPath = Path().apply {
                moveTo(zx - zSize, zy - zSize)
                lineTo(zx + zSize, zy - zSize)
                lineTo(zx - zSize, zy + zSize)
                lineTo(zx + zSize, zy + zSize)
            }
            drawPath(
                path = zPath,
                color = accentColor.copy(alpha = alpha),
                style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }

    // 3. Капля пота (паника, низкий заряд, одиночество)
    if (faceTarget.showSweat) {
        val dropSlide = ((animTime * 1.4f) % 1f) * baseEyeH * 0.28f
        val dropCenter = Offset(rightCenter.x + baseEyeW * 0.68f, rightCenter.y - baseEyeH * 0.35f + dropSlide)
        val r = baseEyeW * 0.13f
        val dropPath = Path().apply {
            moveTo(dropCenter.x, dropCenter.y - r * 1.7f)
            quadraticBezierTo(dropCenter.x + r * 1.2f, dropCenter.y, dropCenter.x, dropCenter.y + r)
            quadraticBezierTo(dropCenter.x - r * 1.2f, dropCenter.y, dropCenter.x, dropCenter.y - r * 1.7f)
            close()
        }
        drawPath(dropPath, Color(0xFF00E5FF).copy(alpha = 0.85f))
    }

    // 4. Жилка гнева (раздражение, голодная злость, тычок в глаз)
    if (faceTarget.showAngerVein) {
        val pulse = 1f + sin(animTime * 12f) * 0.18f
        val vx = leftCenter.x - baseEyeW * 0.52f
        val vy = leftCenter.y - baseEyeH * 0.58f
        val s = baseEyeW * 0.14f * pulse
        val veinColor = Color(0xFFFF3366)
        drawLine(veinColor, Offset(vx - s, vy - s * 0.3f), Offset(vx - s * 0.3f, vy - s * 0.3f), 5f)
        drawLine(veinColor, Offset(vx - s * 0.3f, vy - s), Offset(vx - s * 0.3f, vy - s * 0.3f), 5f)
        drawLine(veinColor, Offset(vx + s, vy + s * 0.3f), Offset(vx + s * 0.3f, vy + s * 0.3f), 5f)
        drawLine(veinColor, Offset(vx + s * 0.3f, vy + s), Offset(vx + s * 0.3f, vy + s * 0.3f), 5f)
    }

    // 5. Звездочки головокружения
    if (faceTarget.showDizzy) {
        for (i in 0..2) {
            val angle = animTime * 4.5f + i * (2f * PI.toFloat() / 3f)
            val sx = midX + cos(angle) * baseEyeW * 0.85f
            val sy = topY + sin(angle) * baseEyeH * 0.22f
            drawStarSpark(Offset(sx, sy), baseEyeW * 0.13f, Color(0xFFFFD600))
        }
    }

    // 6. Потоки ветра при дуновении в микрофон
    if (faceTarget.showWindLines) {
        for (i in 0..4) {
            val phase = (animTime * 3.2f + i * 0.21f) % 1f
            val wy = leftCenter.y - baseEyeH * 0.6f + i * (baseEyeH * 0.32f)
            val startX = size.width * (0.1f + phase * 0.65f)
            val len = baseEyeW * 0.55f
            drawLine(
                color = accentColor.copy(alpha = (1f - phase) * 0.7f),
                start = Offset(startX, wy),
                end = Offset(startX + len, wy),
                strokeWidth = 3.5f,
                cap = StrokeCap.Round
            )
        }
    }

    // 7. Молния зарядки
    if (faceTarget.showLightning) {
        val pulse = 0.85f + sin(animTime * 8f) * 0.15f
        val lx = midX
        val ly = topY - baseEyeH * 0.1f
        val s = baseEyeW * 0.22f * pulse
        val bolt = Path().apply {
            moveTo(lx + s * 0.2f, ly - s)
            lineTo(lx - s * 0.45f, ly + s * 0.1f)
            lineTo(lx + s * 0.05f, ly + s * 0.1f)
            lineTo(lx - s * 0.2f, ly + s)
            lineTo(lx + s * 0.45f, ly - s * 0.1f)
            lineTo(lx - s * 0.05f, ly - s * 0.1f)
            close()
        }
        drawPath(bolt, Color(0xFF00E676))
    }

    // 8. Искры восторга/победы
    if (faceTarget.showSparks) {
        for (i in 0..3) {
            val angle = animTime * 2.5f + i * (PI.toFloat() * 0.5f)
            val sx = midX + cos(angle) * baseEyeW * 1.15f
            val sy = (leftCenter.y + rightCenter.y) * 0.5f + sin(angle) * baseEyeH * 0.75f
            val pulse = (0.5f + 0.5f * sin(animTime * 9f + i)) * baseEyeW * 0.14f
            drawStarSpark(Offset(sx, sy), pulse, Color(0xFF00F0FF))
        }
    }
}

private fun DrawScope.drawVectorHeart(center: Offset, size: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y + size)
        cubicTo(
            center.x - size * 1.4f, center.y + size * 0.2f,
            center.x - size * 0.9f, center.y - size * 1.1f,
            center.x, center.y - size * 0.35f
        )
        cubicTo(
            center.x + size * 0.9f, center.y - size * 1.1f,
            center.x + size * 1.4f, center.y + size * 0.2f,
            center.x, center.y + size
        )
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawStarSpark(center: Offset, radius: Float, color: Color) {
    rotate(degrees = 0f, pivot = center) {
        drawLine(color, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 3.5f, StrokeCap.Round)
        drawLine(color, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 3.5f, StrokeCap.Round)
    }
}
