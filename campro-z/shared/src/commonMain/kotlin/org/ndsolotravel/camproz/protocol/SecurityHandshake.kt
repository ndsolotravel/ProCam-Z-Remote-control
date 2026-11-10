package org.ndsolotravel.camproz.protocol

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Manages zero-trust pairing credentials, QR payloads, manual PIN codes,
 * credential expiration, and authenticated session key derivation.
 */
object SecurityHandshake {

    const val CURRENT_PROTOCOL_VERSION = "1.0"
    const val DEFAULT_PAIRING_VALIDITY_MS = 300_000L // 5 minutes

    private val random = SecureRandom()

    sealed class QrValidationResult {
        data class Success(val info: PairingInfo) : QrValidationResult()
        data class Expired(val ageMs: Long, val limitMs: Long) : QrValidationResult()
        data class UnsupportedVersion(val foundVersion: String, val expectedVersion: String) : QrValidationResult()
        data class InvalidPayload(val reason: String) : QrValidationResult()
    }

    data class PairingInfo(
        val hostAddress: String,
        val commandPort: Int,
        val streamPort: Int,
        val sessionToken: String,
        val pinCode: String,
        val deviceName: String,
        val protocolVersion: String = CURRENT_PROTOCOL_VERSION,
        val timestampMs: Long = System.currentTimeMillis(),
        val validityDurationMs: Long = DEFAULT_PAIRING_VALIDITY_MS
    ) {
        fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
            return (nowMs - timestampMs) > validityDurationMs
        }

        fun remainingTimeMs(nowMs: Long = System.currentTimeMillis()): Long {
            val remaining = (timestampMs + validityDurationMs) - nowMs
            return if (remaining > 0L) remaining else 0L
        }

        fun toQrPayload(): String {
            val encName = try {
                URLEncoder.encode(deviceName, "UTF-8")
            } catch (_: Exception) {
                deviceName.replace(" ", "%20")
            }
            return "camproz://pair?v=$protocolVersion&host=$hostAddress&cmd=$commandPort&stream=$streamPort&token=$sessionToken&pin=$pinCode&name=$encName&ts=$timestampMs&exp=$validityDurationMs"
        }

        companion object {
            fun validateQrPayload(payload: String, nowMs: Long = System.currentTimeMillis()): QrValidationResult {
                if (!payload.startsWith("camproz://pair?")) {
                    return QrValidationResult.InvalidPayload("Payload does not begin with camproz://pair? scheme")
                }

                val query = payload.substringAfter("?", "")
                if (query.isEmpty()) {
                    return QrValidationResult.InvalidPayload("Empty query parameters")
                }

                val params = query.split("&").associate { param ->
                    val eqIndex = param.indexOf('=')
                    if (eqIndex != -1) {
                        param.substring(0, eqIndex) to param.substring(eqIndex + 1)
                    } else {
                        param to ""
                    }
                }

                val version = params["v"] ?: "1.0"
                if (version != CURRENT_PROTOCOL_VERSION) {
                    return QrValidationResult.UnsupportedVersion(version, CURRENT_PROTOCOL_VERSION)
                }

                val ts = params["ts"]?.toLongOrNull() ?: 0L
                val exp = params["exp"]?.toLongOrNull() ?: DEFAULT_PAIRING_VALIDITY_MS
                if (ts > 0L && (nowMs - ts) > exp) {
                    return QrValidationResult.Expired(nowMs - ts, exp)
                }

                val host = params["host"]
                if (host.isNullOrBlank() || host == "127.0.0.1" || host == "0.0.0.0" || host == "localhost") {
                    return QrValidationResult.InvalidPayload("Invalid or loopback host address: '$host'")
                }

                val cmd = params["cmd"]?.toIntOrNull()
                    ?: return QrValidationResult.InvalidPayload("Missing or invalid command port")
                val stream = params["stream"]?.toIntOrNull() ?: (cmd + 1)
                val token = params["token"]
                    ?: return QrValidationResult.InvalidPayload("Missing session token")
                val pin = params["pin"]
                    ?: return QrValidationResult.InvalidPayload("Missing PIN code")

                val rawName = params["name"] ?: "CamPro Z Camera"
                val name = try {
                    URLDecoder.decode(rawName, "UTF-8")
                } catch (_: Exception) {
                    rawName
                }

                return QrValidationResult.Success(
                    PairingInfo(
                        hostAddress = host,
                        commandPort = cmd,
                        streamPort = stream,
                        sessionToken = token,
                        pinCode = pin,
                        deviceName = name,
                        protocolVersion = version,
                        timestampMs = if (ts > 0L) ts else nowMs,
                        validityDurationMs = exp
                    )
                )
            }

            fun fromQrPayload(payload: String): PairingInfo? {
                return when (val res = validateQrPayload(payload)) {
                    is QrValidationResult.Success -> res.info
                    else -> null
                }
            }
        }
    }

    fun generateSessionToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun generatePinCode(): String {
        val pinInt = random.nextInt(900000) + 100000
        return pinInt.toString()
    }

    fun deriveAuthSignature(sessionId: String, token: String, pin: String, data: String): String {
        val secret = "$sessionId:$token:$pin".toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val hash = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun verifyAuthSignature(sessionId: String, token: String, pin: String, data: String, signature: String): Boolean {
        val expected = deriveAuthSignature(sessionId, token, pin, data)
        return expected.equals(signature, ignoreCase = true)
    }
}
