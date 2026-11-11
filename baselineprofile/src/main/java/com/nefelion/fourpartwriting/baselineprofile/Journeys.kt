package com.nefelion.fourpartwriting.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val APP = "com.nefelion.fourpartwriting"

/** Start-up, then the common paths: a chorale opened and checked, the lessons list. */
private fun MacrobenchmarkScope.journey() {
    pressHome()
    startActivityAndWait()
    home()
    val free = device.findObject(By.text("Free Mode")) ?: return // first run: the tutorial
    free.click()
    device.wait(Until.hasObject(By.text("Chorales")), 5_000)
    device.findObject(By.text("Chorales"))?.click()
    device.wait(Until.hasObject(By.textStartsWith("BWV")), 5_000)
    device.findObject(By.textStartsWith("BWV"))?.click()
    device.wait(Until.hasObject(By.desc("Check harmony")), 5_000)
    device.findObject(By.desc("Check harmony"))?.click()
    device.waitForIdle()
    device.findObject(By.desc("Check harmony"))?.click()
    home()
    device.findObject(By.text("Lessons"))?.click()
    device.wait(Until.hasObject(By.textContains("The sound inside a note")), 5_000)
    device.pressBack(); device.waitForIdle()
}

/** Back until Home shows (the app reopens its last screen); stops there, so it never leaves the app. */
private fun MacrobenchmarkScope.home() {
    repeat(5) {
        if (device.hasObject(By.text("Free Mode")) && device.hasObject(By.text("Lessons"))) return
        device.pressBack(); device.waitForIdle()
    }
}

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(APP, includeInStartupProfile = true) { journey() }
}

/** Cold start time without and with the profile: ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun none() = start(CompilationMode.None())
    @Test fun profile() = start(CompilationMode.Partial())

    private fun start(mode: CompilationMode) = rule.measureRepeated(APP, listOf(StartupTimingMetric()), mode, StartupMode.COLD, iterations = 10) {
        pressHome()
        startActivityAndWait()
    }
}
