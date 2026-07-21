package it.drone.mesh.common

import android.Manifest

object MeshPermissions {
    private const val RUNTIME_PERMISSIONS_SDK = 23
    private const val NEARBY_DEVICES_SDK = 31
    private const val NEARBY_WIFI_DEVICES_SDK = 33
    private const val NOTIFICATIONS_SDK = 33

    @JvmStatic
    fun requiredFor(sdkInt: Int): Array<String> = when {
        sdkInt >= NEARBY_WIFI_DEVICES_SDK -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )

        sdkInt >= NEARBY_DEVICES_SDK -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        sdkInt >= RUNTIME_PERMISSIONS_SDK -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyArray()
    }

    @JvmStatic
    fun optionalFor(sdkInt: Int): Array<String> =
        if (sdkInt >= NOTIFICATIONS_SDK) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }
}