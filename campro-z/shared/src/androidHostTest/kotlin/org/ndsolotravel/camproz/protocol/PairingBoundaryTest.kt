package org.ndsolotravel.camproz.protocol

import kotlin.test.*

class PairingBoundaryTest {
    private val now = 1700000000000L
    private val info = SecurityHandshake.PairingInfo("192.168.1.10", 8989, 8990, "a".repeat(32), "123456", "Camera & 1", timestampMs = now)
    @Test fun rejectsInvalidPortsCredentialsAndDuplicateFields() {
        val valid = info.toQrPayload()
        for (payload in listOf(valid.replace("cmd=8989", "cmd=0"), valid.replace("stream=8990", "stream=8989"),
            valid.replace("pin=123456", "pin="), valid + "&host=192.168.1.11", valid.replace("token=" + "a".repeat(32), "token="),
            valid.replace("host=192.168.1.10", "host=192.168.1.10/path"), valid.replace("exp=300000", "exp=-1"))) {
            assertIs<SecurityHandshake.QrValidationResult.InvalidPayload>(SecurityHandshake.PairingInfo.validateQrPayload(payload, now))
        }
    }
    @Test fun hostQrRoundTripPreservesCredentialsAndName() {
        val result = assertIs<SecurityHandshake.QrValidationResult.Success>(SecurityHandshake.PairingInfo.validateQrPayload(info.toQrPayload(), now))
        assertEquals(info, result.info)
    }
    @Test fun parsesEscapedPayloadWithoutConfusingNestedKeys() {
        val message = CommandMessage(sessionId = "session", commandId = "id", type = CommandType.SET_LUT,
            payloadJson = "{\"lutId\":\"a\\\"}b\",\"sessionId\":\"nested\"}")
        val decoded = assertNotNull(ProtocolCodec.decodeCommand(ProtocolCodec.encodeCommand(message)))
        assertEquals("session", decoded.sessionId)
        assertEquals("a\"}b", ProtocolCodec.extractString(decoded.payloadJson, "lutId"))
        val error = "Camera \"busy\"\nRetry later"
        assertEquals(error, ProtocolCodec.decodeResult(ProtocolCodec.encodeResult(CommandResult("id", false, 2, error)))?.errorMessage)
    }
    @Test fun rejectsMissingSessionWrongVersionAndNonObjectPayload() {
        val message = ProtocolCodec.encodeCommand(CommandMessage(sessionId = "session", commandId = "id", type = CommandType.SET_ZOOM))
        assertNull(ProtocolCodec.decodeCommand(message.replace("\"session\"", "\"\"")))
        assertNull(ProtocolCodec.decodeCommand(message.replace("\"1.0\"", "\"2.0\"")))
        assertNull(ProtocolCodec.decodeCommand(message.replace("\"payload\":{}", "\"payload\":[]")))
    }
}
