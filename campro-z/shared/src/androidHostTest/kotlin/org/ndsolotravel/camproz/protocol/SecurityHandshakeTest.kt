package org.ndsolotravel.camproz.protocol

import kotlin.test.*

class SecurityHandshakeTest {

    @Test
    fun testQrPayloadSerializationAndParsing() {
        val info = SecurityHandshake.PairingInfo(
            hostAddress = "192.168.1.105",
            commandPort = 8080,
            streamPort = 8081,
            sessionToken = "a1b2c3d4e5f67890",
            pinCode = "482910",
            deviceName = "Host-Pixel8"
        )

        val qrString = info.toQrPayload()
        assertTrue(qrString.startsWith("camproz://pair?"))
        assertTrue(qrString.contains("host=192.168.1.105"))
        assertTrue(qrString.contains("cmd=8080"))

        val parsed = SecurityHandshake.PairingInfo.fromQrPayload(qrString)
        assertNotNull(parsed)
        assertEquals(info.hostAddress, parsed.hostAddress)
        assertEquals(info.commandPort, parsed.commandPort)
        assertEquals(info.streamPort, parsed.streamPort)
        assertEquals(info.sessionToken, parsed.sessionToken)
        assertEquals(info.pinCode, parsed.pinCode)
        assertEquals(info.deviceName, parsed.deviceName)
    }

    @Test
    fun testQrExpirationAndValidation() {
        val now = 1_700_000_000_000L
        val expiredTs = now - 400_000L // 400s old (limit is 300s)

        val expiredPayload = "camproz://pair?v=1.0&host=192.168.1.50&cmd=8989&stream=8990&token=abc&pin=123456&name=Test&ts=$expiredTs&exp=300000"
        val expiredResult = SecurityHandshake.PairingInfo.validateQrPayload(expiredPayload, nowMs = now)
        assertTrue(expiredResult is SecurityHandshake.QrValidationResult.Expired)

        val unsupportedVersionPayload = "camproz://pair?v=9.9&host=192.168.1.50&cmd=8989&stream=8990&token=abc&pin=123456&name=Test"
        val versionResult = SecurityHandshake.PairingInfo.validateQrPayload(unsupportedVersionPayload, nowMs = now)
        assertTrue(versionResult is SecurityHandshake.QrValidationResult.UnsupportedVersion)

        val loopbackPayload = "camproz://pair?v=1.0&host=127.0.0.1&cmd=8989&stream=8990&token=abc&pin=123456&name=Test"
        val loopbackResult = SecurityHandshake.PairingInfo.validateQrPayload(loopbackPayload, nowMs = now)
        assertTrue(loopbackResult is SecurityHandshake.QrValidationResult.InvalidPayload)

        val spacedNameInfo = SecurityHandshake.PairingInfo(
            hostAddress = "192.168.43.1",
            commandPort = 8989,
            streamPort = 8990,
            sessionToken = "tok123",
            pinCode = "654321",
            deviceName = "CamPro Z (Redmi Note 11)",
            timestampMs = now
        )
        val validPayload = spacedNameInfo.toQrPayload()
        val validResult = SecurityHandshake.PairingInfo.validateQrPayload(validPayload, nowMs = now)
        assertTrue(validResult is SecurityHandshake.QrValidationResult.Success)
        assertEquals("CamPro Z (Redmi Note 11)", validResult.info.deviceName)
    }

    @Test
    fun testAuthSignatureVerification() {
        val sessionId = "session-777"
        val token = "random-token-xyz"
        val pin = "123456"
        val data = "START_RECORDING:1700000000"

        val sig = SecurityHandshake.deriveAuthSignature(sessionId, token, pin, data)
        assertTrue(sig.isNotEmpty())

        val valid = SecurityHandshake.verifyAuthSignature(sessionId, token, pin, data, sig)
        assertTrue(valid)

        val tamperedData = "START_RECORDING:1700000001"
        val invalid = SecurityHandshake.verifyAuthSignature(sessionId, token, pin, tamperedData, sig)
        assertFalse(invalid)

        val invalidPin = SecurityHandshake.verifyAuthSignature(sessionId, token, "999999", data, sig)
        assertFalse(invalidPin)
    }
}
