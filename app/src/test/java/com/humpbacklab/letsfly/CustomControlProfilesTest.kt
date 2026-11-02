package com.humpbacklab.letsfly

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class CustomControlProfilesTest {
    private val car = CustomControlConfig.default().let { base ->
        base.copy(defaults = base.defaults.toMutableList().also { it[2] = 50 },
            controls = listOf(
                CustomControl(1, CustomControlType.HORIZONTAL, .2f, .6f,
                    CustomAxis(4, 10, 90, true, 50), widthScale = 1.4f,
                    heightScale = .7f, backgroundOpacity = 45),
                CustomControl(2, CustomControlType.VERTICAL, .8f, .6f,
                    CustomAxis(3, 15, 85, true, 50)),
                CustomControl(3, CustomControlType.BUTTON, .5f, .3f,
                    CustomAxis(7), offValue = 10, middleValue = 55,
                    onValue = 90, buttonPositions = 3)))
    }
    private val airplane = CustomControlConfig.default()

    @Test fun migratesLegacyLayoutsWithoutChangingTheirConfiguration() {
        for (version in listOf("1", "2", "3")) {
            val preferences = memoryPreferences()
            val config = CustomControlConfig.default(version == "3")
            val encoded = config.encode().let { text ->
                if (version != "1") text else text.lineSequence().mapIndexed { i, line ->
                    when (i) { 0 -> "1"; 1 -> line; else -> line.split(',').take(8).joinToString(",") }
                }.joinToString("\n")
            }
            preferences.edit().putString(CustomControlConfig.PREF_KEY, encoded).apply()
            val expected = CustomControlConfig.decode(encoded)!!
            val profiles = CustomControlProfiles(preferences, "Original")
            assertEquals(1, profiles.activeId())
            assertEquals(listOf(CustomControlProfile(1, "Original", expected)), profiles.all())
            assertEquals(expected, CustomControlConfig.load(preferences))
            assertEquals(profiles.all(), CustomControlProfiles(preferences, "Other").all())
        }
    }

    @Test fun createsDefaultProfileOnFirstUse() {
        val preferences = memoryPreferences()
        val profiles = CustomControlProfiles(preferences, "Layout 1")
        assertEquals(CustomControlConfig.default(), profiles.get(1)!!.config)
        assertEquals(CustomControlConfig.default(), CustomControlConfig.load(preferences))
        assertFalse(profiles.delete(1))
    }

    @Test fun carAndAirplaneKeepIndependentLayoutsAcrossRestartAndSwitching() {
        val preferences = memoryPreferences()
        val profiles = CustomControlProfiles(preferences, "Car")
        assertTrue(profiles.save(1, car))
        val planeId = profiles.create("Airplane", car)!!
        assertEquals(car, profiles.get(planeId)!!.config)
        assertTrue(profiles.save(planeId, airplane))
        assertEquals(airplane, CustomControlConfig.load(preferences))
        assertEquals(car, profiles.get(1)!!.config)

        val reopened = CustomControlProfiles(preferences, "Unused")
        assertEquals(planeId, reopened.activeId())
        repeat(3) {
            assertTrue(reopened.select(1))
            assertEquals(car, CustomControlConfig.load(preferences))
            assertTrue(reopened.select(planeId))
            assertEquals(airplane, CustomControlConfig.load(preferences))
        }
    }

    @Test fun renamingPreservesLayoutAndRejectsEmptyLongOrDuplicateNames() {
        val profiles = CustomControlProfiles(memoryPreferences(), "Car")
        profiles.save(1, car)
        val planeId = profiles.create("Airplane", airplane)!!
        assertTrue(profiles.rename(1, "  Drift car  "))
        assertEquals("Drift car", profiles.get(1)!!.name)
        assertEquals(car, profiles.get(1)!!.config)
        for (name in listOf("", "   ", "Airplane", "x".repeat(31))) {
            assertFalse(profiles.rename(1, name))
            assertNull(profiles.create(name, car))
        }
        assertTrue(profiles.rename(planeId, "Airplane"))
        assertEquals("Drift car", profiles.get(1)!!.name)
    }

    @Test fun savesOnlyTheRequestedProfileAndKeepsActiveLayoutInSync() {
        val preferences = memoryPreferences()
        val profiles = CustomControlProfiles(preferences, "Car")
        val planeId = profiles.create("Airplane", airplane)!!
        assertTrue(profiles.save(1, car))
        assertEquals(airplane, CustomControlConfig.load(preferences))
        assertEquals(planeId, profiles.activeId())
        assertTrue(profiles.select(1))
        assertEquals(car, CustomControlConfig.load(preferences))
        assertEquals(airplane, profiles.get(planeId)!!.config)
    }

    @Test fun invalidOperationsDoNotChangeSavedLayoutsOrActiveSelection() {
        val preferences = memoryPreferences()
        val profiles = CustomControlProfiles(preferences, "Car")
        profiles.save(1, car)
        val before = preferences.all
        val invalid = car.copy(defaults = List(16) { 101 })
        assertNull(profiles.create("Broken", invalid))
        assertFalse(profiles.save(1, invalid))
        assertFalse(profiles.save(999, airplane))
        assertFalse(profiles.select(999))
        assertFalse(profiles.rename(999, "Missing"))
        assertFalse(profiles.delete(999))
        assertFalse(profiles.delete(1))
        assertEquals(before, preferences.all)
    }

    @Test fun deletingActiveProfileRestoresRemainingLayoutAndNeverReusesIds() {
        val preferences = memoryPreferences()
        val profiles = CustomControlProfiles(preferences, "Car")
        profiles.save(1, car)
        val planeId = profiles.create("Airplane", airplane)!!
        assertTrue(profiles.delete(planeId))
        assertEquals(1, profiles.activeId())
        assertEquals(car, CustomControlConfig.load(preferences))
        val newId = profiles.create("New airplane", airplane)!!
        assertTrue(newId > planeId)
        assertTrue(profiles.delete(1))
        assertEquals(newId, profiles.activeId())
        assertEquals(airplane, CustomControlConfig.load(preferences))
        assertFalse(profiles.delete(newId))
    }

    @Test fun unrelatedPreferencesArePreserved() {
        val preferences = memoryPreferences()
        preferences.edit().putBoolean("gyro_enabled", true)
            .putString("orientation_mode", "rc_car").apply()
        val profiles = CustomControlProfiles(preferences, "Car")
        profiles.save(1, car)
        val planeId = profiles.create("Airplane", airplane)!!
        profiles.select(1)
        profiles.rename(planeId, "Plane")
        profiles.delete(planeId)
        assertTrue(preferences.getBoolean("gyro_enabled", false))
        assertEquals("rc_car", preferences.getString("orientation_mode", null))
    }

    @Test fun switchingLayoutsProvidesDefaultsInsteadOfPreviousControlOutputs() {
        val preferences = memoryPreferences()
        val profiles = CustomControlProfiles(preferences, "Car")
        profiles.save(1, car)
        val oldState = CustomControlState(CustomControlConfig.load(preferences))
        oldState.setArmed(true)
        oldState.move(2, 0f, 1f)
        oldState.advanceButton(3)
        val planeId = profiles.create("Airplane", airplane)!!
        val newState = CustomControlState(CustomControlConfig.load(preferences))
        assertFalse(newState.armed)
        assertEquals(0, newState.value(3))
        assertEquals(0, newState.value(5))
        assertEquals(0, newState.value(7))
        assertNotEquals(oldState.value(3), newState.value(3))
        assertTrue(profiles.select(1))
        val restored = CustomControlState(CustomControlConfig.load(preferences))
        assertEquals(50, restored.value(3))
        assertNotEquals(1, planeId)
    }

    @Test fun downgradeEditIsImportedIntoActiveProfileWithoutChangingOtherProfiles() {
        val preferences = memoryPreferences()
        val profiles = CustomControlProfiles(preferences, "Car")
        profiles.save(1, car)
        val planeId = profiles.create("Airplane", airplane)!!
        for (version in listOf("2", "3")) {
            // The older editor knows only the canonical layout key, not the profile keys.
            val legacy = car.encode().replaceFirst("3\n", "$version\n")
            preferences.edit().putString(CustomControlConfig.PREF_KEY, legacy).apply()
            val expected = CustomControlConfig.decode(legacy)!!
            val upgraded = CustomControlProfiles(preferences, "Unused")
            assertEquals(planeId, upgraded.activeId())
            assertEquals("Airplane", upgraded.get(planeId)!!.name)
            assertEquals(expected, upgraded.get(planeId)!!.config)
            assertEquals(expected, CustomControlConfig.load(preferences))
            assertEquals(car, upgraded.get(1)!!.config)
            assertTrue(upgraded.select(1))
            assertTrue(upgraded.select(planeId))
            assertEquals(expected, CustomControlConfig.load(preferences))
            assertEquals(expected, CustomControlProfiles(preferences, "Unused").get(planeId)!!.config)
        }
    }

    @Test fun invalidOrMissingLegacyLayoutCannotOverwriteAnExistingProfile() {
        for (legacy in listOf<String?>("invalid layout", null)) {
            val preferences = memoryPreferences()
            val profiles = CustomControlProfiles(preferences, "Car")
            profiles.save(1, car)
            preferences.edit().putString(CustomControlConfig.PREF_KEY, legacy).apply()
            val reopened = CustomControlProfiles(preferences, "Unused")
            assertEquals(car, reopened.get(1)!!.config)
            assertTrue(reopened.select(1))
            assertEquals(car, CustomControlConfig.load(preferences))
        }
    }

    /** SharedPreferences storage fixture; apply publishes all edits together. */
    private fun memoryPreferences(): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString", "putInt", "putBoolean" -> {
                        pending[args!![0] as String] = args[1]
                        proxy
                    }
                    "remove" -> { pending[args!![0] as String] = null; proxy }
                    "apply", "commit" -> {
                        for ((key, value) in pending) {
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        pending.clear()
                        if (method.name == "commit") true else null
                    }
                    else -> error("Unexpected editor method: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString", "getInt", "getBoolean" -> values[args!![0]] ?: args[1]
                "getAll" -> values.toMap()
                "edit" -> editor()
                else -> error("Unexpected preferences method: ${method.name}")
            }
        } as SharedPreferences
    }
}
