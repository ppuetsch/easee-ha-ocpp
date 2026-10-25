package de.puetsch.easeeocpp.ocpp

import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@Epic("OCPP")
@Feature("Frame-Parsing")
class OcppFrameCodecTest {

    @Test
    @Story("Gültige Frames")
    fun parsesCall() {
        val accepted = parseAccepted("""[2,"abc-1","Heartbeat",{}]""")

        val call = assertInstanceOf(OcppFrame.Call::class.java, accepted)
        assertEquals("abc-1", call.uniqueId)
        assertEquals("Heartbeat", call.action)
    }

    @Test
    @Story("Gültige Frames")
    fun parsesCallResult() {
        val accepted = parseAccepted("""[3,"abc-1",{"status":"Accepted"}]""")

        val result = assertInstanceOf(OcppFrame.CallResult::class.java, accepted)
        assertEquals("Accepted", result.payload.path("status").asText())
    }

    @Test
    @Story("Gültige Frames")
    fun parsesCallError() {
        val accepted = parseAccepted("""[4,"abc-1","NotSupported","nope",{}]""")

        val error = assertInstanceOf(OcppFrame.CallError::class.java, accepted)
        assertEquals("NotSupported", error.errorCode)
        assertEquals("nope", error.errorDescription)
    }

    @Test
    @Story("Müll wird abgewiesen")
    fun invalidJsonIsRejectedWithoutReply() {
        val rejected = parseRejected("{kaputt")

        assertNull(rejected.errorReply)
    }

    @Test
    @Story("Müll wird abgewiesen")
    fun nonArrayIsRejectedWithoutReply() {
        assertNull(parseRejected("""{"a":1}""").errorReply)
    }

    @Test
    @Story("Müll wird abgewiesen")
    fun unknownMessageTypeIsRejectedWithoutReply() {
        assertNull(parseRejected("""[9,"abc-1",{}]""").errorReply)
    }

    @Test
    @Story("CALLERROR bei lesbarer UniqueId")
    fun callWithWrongLengthGetsFormatViolation() {
        val rejected = parseRejected("""[2,"abc-1","Heartbeat"]""")

        assertFormatViolationFor("abc-1", rejected)
    }

    @Test
    @Story("CALLERROR bei lesbarer UniqueId")
    fun callWithBlankActionGetsFormatViolation() {
        assertFormatViolationFor("abc-1", parseRejected("""[2,"abc-1","",{}]"""))
    }

    @Test
    @Story("CALLERROR bei lesbarer UniqueId")
    fun callWithNonObjectPayloadGetsFormatViolation() {
        assertFormatViolationFor("abc-1", parseRejected("""[2,"abc-1","Heartbeat",[]]"""))
    }

    @Test
    @Story("CALLERROR bei lesbarer UniqueId")
    fun callWithTooLongUniqueIdIsRejectedWithoutReply() {
        val tooLongId = "x".repeat(MAX_UNIQUE_ID_LENGTH + 1)

        assertNull(parseRejected("""[2,"$tooLongId","Heartbeat",{}]""").errorReply)
    }

    @Test
    @Story("Antworten werden nie beantwortet")
    fun malformedCallResultIsRejectedWithoutReply() {
        assertNull(parseRejected("""[3,"abc-1"]""").errorReply)
    }

    @Test
    @Story("Serialisierung")
    fun serializesCallAsWireArray() {
        val call = OcppFrame.Call("abc-1", "Heartbeat", OcppJson.mapper.createObjectNode())

        assertEquals("""[2,"abc-1","Heartbeat",{}]""", OcppFrameCodec.serialize(call))
    }

    @Test
    @Story("Serialisierung")
    fun serializedCallErrorParsesBackToSameFrame() {
        val original = callErrorFrame("abc-1", OcppErrorCode.FORMAT_VIOLATION, "kaputt")

        val parsed = parseAccepted(OcppFrameCodec.serialize(original))

        assertEquals(original, parsed)
    }

    private fun parseAccepted(text: String): OcppFrame {
        val outcome = OcppFrameCodec.parse(text)
        return assertInstanceOf(ParseOutcome.Accepted::class.java, outcome).frame
    }

    private fun parseRejected(text: String): ParseOutcome.Rejected =
        assertInstanceOf(ParseOutcome.Rejected::class.java, OcppFrameCodec.parse(text))

    private fun assertFormatViolationFor(uniqueId: String, rejected: ParseOutcome.Rejected) {
        val reply = assertInstanceOf(OcppFrame.CallError::class.java, rejected.errorReply)
        assertEquals(uniqueId, reply.uniqueId)
        assertEquals(OcppErrorCode.FORMAT_VIOLATION.wireValue, reply.errorCode)
    }
}
