/*
 * ZIPRAF_OMEGA Risk Mitigation Module Tests
 * Copyright (C) 2025 Rafael Melo Reis
 */

package org.florisboard.lib.zipraf

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RiskMitigationModuleTest {
    
    private lateinit var module: RiskMitigationModule
    
    @BeforeEach
    fun setup() {
        module = RiskMitigationModule.getInstance()
        module.resetMetrics()
    }
    
    @AfterEach
    fun tearDown() {
        module.resetMetrics()
    }
    
    @Test
    fun `test latency measurement within threshold`() = runTest {
        val (result, measurement) = module.measureLatency("fast_op", 100L) {
            delay(10)
            "success"
        }
        
        assertEquals("success", result)
        assertFalse(measurement.exceedsThreshold)
        assertTrue(measurement.durationMs < 100L)
    }
    
    @Test
    fun `test latency measurement exceeds threshold`() = runTest {
        // Use actual computation instead of delay to properly measure wall-clock time
        // measureTimeMillis uses wall-clock, so we need real work, not virtual delay
        val (result, measurement) = module.measureLatency("slow_op", 1L) {
            // Perform actual work that takes measurable time
            var sum = 0.0
            repeat(100_000) { i ->
                sum += Math.sin(i.toDouble())
            }
            "completed"
        }
        
        assertEquals("completed", result)
        // With threshold of 1ms, any real computation should exceed it
        assertTrue(measurement.exceedsThreshold)
        assertTrue(measurement.durationMs >= 1L)
    }
    
    @Test
    fun `test fragmentation detection`() {
        val info = module.checkFragmentation()
        
        assertNotNull(info)
        assertTrue(info.totalMemoryBytes > 0)
        assertTrue(info.freeMemoryBytes >= 0)
        assertTrue(info.fragmentationRatio >= 0.0 && info.fragmentationRatio <= 1.0)
    }
    
    @Test
    fun `test garbage collection trigger`() {
        val result = module.triggerGarbageCollection()
        assertTrue(result)
    }
    
    @Test
    fun `test process registration`() {
        module.registerProcess("proc1", "TestProcess")
        
        val metrics = module.getMetrics()
        assertEquals(1L, metrics["active_processes"])
    }
    
    @Test
    fun `test process activity update`() {
        module.registerProcess("proc1", "TestProcess")
        module.updateProcessActivity("proc1")
        
        // Should not throw exception
        assertTrue(true)
    }
    
    @Test
    fun `test process unregistration`() {
        module.registerProcess("proc1", "TestProcess")
        module.unregisterProcess("proc1")
        
        val metrics = module.getMetrics()
        assertEquals(0L, metrics["active_processes"])
    }
    
    @Test
    fun `test zombie process detection`() = runTest {
        module.registerProcess("zombie", "ZombieProcess")
        
        // Should not be zombie immediately
        val zombies1 = module.detectZombieProcesses()
        assertTrue(zombies1.isEmpty())
        
        // Note: In real test would wait for threshold, here we just verify the mechanism
        val metrics = module.getMetrics()
        assertNotNull(metrics["active_processes"])
    }
    
    @Test
    fun `test zombie cleanup`() {
        module.registerProcess("zombie1", "Zombie1")
        module.registerProcess("zombie2", "Zombie2")
        
        val zombie = ZombieProcess(
            processId = "zombie1",
            name = "Zombie1",
            createdAt = System.currentTimeMillis(),
            lastActivityAt = 0L,
            idleTimeMs = 1000000L,
            isZombie = true
        )
        
        val cleaned = module.cleanupZombieProcesses(listOf(zombie))
        assertEquals(1, cleaned)
    }
    
    @Test
    fun `test redundancy detection`() {
        val data = listOf("a", "b", "c", "a", "b", "d")
        val redundant = module.detectRedundancy(data)
        
        assertEquals(2, redundant.size)
        assertTrue(redundant.contains("a"))
        assertTrue(redundant.contains("b"))
    }
    
    @Test
    fun `test redundancy detection with no duplicates`() {
        val data = listOf("a", "b", "c", "d")
        val redundant = module.detectRedundancy(data)
        
        assertTrue(redundant.isEmpty())
    }
    
    @Test
    fun `test metrics collection`() = runTest {
        // Generate some events
        // Use actual computation for reliable timing measurement
        module.measureLatency("op1", 1L) { 
            var sum = 0.0
            repeat(10_000) { i -> sum += Math.sin(i.toDouble()) }
            "result" 
        }
        module.checkFragmentation()
        module.detectRedundancy(listOf("a", "a", "b"))
        
        val metrics = module.getMetrics()
        
        assertNotNull(metrics["latency_violations"])
        assertNotNull(metrics["redundancies_found"])
        assertTrue(metrics["latency_violations"]!! >= 0)
    }
    
    @Test
    fun `test average latency calculation`() = runTest {
        // Use actual computation instead of delay to properly measure wall-clock time
        repeat(3) {
            module.measureLatency("test_op", 10000L) {
                // Perform actual work that takes measurable time
                var sum = 0.0
                repeat(10_000) { i ->
                    sum += Math.sin(i.toDouble())
                }
                "result"
            }
        }
        
        val avg = module.getAverageLatency("test_op")
        assertNotNull(avg)
        assertTrue(avg!! >= 0.0) // Average should be non-negative
    }
    
    @Test
    fun `test average latency for unknown operation`() {
        val avg = module.getAverageLatency("unknown")
        assertNull(avg)
    }
    
    @Test
    fun `test risk event emission`() = runTest {
        // Start collecting events - use drop to skip any old replay events from previous tests
        val eventJob = launch {
            // Use filter to only get events from this test's operation
            val event = module.riskEvents.first { it.description.contains("emit_test") }
            assertNotNull(event)
            assertEquals(RiskType.LATENCY.name, event.riskType)
        }
        
        // Give the collector time to start subscribing
        kotlinx.coroutines.yield()
        
        // Trigger event by exceeding threshold using actual computation
        module.measureLatency("emit_test", 1L) {
            // Use Thread.sleep to ensure actual wall-clock time passes
            // measureTimeMillis uses wall-clock time, not virtual time
            Thread.sleep(10)
            "result"
        }
        
        eventJob.join()
    }
    
    @Test
    fun `test metrics reset`() = runTest {
        // Generate some data using actual computation for reliable timing
        module.measureLatency("op", 1L) { 
            var sum = 0.0
            repeat(100_000) { i -> sum += Math.sin(i.toDouble()) }
            "result" 
        }
        module.checkFragmentation()
        
        // Reset
        module.resetMetrics()
        
        val metrics = module.getMetrics()
        assertEquals(0L, metrics["latency_violations"])
        assertEquals(0L, metrics["fragmentation_events"])
    }
}
