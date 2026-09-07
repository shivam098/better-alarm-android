package com.alarmy.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlarmModelTest {

    @Test
    fun `weekday summaries read naturally`() {
        assertEquals("Once", emptySet<Weekday>().summary())
        assertEquals("Every day", Weekday.entries.toSet().summary())
        assertEquals("Weekdays", Weekday.WEEKDAYS.summary())
        assertEquals("Weekends", Weekday.WEEKEND.summary())
        assertEquals(
            "Mon, Wed, Fri",
            setOf(Weekday.MONDAY, Weekday.WEDNESDAY, Weekday.FRIDAY).summary()
        )
    }

    @Test
    fun `weekday summaries are ordered Monday first regardless of insertion order`() {
        val shuffled = setOf(Weekday.FRIDAY, Weekday.MONDAY, Weekday.WEDNESDAY)
        assertEquals("Mon, Wed, Fri", shuffled.summary())
    }

    @Test
    fun `weekdays map to and from java time without an off-by-one`() {
        Weekday.entries.forEach { day ->
            assertEquals(day, Weekday.from(day.dayOfWeek))
            assertEquals(day.iso, day.dayOfWeek.value)
        }
    }

    @Test
    fun `an alarm with a mission and a guard window is guarded`() {
        val alarm = Alarm(mission = MissionConfig(MissionType.MATH, MissionDifficulty.MEDIUM, 3))
        assertTrue(alarm.isGuarded)
    }

    @Test
    fun `an alarm without a mission is never guarded`() {
        val alarm = Alarm(mission = MissionConfig(MissionType.NONE, MissionDifficulty.MEDIUM, 0))
        assertFalse(alarm.isGuarded)
    }

    @Test
    fun `a zero-length guard window disables the guard`() {
        val alarm = Alarm(missionGuard = MissionGuardSettings(windowMinutes = 0))
        assertFalse(alarm.isGuarded)
    }

    @Test
    fun `an explicit fallback is honoured when it is usable`() {
        val alarm = Alarm(
            mission = MissionConfig(MissionType.QR, MissionDifficulty.MEDIUM, 1),
            fallbackMission = MissionType.TYPING
        )

        assertEquals(MissionType.TYPING, alarm.resolvedFallback(setOf(MissionPermission.CAMERA)))
    }

    @Test
    fun `an unusable explicit fallback is skipped`() {
        val alarm = Alarm(
            mission = MissionConfig(MissionType.QR, MissionDifficulty.MEDIUM, 1),
            fallbackMission = MissionType.PHOTO
        )

        val resolved = alarm.resolvedFallback(setOf(MissionPermission.CAMERA))

        assertFalse(resolved == MissionType.PHOTO)
        assertTrue(resolved.requiredPermissions.none { it == MissionPermission.CAMERA })
    }

    @Test
    fun `the fallback chain always terminates somewhere usable`() {
        val everythingDenied = MissionPermission.entries.toSet()

        MissionType.entries.forEach { type ->
            val alarm = Alarm(mission = MissionConfig(type, MissionDifficulty.MEDIUM, 1))
            val resolved = alarm.resolvedFallback(everythingDenied)

            assertTrue(
                resolved.requiredPermissions.isEmpty(),
                "${type.name} fell back to ${resolved.name}, which still needs a permission"
            )
        }
    }

    @Test
    fun `an alarm survives a serialization round trip`() {
        val alarm = Alarm(
            name = "Gym",
            hour = 5,
            minute = 45,
            repeatDays = Weekday.WEEKDAYS,
            mission = MissionConfig(MissionType.SQUAT, MissionDifficulty.HARD, 12),
            snooze = SnoozeSettings(duration = 5, limit = 2),
            missionGuard = MissionGuardSettings(windowMinutes = 60)
        )

        val json = Json { prettyPrint = false }
        val restored = json.decodeFromString<Alarm>(json.encodeToString(alarm))

        assertEquals(alarm, restored)
    }

    @Test
    fun `an occurrence survives a serialization round trip`() {
        val occurrence = AlarmOccurrence(
            alarmId = "a1",
            occurrenceKey = "a1-20260511-0700",
            scheduledAtEpochMillis = 1_778_000_000_000,
            outcome = AlarmOutcome.COMPLETED,
            missionType = MissionType.MATH,
            difficulty = MissionDifficulty.MEDIUM,
            deliveryTier = DeliveryTier.EXACT_WHILE_IDLE,
            latenessSeconds = 42
        )

        val json = Json
        assertEquals(occurrence, json.decodeFromString<AlarmOccurrence>(json.encodeToString(occurrence)))
    }

    @Test
    fun `sound lookup falls back to the default rather than failing`() {
        assertEquals(SoundLibrary.DEFAULT_SOUND_ID, SoundLibrary.soundFor("does-not-exist").id)
        assertEquals("ascend", SoundLibrary.soundFor("ascend").id)
    }
}

class MissionConfigTest {

