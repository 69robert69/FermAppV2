package com.fermapp.api.domain

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fermapp.api.common.json.JsonFormats
import com.fermapp.api.domain.work.WorkMath
import com.fermapp.api.domain.work.WorkMath.WorkParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class WorkMathTest {

    private val start = Instant.parse("2026-04-01T08:00:00Z")

    @Test
    fun `capacity follows width times speed times efficiency over ten`() {
        // 6 m × 8 km/h × 75% / 10 = 3.6 ha/h; 18 ha take 5 hours.
        val estimate = WorkMath.estimateWork(18.0, WorkParams(6.0, 8.0, 75.0), start)
        assertEquals(3.6, estimate.capacityHaH)
        assertEquals(5.0, estimate.durationHours)
        assertEquals(start.plusSeconds(5 * 3600), estimate.eta)
    }

    @Test
    fun `invalid implement settings are rejected`() {
        assertThrows<IllegalArgumentException> { WorkMath.workCapacityHaH(WorkParams(6.0, 8.0, 120.0)) }
        assertThrows<IllegalArgumentException> { WorkMath.workCapacityHaH(WorkParams(0.0, 8.0, 75.0)) }
        assertThrows<IllegalArgumentException> { WorkMath.irrigationDurationHours(500.0, 0.0) }
    }

    @Test
    fun `a paused run stops the timer`() {
        val pausedAt = start.plusSeconds(2 * 3600)
        val progress = WorkMath.timedProgress(start, 4 * 3_600_000.0, 0.0, pausedAt, start.plusSeconds(3 * 3600))
        assertEquals(2 * 3_600_000.0, progress.elapsedMs)
        assertEquals(50.0, progress.percent)
        assertTrue(!progress.finished)
    }

    @Test
    fun `a run past its duration is finished`() {
        val progress = WorkMath.timedProgress(start, 3_600_000.0, 0.0, null, start.plusSeconds(2 * 3600))
        assertEquals(100.0, progress.percent)
        assertEquals(0.0, progress.remainingMs)
        assertTrue(progress.finished)
    }

    @Test
    fun `the JSON is the same as from the Java records`() {
        val mapper = ObjectMapper().registerModule(JsonFormats.module()).registerKotlinModule()
        val json = mapper.writeValueAsString(WorkMath.estimateWork(18.0, WorkParams(6.0, 8.0, 75.0), start))
        assertEquals("""{"capacityHaH":3.6,"durationHours":5,"eta":"2026-04-01T13:00:00.000Z"}""", json)
        val params = mapper.readValue("""{"workingWidthM":6,"speedKmh":8,"efficiencyPct":75}""", WorkParams::class.java)
        assertEquals(WorkParams(6.0, 8.0, 75.0), params)
    }

    @Test
    fun `irrigation duration is path length over speed`() {
        val estimate = WorkMath.estimateIrrigation(600.0, 40.0, start)
        assertEquals(15.0, estimate.durationHours)
        assertEquals(start.plusSeconds(15 * 3600), estimate.eta)
    }
}
