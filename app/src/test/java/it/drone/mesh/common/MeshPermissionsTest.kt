package it.drone.mesh.common

import android.Manifest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class MeshPermissionsTest {
    @Test
    fun `given Android below runtime permissions when permissions requested then none are required`() {
        assertArrayEquals(emptyArray<String>(), MeshPermissions.requiredFor(22))
    }

    @Test
    fun `given Android 6 through 11 when permissions requested then fine location is required`() {
        assertArrayEquals(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            MeshPermissions.requiredFor(23),
        )
        assertArrayEquals(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            MeshPermissions.requiredFor(30),
        )
    }

    @Test
    fun `given Android 12 when permissions requested then Bluetooth and location permissions are required`() {
        val expected = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        assertArrayEquals(expected, MeshPermissions.requiredFor(31))
        assertArrayEquals(expected, MeshPermissions.requiredFor(32))
    }

    @Test
    fun `given Android 13 or newer when permissions requested then Bluetooth and nearby Wi-Fi permissions are required`() {
        val expected = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )

        assertArrayEquals(expected, MeshPermissions.requiredFor(33))
        assertArrayEquals(expected, MeshPermissions.requiredFor(37))
    }

    @Test
    fun `given Android below 13 when optional permissions requested then notifications are omitted`() {
        assertArrayEquals(emptyArray<String>(), MeshPermissions.optionalFor(32))
    }

    @Test
    fun `given Android 13 or newer when optional permissions requested then notifications are included`() {
        assertArrayEquals(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            MeshPermissions.optionalFor(33),
        )
    }

    @Test
    fun `given an invalid negative SDK when permissions requested then none are required`() {
        assertArrayEquals(emptyArray<String>(), MeshPermissions.requiredFor(-1))
    }
}