    @Test
    fun `escalation raises difficulty one step and stops at the top`() {
        val medium = MissionConfig(MissionType.MATH, MissionDifficulty.MEDIUM, 3)
        assertEquals(MissionDifficulty.HARD, medium.escalated().difficulty)

        val hardest = MissionConfig(MissionType.MATH, MissionDifficulty.VERY_HARD, 3)
        assertEquals(MissionDifficulty.VERY_HARD, hardest.escalated().difficulty)
    }

    @Test
    fun `escalation reaches every child of a combination`() {
        val combination = MissionConfig(
            type = MissionType.COMBINATION,
            difficulty = MissionDifficulty.EASY,
            targetCount = 2,
            children = listOf(
                MissionConfig(MissionType.MATH, MissionDifficulty.EASY, 3),
                MissionConfig(MissionType.TYPING, MissionDifficulty.MEDIUM, 1)
            )
        )

        val escalated = combination.escalated()

        assertEquals(MissionDifficulty.MEDIUM, escalated.children[0].difficulty)
        assertEquals(MissionDifficulty.HARD, escalated.children[1].difficulty)
    }

    @Test
    fun `a physical mission outranks a mental one when alarms merge`() {
        val math = MissionConfig(MissionType.MATH, MissionDifficulty.VERY_HARD, 3)
        val squat = MissionConfig(MissionType.SQUAT, MissionDifficulty.VERY_EASY, 5)

        assertTrue(squat.hardnessScore > math.hardnessScore)
    }

    @Test
    fun `difficulty breaks ties within a mission type`() {
        val easy = MissionConfig(MissionType.MATH, MissionDifficulty.EASY, 3)
        val hard = MissionConfig(MissionType.MATH, MissionDifficulty.HARD, 3)

        assertTrue(hard.hardnessScore > easy.hardnessScore)
    }

    @Test
    fun `a combination is harder than any of its parts`() {
        val child = MissionConfig(MissionType.MATH, MissionDifficulty.MEDIUM, 3)
        val combination = MissionConfig(
            type = MissionType.COMBINATION,
            targetCount = 2,
            children = listOf(child, child)
        )

        assertTrue(combination.hardnessScore > child.hardnessScore)
    }

    @Test
    fun `normalising clamps an out-of-range target count`() {
        val tooMany = MissionConfig(MissionType.SQUAT, MissionDifficulty.MEDIUM, 500).normalized()
        val tooFew = MissionConfig(MissionType.SQUAT, MissionDifficulty.MEDIUM, 0).normalized()

        assertEquals(50, tooMany.targetCount)
        assertEquals(3, tooFew.targetCount)
    }

    @Test
    fun `normalising drops children from a non-combination mission`() {
        val stray = MissionConfig(
            type = MissionType.MATH,
            targetCount = 3,
            children = listOf(MissionConfig(MissionType.TYPING, MissionDifficulty.EASY, 1))
        ).normalized()

        assertTrue(stray.children.isEmpty())
    }

    @Test
    fun `every default target count is inside its own legal range`() {
        MissionType.entries.forEach { type ->
            val default = MissionConfig.defaultTargetCount(type)
            assertTrue(
                default in MissionConfig.targetRange(type),
                "${type.name} default $default is outside ${MissionConfig.targetRange(type)}"
            )
        }
    }

    @Test
    fun `a combination reports the permissions of all its children`() {
        val combination = MissionConfig(
            type = MissionType.COMBINATION,
            targetCount = 2,
            children = listOf(
                MissionConfig(MissionType.QR, MissionDifficulty.EASY, 1),
                MissionConfig(MissionType.STEPS, MissionDifficulty.EASY, 30)
            )
        )

        assertEquals(
            setOf(MissionPermission.CAMERA, MissionPermission.ACTIVITY_RECOGNITION),
            combination.requiredPermissions
        )
    }

    @Test
    fun `every mission type is presentable in the picker`() {
        MissionType.entries.forEach { type ->
            assertTrue(type.displayName.isNotBlank())
            assertTrue(type.category.displayName.isNotBlank())
        }
    }

    @Test
    fun `every permission explains itself before it is requested`() {
        MissionPermission.entries.forEach { permission ->
            assertTrue(permission.rationale.isNotBlank())
            assertTrue(permission.displayName.isNotBlank())
        }
    }

    @Test
    fun `only the alarm clock tier is treated as reliable`() {
        assertTrue(DeliveryTier.EXACT_ALARM_CLOCK.isReliable)
        assertFalse(DeliveryTier.EXACT_WHILE_IDLE.isReliable)
        assertFalse(DeliveryTier.INEXACT.isReliable)
    }

    @Test
    fun `dismissing without the mission is never a success`() {
        assertTrue(AlarmOutcome.COMPLETED.isSuccess)
        assertFalse(AlarmOutcome.DISMISSED_UNVERIFIED.isSuccess)
        assertFalse(AlarmOutcome.SNOOZED.isSuccess)
        assertFalse(AlarmOutcome.MISSED.isSuccess)
    }

    @Test
    fun `audio suppression is explained except when there is nothing to explain`() {
        assertEquals(null, AudioSuppressionReason.NONE.userExplanation)
        AudioSuppressionReason.entries.filter { it != AudioSuppressionReason.NONE }.forEach {
            assertTrue(it.userExplanation!!.isNotBlank())
        }
    }
}
