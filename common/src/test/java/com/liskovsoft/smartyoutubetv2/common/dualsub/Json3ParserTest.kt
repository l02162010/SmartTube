package com.liskovsoft.smartyoutubetv2.common.dualsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Json3ParserTest {
    @Test
    fun manualSubsKeepCueSplit() {
        val json = """
            {"events":[
              {"tStartMs":0,"dDurationMs":100000,"id":1,"wpWinPosId":1},
              {"tStartMs":1000,"dDurationMs":2000,"segs":[{"utf8":"Hello\nthere,"}]},
              {"tStartMs":3500,"dDurationMs":1500,"segs":[{"utf8":"  general   "},{"utf8":"Kenobi."}]},
              {"tStartMs":6000,"dDurationMs":1000,"segs":[{"utf8":"\n"}]}
            ]}
        """.trimIndent()

        val cues = Json3Parser.parse(json, false)

        assertEquals(listOf(
            SubCue(1000, 3000, "Hello there,"),
            SubCue(3500, 5000, "general Kenobi.")
        ), cues)
    }

    @Test
    fun autoSubsAreRebuiltIntoSentences() {
        val json = """
            {"events":[
              {"tStartMs":0,"dDurationMs":200000,"id":1,"wpWinPosId":1},
              {"tStartMs":1000,"dDurationMs":4000,"wWinId":1,"segs":[{"utf8":"so","acAsrConf":0},{"utf8":" today","tOffsetMs":300},{"utf8":" we","tOffsetMs":700}]},
              {"tStartMs":2500,"dDurationMs":100,"wWinId":1,"aAppend":1,"segs":[{"utf8":"\n"}]},
              {"tStartMs":2500,"dDurationMs":4000,"wWinId":1,"segs":[{"utf8":"cook."},{"utf8":" first","tOffsetMs":500},{"utf8":" step","tOffsetMs":800}]},
              {"tStartMs":9000,"dDurationMs":1000,"wWinId":1,"segs":[{"utf8":"next"}]}
            ]}
        """.trimIndent()

        val cues = Json3Parser.parse(json, true)

        assertEquals(3, cues.size)
        // Word end is capped by the next word start (cook. starts at 2500)
        assertEquals(SubCue(1000, 3000, "so today we cook."), cues[0])
        assertEquals(SubCue(3000, 6500, "first step"), cues[1])
        // Gap > 1.5s starts a new cue
        assertEquals(SubCue(9000, 10000, "next"), cues[2])
    }

    @Test
    fun longParagraphIsSplitByLengthAndSpan() {
        val segs = (0 until 60).joinToString(",") { i -> """{"utf8":"${if (i == 0) "" else " "}word$i","tOffsetMs":${i * 200}}""" }
        val json = """{"events":[{"tStartMs":0,"dDurationMs":12000,"segs":[$segs]}]}"""

        val cues = Json3Parser.parse(json, true)

        assertTrue(cues.size > 1)
        cues.forEach { cue ->
            assertTrue("too long: ${cue.text}", cue.text.length <= Json3Parser.MAX_CHARS)
            assertTrue("too wide: $cue", cue.endMs - cue.startMs <= Json3Parser.MAX_SPAN_MS)
        }
        // Nothing lost
        assertEquals((0 until 60).joinToString(" ") { "word$it" }, cues.joinToString(" ") { it.text })
    }

    @Test
    fun cjkWordsAreJoinedWithoutSpaces() {
        val json = """
            {"events":[{"tStartMs":0,"dDurationMs":3000,"segs":[{"utf8":"今日は"},{"utf8":"いい","tOffsetMs":500},{"utf8":"天気。","tOffsetMs":900},{"utf8":"OK","tOffsetMs":1500},{"utf8":"go","tOffsetMs":1800}]}]}
        """.trimIndent()

        val cues = Json3Parser.parse(json, true)

        assertEquals(listOf("今日はいい天気。", "OK go"), cues.map { it.text })
    }

    @Test
    fun sentenceEnd() {
        assertTrue(Json3Parser.endsSentence("done."))
        assertTrue(Json3Parser.endsSentence("really?\""))
        assertTrue(Json3Parser.endsSentence("好。"))
        assertTrue(!Json3Parser.endsSentence("U.S"))
    }

    @Test
    fun findCue() {
        val cues = listOf(SubCue(0, 1000, "a"), SubCue(2000, 3000, "b"), SubCue(3000, 4000, "c"))

        assertEquals(0, findCueIndex(cues, 0))
        assertEquals(-1, findCueIndex(cues, 1500))
        assertEquals(1, findCueIndex(cues, 2999))
        assertEquals(2, findCueIndex(cues, 3000))
        assertEquals(-1, findCueIndex(cues, 5000))
        assertEquals(1, findCueIndexFrom(cues, 1500))
        assertEquals(2, findCueIndexFrom(cues, 9999))
    }
}
