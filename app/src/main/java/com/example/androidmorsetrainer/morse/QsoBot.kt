package com.example.androidmorsetrainer.morse

import kotlin.random.Random

/**
 * Represents a simulated amateur radio operator profile.
 */
data class QsoBotProfile(
    val callsign: String,
    val name: String,
    val qth: String,
    val wpm: Int
)

/**
 * Grouped geographic data for amateur radio procedural generation and lookup.
 */
data class RegionData(
    val regionName: String,
    val prefixes: List<String>,
    val names: List<String>,
    val qths: List<String>
) {
    companion object {
        val USA = RegionData(
            regionName = "USA",
            prefixes = listOf("W", "K", "N", "A"),
            names = listOf("John", "Bob", "Alice", "Tom", "Sarah"),
            qths = listOf("Chicago", "Dallas", "Seattle", "Denver", "Boston")
        )

        val UK = RegionData(
            regionName = "UK",
            prefixes = listOf("G", "M", "2E"),
            names = listOf("Dave", "Pete", "Emma", "Ian"),
            qths = listOf("Warrington", "Manchester", "London", "Sheffield", "Glasgow")
        )

        val ITALY = RegionData(
            regionName = "Italy",
            prefixes = listOf("I", "IK", "IZ", "IN"),
            names = listOf("Marco", "Giuseppe", "Laura", "Antonio"),
            qths = listOf("Rome", "Milan", "Naples", "Turin")
        )

        val GERMANY = RegionData(
            regionName = "Germany",
            prefixes = listOf("DL", "DJ", "DK"),
            names = listOf("Hans", "Klaus", "Julia", "Stefan"),
            qths = listOf("Berlin", "Munich", "Hamburg", "Frankfurt")
        )

        val BULGARIA = RegionData(
            regionName = "Bulgaria",
            prefixes = listOf("LZ"),
            names = listOf("Georgi", "Ivan", "Elena", "Maria"),
            qths = listOf("Sofia", "Plovdiv", "Varna", "Burgas")
        )

        val JAPAN = RegionData(
            regionName = "Japan",
            prefixes = listOf("JA", "JH", "JR"),
            names = listOf("Ken", "Hiro", "Yuki", "Akira"),
            qths = listOf("Tokyo", "Osaka", "Kyoto", "Sapporo")
        )

        val ALL_REGIONS = listOf(USA, UK, ITALY, GERMANY, BULGARIA, JAPAN)

        /**
         * Resolves the appropriate geographic region for a given amateur radio callsign.
         * Correctly prioritizes multi-character ITU prefixes (e.g., "2E", "DL", "LZ", "IK")
         * before falling back to single-character rules (e.g. matching "KD9WNE" to US "K").
         */
        fun findRegionForCallsign(callsign: String): RegionData {
            val clean = callsign.trim().uppercase()

            // 1. Check multi-character prefixes first, sorted by length descending
            val multiPrefixMatches = ALL_REGIONS.flatMap { region ->
                region.prefixes.filter { it.length > 1 }.map { prefix -> prefix to region }
            }.sortedByDescending { it.first.length }

            for ((prefix, region) in multiPrefixMatches) {
                if (clean.startsWith(prefix)) {
                    return region
                }
            }

            // 2. Check single-character prefixes
            val singlePrefixMatches = ALL_REGIONS.flatMap { region ->
                region.prefixes.filter { it.length == 1 }.map { prefix -> prefix to region }
            }

            for ((prefix, region) in singlePrefixMatches) {
                if (clean.startsWith(prefix)) {
                    return region
                }
            }

            // Fallback default
            return USA
        }
    }
}

/**
 * Simulated operator bot for the Morse code QSO simulator.
 * Maintains a fixed session pool of exactly 20 profiles (9 beta testers + 11 procedural).
 */
