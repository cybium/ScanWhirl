package com.scanwhirl.app.store

import com.scanwhirl.app.util.Durable
import java.io.IOException

/**
 * One reset and injection point for every fault seam (PM-0.2b). Every test class that uses a
 * seam calls [resetAll] from its `@After`.
 *
 * A simulated death is [SimulatedProcessDeath], an `Error`, so production `catch (e: Exception)`
 * cleanup is skipped exactly as a real kill would skip it.
 */
object Faults {

    class Probe {
        @Volatile var fired = false
    }

    /** Restores every seam (and forgets live staging folders, as a new process would). */
    fun resetAll() {
        SaveFaults.reset()
        OpFaults.reset()
        OpStaging.resetForTests()
        ScanRepository.resetTestSeams()
        Durable.resetForTests()
    }

    /** Simulated process death just before the fsync of the file named [name]. */
    fun dieAtFileSync(name: String): Probe {
        val probe = Probe()
        Durable.beforeFileSync = { f ->
            if (f.name == name) {
                probe.fired = true
                throw SimulatedProcessDeath("sync:$name")
            }
        }
        return probe
    }

    /** An [IOException] just before the fsync of the file named [name]. */
    fun failAtFileSync(name: String): Probe {
        val probe = Probe()
        Durable.beforeFileSync = { f ->
            if (f.name == name) {
                probe.fired = true
                throw IOException("injected failure at $name")
            }
        }
        return probe
    }

    /** Simulated process death at the [OpFaults] step named exactly [step]. */
    fun dieAtOp(step: String): Probe {
        val probe = Probe()
        OpFaults.hook = { s ->
            if (s == step) {
                probe.fired = true
                throw SimulatedProcessDeath(step)
            }
        }
        return probe
    }

    /** An [IOException] at the [OpFaults] step named exactly [step]. */
    fun failAtOp(step: String): Probe {
        val probe = Probe()
        OpFaults.hook = { s ->
            if (s == step) {
                probe.fired = true
                throw IOException("injected failure at $step")
            }
        }
        return probe
    }
}
