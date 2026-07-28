package com.mikeos.maps.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Reads the phone's ambient illuminance (lux) from [Sensor.TYPE_LIGHT] and forwards it to [MapTheme],
 * which decides light vs dark map (in AUTO mode). Registered while the app is foreground, unregistered
 * on pause — so it costs nothing when not visible. No-op on devices without a light sensor (the map
 * then stays on whatever fixed mode is set).
 */
class LightSensor(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)

    fun available(): Boolean = sensor != null

    fun start() { sensor?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } }
    fun stop() { sm?.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_LIGHT) MapTheme.onLux(e.values.firstOrNull() ?: return)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