class QsoBot(
    val userCallsign: String = "M7KQX",
    private val random: Random = Random.Default,
    initialProfile: QsoBotProfile? = null
) {
    companion object {
        val BETA_TESTERS = listOf(
            Pair("NY9K", "Dee"),
            Pair("N1BAM", "Brian"),
            Pair("KD9WNE", "Tim"),
            Pair("K7JAJ", "Jim"),
            Pair("WA0YMH", "Roy"),
            Pair("KD0NZL", "Matt"),
            Pair("LZ2BB", "Boyan"),
            Pair("WC7L", "Casey"),
            Pair("M7KQX", "Chris")
        )
    }

    /**
     * The fixed pool of exactly 20 operator profiles.
     */
    val pool: List<QsoBotProfile>

    /**
     * The currently active bot profile for QSO interactions.
     */
    var currentProfile: QsoBotProfile
        private set

    init {
        val combinedPool = mutableListOf<QsoBotProfile>()

        // 1. Add the 9 beta testers with geographically matched QTHs and random WPM (12-22)
        for ((callsign, name) in BETA_TESTERS) {
            val region = RegionData.findRegionForCallsign(callsign)
            val qth = region.qths.random(random)
            val wpm = random.nextInt(12, 23)
            combinedPool.add(QsoBotProfile(callsign = callsign, name = name, qth = qth, wpm = wpm))
        }

        // 2. Add 11 procedurally generated profiles
        val numToGenerate = 20 - combinedPool.size
        for (i in 1..numToGenerate) {
            val region = RegionData.ALL_REGIONS.random(random)
            val prefix = region.prefixes.random(random)
            val digit = random.nextInt(10)
            val suffixLength = random.nextInt(2, 4) // 2 or 3 letters
            val suffix = (1..suffixLength)
                .map { ('A'..'Z').random(random) }
                .joinToString("")
            val callsign = "$prefix$digit$suffix"
            val name = region.names.random(random)
            val qth = region.qths.random(random)
            val wpm = random.nextInt(12, 23)
            combinedPool.add(QsoBotProfile(callsign = callsign, name = name, qth = qth, wpm = wpm))
        }

        pool = combinedPool.toList()
        currentProfile = initialProfile ?: generateProfile()
    }

    /**
     * Randomly selects and returns one profile from the fixed pool of 20.
     */
    fun generateProfile(): QsoBotProfile {
        return pool.random(random)
    }

    /**
     * Sets the active bot profile to a specific profile from the pool or a generated one.
     */
    fun selectProfile(profile: QsoBotProfile) {
        currentProfile = profile
    }

    /**
     * Constructs the bot's simulated CW transmission string based on the current FSM state.
     *
     * @param state The current QSO state in the state machine.
     * @param userCall Optional override for user's callsign.
     * @return Formatted Morse transmission text.
     */
    fun constructResponse(state: QsoState, userCall: String = userCallsign): String {
        val botCall = currentProfile.callsign
        val name = currentProfile.name
        val qth = currentProfile.qth
        return when (state) {
            is QsoState.Idle -> {
                "CQ CQ DE $botCall K"
            }

            is QsoState.ListenCQ -> {
                "$userCall DE $botCall K"
            }

            is QsoState.InitialResponse -> {
                "$userCall DE $botCall UR RST IS 599 5NN BK"
            }

            is QsoState.InfoExchange -> {
                "$userCall DE $botCall QSL TNX NAME $name QTH $qth BK"
            }

            is QsoState.SignOff -> {
                "TU FER QSO 73 DE $botCall SK"
            }

            is QsoState.DitDitFinale -> {
                "E E"
            }

            is QsoState.Summary -> {
                ""
            }

            is QsoState.Error -> {
                state.message
            }

            // Legacy states for backwards compatibility
            is QsoState.TransmittingCQ -> {
                "$userCall DE $botCall K"
            }

            is QsoState.ListeningForCaller -> {
                "$userCall DE $botCall K"
            }

            is QsoState.Exchanging -> {
                when (state.stage) {
                    ExchangeStage.AWAITING_CALLSIGN -> {
                        "$userCall DE $botCall K"
                    }

                    ExchangeStage.AWAITING_REPORT -> {
                        "$userCall DE $botCall UR 599 599 BK"
                    }

                    ExchangeStage.COMPLETED -> {
                        "TU 73 DE $botCall SK"
                    }
                }
            }

            is QsoState.LoggingContact -> {
                "73 DE $botCall SK"
            }
        }
    }

    /**
     * Constructs error recovery Morse transmission when incoming CW decode is incomprehensible.
     */
    fun constructErrorResponse(): String = "AGN AGN?"
}
