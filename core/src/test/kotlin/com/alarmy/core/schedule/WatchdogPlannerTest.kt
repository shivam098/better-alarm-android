package com.alarmy.core.schedule

import com.alarmy.core.model.Alarm
import com.alarmy.core.model.MissionConfig
import com.alarmy.core.model.MissionDifficulty
import com.alarmy.core.model.MissionGuardSettings
import com.alarmy.core.model.MissionType
import com.alarmy.core.model.OccurrenceKey
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchdogPlannerTest {

    private val now: Instant = Instant.parse("2026-05-11T07:00:00Z")
    private val parentKey = "alarm-1-20260511-0700"

    private fun alarm(
        windowMinutes: Int = 30,
        escalates: Boolean = true,
        missionType: MissionType = MissionType.MATH,
        difficulty: MissionDifficulty = MissionDifficulty.MEDIUM
    ) = Alarm(
        id = "alarm-1",
        name = "Wake",
        mission = MissionConfig(missionType, difficulty, 3),
        missionGuard = MissionGuardSettings(
            isEnabled = windowMinutes > 0,
            windowMinutes = windowMinutes,
            escalates = escalates
        )
    )

    @Test
    fun `guard disabled produces no follow-ups`() {
        val plan = WatchdogPlanner().plan(alarm(windowMinutes = 0), now, parentKey)
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `follow-ups stay inside the configured window`() {
        val plan = WatchdogPlanner().plan(alarm(windowMinutes = 15), now, parentKey)

        assertTrue(plan.entries.isNotEmpty())
        plan.entries.forEach { entry ->
            val offsetMinutes = (entry.fireDate.epochSecond - now.epochSecond) / 60
            assertTrue(offsetMinutes <= 15, "entry at +${offsetMinutes}m exceeds the window")
        }
    }

    @Test
    fun `follow-ups are capped by the entry budget`() {
        val plan = WatchdogPlanner(maxEntries = 4).plan(alarm(windowMinutes = 60), now, parentKey)

        assertEquals(4, plan.entries.size)
    }

    @Test
    fun `first follow-up lands one minute after the alarm`() {
        val plan = WatchdogPlanner().plan(alarm(), now, parentKey)

        assertEquals(now.plusSeconds(60), plan.entries.first().fireDate)
    }

    @Test
    fun `follow-ups are strictly increasing and uniquely keyed`() {
        val plan = WatchdogPlanner().plan(alarm(windowMinutes = 60), now, parentKey)

        val dates = plan.entries.map { it.fireDate }
        assertEquals(dates.sorted(), dates)
        assertEquals(dates.size, dates.toSet().size)

        val keys = plan.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        keys.forEach { assertEquals(parentKey, OccurrenceKey.rootOf(it)) }
    }

    @Test
    fun `difficulty escalates only from the third follow-up`() {
        val plan = WatchdogPlanner(escalateFromIndex = 3)
            .plan(alarm(windowMinutes = 60, difficulty = MissionDifficulty.MEDIUM), now, parentKey)

        val byIndex = plan.entries.associateBy { it.index }
        assertEquals(MissionDifficulty.MEDIUM, byIndex.getValue(1).mission.difficulty)
        assertEquals(MissionDifficulty.MEDIUM, byIndex.getValue(2).mission.difficulty)
        assertEquals(MissionDifficulty.HARD, byIndex.getValue(3).mission.difficulty)
        assertEquals(MissionDifficulty.HARD, byIndex.getValue(4).mission.difficulty)
    }

    @Test
    fun `escalation can be turned off`() {
        val plan = WatchdogPlanner()
            .plan(alarm(windowMinutes = 60, escalates = false), now, parentKey)

        assertTrue(plan.entries.all { it.mission.difficulty == MissionDifficulty.MEDIUM })
    }

    @Test
    fun `escalation is capped at the hardest difficulty`() {
        val plan = WatchdogPlanner()
            .plan(
                alarm(windowMinutes = 60, difficulty = MissionDifficulty.VERY_HARD),
                now,
                parentKey
            )

        assertTrue(plan.entries.all { it.mission.difficulty == MissionDifficulty.VERY_HARD })
    }

    @Test
    fun `a none mission is never guarded`() {
        val plan = WatchdogPlanner().plan(
            alarm(missionType = MissionType.NONE),
            now,
            parentKey
        )
        assertTrue(plan.isEmpty, "an alarm with no mission has nothing to guard")
    }

    @Test
    fun `planning is deterministic`() {
        val planner = WatchdogPlanner()
        val a = planner.plan(alarm(), now, parentKey)
        val b = planner.plan(alarm(), now, parentKey)

        assertEquals(a.entries.map { it.key }, b.entries.map { it.key })
        assertEquals(a.entries.map { it.fireDate }, b.entries.map { it.fireDate })
    }
}

class SchedulingBudgetTest {

    private val now: Instant = Instant.parse("2026-05-11T07:00:00Z")

    private fun request(
        key: String,
        offsetHours: Long,
        isWatchdog: Boolean = false,
        isRepeating: Boolean = false
    ) = SchedulingBudget.Request(key, now.plusSeconds(offsetHours * 3600), isWatchdog, isRepeating)

    @Test
    fun `past requests are dropped`() {
        val budget = SchedulingBudget(capacity = 10)
        val kept = budget.prioritise(
            listOf(request("old", -1), request("new", 1)),
            now
        )
        assertEquals(listOf("new"), kept.map { it.key })
    }

    @Test
    fun `imminent real alarms outrank imminent watchdogs`() {
        val budget = SchedulingBudget(capacity = 10)
        val kept = budget.prioritise(
            listOf(
                request("watchdog", 1, isWatchdog = true),
                request("alarm", 2)
            ),
            now
        )
        assertEquals(listOf("alarm", "watchdog"), kept.map { it.key })
    }

    @Test
    fun `distant repeating alarms outrank distant one-offs`() {
        val budget = SchedulingBudget(capacity = 10)
        val kept = budget.prioritise(
            listOf(
                request("one-off", 100),
                request("repeating", 200, isRepeating = true)
            ),
            now
        )
        assertEquals(listOf("repeating", "one-off"), kept.map { it.key })
    }

    @Test
    fun `capacity trims the least important requests`() {
        val budget = SchedulingBudget(capacity = 3)
        val kept = budget.prioritise(
            (1..10).map { request("a$it", it.toLong()) },
            now
        )
        assertEquals(3, kept.size)
        assertEquals(listOf("a1", "a2", "a3"), kept.map { it.key })
    }
}
