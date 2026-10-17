package com.example.androidmorsetrainer.morse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * JUnit 4 tests for [QsoBot], [QsoBotProfile], and [RegionData].
 */
class QsoBotTest {

    private lateinit var bot: QsoBot

    @Before
    fun setUp() {
        bot = QsoBot(userCallsign = "M7KQX", random = Random(42))
    }

    @Test
    fun testPoolContainsExactly20Items() {
        assertEquals("Session pool must contain exactly 20 profiles", 20, bot.pool.size)
    }

    @Test
    fun testBetaTestersPresentInPool() {
        val testerCallsigns = QsoBot.BETA_TESTERS.map { it.first }.toSet()
        val poolCallsigns = bot.pool.map { it.callsign }.toSet()

        for ((call, name) in QsoBot.BETA_TESTERS) {
            assertTrue("Expected beta tester $call in pool", poolCallsigns.contains(call))
            val profile = bot.pool.first { it.callsign == call }
            assertEquals("Name must match for $call", name, profile.name)
        }
    }

    @Test
    fun testLz2bbGetsBulgarianQth() {
        val lzProfile = bot.pool.firstOrNull { it.callsign == "LZ2BB" }
        assertNotNull("LZ2BB must be present in the pool", lzProfile)
        assertEquals("LZ2BB name must be Boyan", "Boyan", lzProfile!!.name)

        val bulgarianQths = RegionData.BULGARIA.qths
        assertTrue(
            "LZ2BB QTH '${lzProfile.qth}' must be in Bulgarian QTHs: $bulgarianQths",
            bulgarianQths.contains(lzProfile.qth)
        )
    }

    @Test
    fun testN1bamGetsUsQth() {
        val n1bamProfile = bot.pool.firstOrNull { it.callsign == "N1BAM" }
        assertNotNull("N1BAM must be present in the pool", n1bamProfile)
        assertEquals("N1BAM name must be Brian", "Brian", n1bamProfile!!.name)

        val usQths = RegionData.USA.qths
        assertTrue(
            "N1BAM QTH '${n1bamProfile.qth}' must be in US QTHs: $usQths",
            usQths.contains(n1bamProfile.qth)
        )
    }

    @Test
    fun testAllProfilesHaveGeographicallyAccurateQths() {
        // Every profile in the pool (beta testers and procedural) must have a QTH matching its callsign prefix
        for (profile in bot.pool) {
            val matchingRegion = RegionData.findRegionForCallsign(profile.callsign)
            assertTrue(
                "Profile ${profile.callsign} with QTH '${profile.qth}' must match region '${matchingRegion.regionName}' QTH list",
                matchingRegion.qths.contains(profile.qth)
            )
        }
    }

    @Test
    fun testAllProfilesHaveWpmWithinRange() {
        for (profile in bot.pool) {
            assertTrue(
                "Profile ${profile.callsign} WPM (${profile.wpm}) must be between 12 and 22",
                profile.wpm in 12..22
            )
        }
    }

    @Test
    fun testGenerateProfileReturnsElementFromPool() {
        val poolSet = bot.pool.toSet()
        repeat(50) {
            val selected = bot.generateProfile()
            assertTrue("Generated profile must be in the fixed pool", poolSet.contains(selected))
        }
    }

    @Test
    fun testMultiCharacterPrefixParsing() {
        // Multi-character ITU prefixes vs single-character US prefix rules
        assertEquals("USA", RegionData.findRegionForCallsign("KD9WNE").regionName)
        assertEquals("USA", RegionData.findRegionForCallsign("WA0YMH").regionName)
        assertEquals("USA", RegionData.findRegionForCallsign("WC7L").regionName)
        assertEquals("USA", RegionData.findRegionForCallsign("NY9K").regionName)
        assertEquals("UK", RegionData.findRegionForCallsign("M7KQX").regionName)
        assertEquals("UK", RegionData.findRegionForCallsign("2E0XYZ").regionName)
        assertEquals("UK", RegionData.findRegionForCallsign("G4ABC").regionName)
        assertEquals("Germany", RegionData.findRegionForCallsign("DL1ABC").regionName)
        assertEquals("Germany", RegionData.findRegionForCallsign("DJ5XYZ").regionName)
        assertEquals("Italy", RegionData.findRegionForCallsign("IK2ABC").regionName)
        assertEquals("Italy", RegionData.findRegionForCallsign("I1ABC").regionName)
        assertEquals("Bulgaria", RegionData.findRegionForCallsign("LZ2BB").regionName)
        assertEquals("Japan", RegionData.findRegionForCallsign("JA1ABC").regionName)
        assertEquals("Japan", RegionData.findRegionForCallsign("JH7XYZ").regionName)
    }

    @Test
    fun testConstructResponseOutputsMatchExpectedStateTransitions() {
        val profile = QsoBotProfile(callsign = "LZ2BB", name = "Boyan", qth = "Sofia", wpm = 18)
        bot.selectProfile(profile)

        // 1. Idle state
        assertEquals("CQ CQ DE LZ2BB K", bot.constructResponse(QsoState.Idle))

        // 2. TransmittingCQ / ListeningForCaller
        assertEquals("M7KQX DE LZ2BB K", bot.constructResponse(QsoState.ListeningForCaller))

        // 3. Exchanging - Awaiting Report
        assertEquals(
            "M7KQX DE LZ2BB UR 599 599 BK",
            bot.constructResponse(QsoState.Exchanging(ExchangeStage.AWAITING_REPORT))
        )

        // 4. Exchanging - Completed
        assertEquals(
            "TU 73 DE LZ2BB SK",
            bot.constructResponse(QsoState.Exchanging(ExchangeStage.COMPLETED))
        )

        // 5. LoggingContact
        assertEquals(
            "73 DE LZ2BB SK",
            bot.constructResponse(QsoState.LoggingContact)
        )
    }

    @Test
    fun testConstructResponseWithCustomUserCallsign() {
        val profile = QsoBotProfile(callsign = "N1BAM", name = "Brian", qth = "Boston", wpm = 20)
        bot.selectProfile(profile)

        val response = bot.constructResponse(
            state = QsoState.Exchanging(ExchangeStage.AWAITING_REPORT),
            userCall = "G4ABC"
        )
        assertEquals("G4ABC DE N1BAM UR 599 599 BK", response)
    }
}
