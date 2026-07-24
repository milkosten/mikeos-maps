package com.mikeos.maps.net

/**
 * Decodes Google's Encoded Polyline Algorithm Format (precision 5) — the format OSRM (and
 * trips-cloud's `/api/route`) returns. Pure Kotlin, no dependencies. Used to render the route
 * as a traced [androidx.compose.ui.graphics.Path] over a neutral canvas (no online map tiles).
 */
object PolylineCodec {

    data class LatLon(val lat: Double, val lon: Double)

    /** Decode an encoded polyline (precision 5) into an ordered list of points. */
    fun decode(encoded: String, precision: Int = 5): List<LatLon> {
        val out = ArrayList<LatLon>()
        val factor = Math.pow(10.0, precision.toDouble())
        var index = 0
        var lat = 0
        var lon = 0
        val len = encoded.length
        while (index < len) {
            var result = 1
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63 - 1
                result += b shl shift
                shift += 5
            } while (b >= 0x1f && index < len)
            lat += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)

            result = 1
            shift = 0
            do {
                b = encoded[index++].code - 63 - 1
                result += b shl shift
                shift += 5
            } while (b >= 0x1f && index < len)
            lon += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)

            out.add(LatLon(lat / factor, lon / factor))
        }
        return out
    }
}
