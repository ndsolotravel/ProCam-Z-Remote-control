package org.ndsolotravel.apps.camera

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses 33x33x33 3D .cube LUT files (Adobe Cube format) from assets
 * and prepares byte buffers for OpenGL ES 3.0 3D texture mapping (sampler3D).
 */
class LutManager(private val context: Context) {

    data class LutData(
        val name: String,
        val size: Int,
        val buffer: ByteBuffer
    )

    private val lutCache = mutableMapOf<String, LutData>()

    fun getAvailableLuts(): List<String> {
        return listOf(
            "None",
            "Identity",
            "Natural",
            "Travel",
            "Warm Sunset",
            "Cool Cinema",
            "Mono",
            "Green Pastures",
            "Vintage 80s"
        )
    }

    fun loadLut(name: String): LutData? {
        if (name == "None") return null
        if (lutCache.containsKey(name)) return lutCache[name]

        val fileName = when (name) {
            "Identity" -> "CamPro_Z_00_Identity_Rec709_33.cube"
            "Natural" -> "CamPro_Z_01_Natural_Rec709_33.cube"
            "Travel" -> "CamPro_Z_02_Travel_Rec709_33.cube"
            "Warm Sunset" -> "CamPro_Z_03_Warm_Sunset_Rec709_33.cube"
            "Cool Cinema" -> "CamPro_Z_04_Cool_Cinema_Rec709_33.cube"
            "Mono" -> "CamPro_Z_05_Mono_Rec709_33.cube"
            "Green Pastures" -> "CamPro_Z_Green_Pastures_Rec709_33.cube"
            "Vintage 80s" -> "CamPro_Z_Vintage_80s_Rec709_33.cube"
            else -> return null
        }

        try {
            val assetPath = "luts/$fileName"
            context.assets.open(assetPath).use { stream ->
                val reader = BufferedReader(InputStreamReader(stream))
                var size = 33
                val values = ArrayList<Float>(33 * 33 * 33 * 3)

                var line: String? = reader.readLine()
                while (line != null) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        if (trimmed.startsWith("LUT_3D_SIZE")) {
                            val parts = trimmed.split("\\s+".toRegex())
                            if (parts.size >= 2) {
                                size = parts[1].toIntOrNull() ?: 33
                            }
                        } else if (trimmed.startsWith("TITLE") || trimmed.startsWith("DOMAIN_MIN") || trimmed.startsWith("DOMAIN_MAX")) {
                            // Header metadata - skipped
                        } else {
                            val rgb = trimmed.split("\\s+".toRegex())
                            if (rgb.size >= 3) {
                                val r = rgb[0].toFloatOrNull() ?: 0f
                                val g = rgb[1].toFloatOrNull() ?: 0f
                                val b = rgb[2].toFloatOrNull() ?: 0f
                                values.add(r.coerceIn(0f, 1f))
                                values.add(g.coerceIn(0f, 1f))
                                values.add(b.coerceIn(0f, 1f))
                            }
                        }
                    }
                    line = reader.readLine()
                }

                val totalElements = size * size * size * 4 // RGBA8888
                val byteBuffer = ByteBuffer.allocateDirect(totalElements).order(ByteOrder.nativeOrder())
                var valIdx = 0
                for (i in 0 until (size * size * size)) {
                    if (valIdx + 2 < values.size) {
                        val r = (values[valIdx++] * 255f).toInt().toByte()
                        val g = (values[valIdx++] * 255f).toInt().toByte()
                        val b = (values[valIdx++] * 255f).toInt().toByte()
                        val a = 255.toByte()
                        byteBuffer.put(r)
                        byteBuffer.put(g)
                        byteBuffer.put(b)
                        byteBuffer.put(a)
                    } else {
                        byteBuffer.put(0.toByte()).put(0.toByte()).put(0.toByte()).put(255.toByte())
                    }
                }
                byteBuffer.flip()

                val lutData = LutData(name, size, byteBuffer)
                lutCache[name] = lutData
                Log.d("LutManager", "Loaded 3D LUT '$name' ($size^3) with ${values.size / 3} table points")
                return lutData
            }
        } catch (e: Exception) {
            Log.e("LutManager", "Failed to load LUT $name: ${e.message}", e)
            return null
        }
    }
}
