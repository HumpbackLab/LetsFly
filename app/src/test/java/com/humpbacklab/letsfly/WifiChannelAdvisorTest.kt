package com.humpbacklab.letsfly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiChannelAdvisorTest {
    @Test
    fun strongNeighborAffectsOverlappingChannelsAndRecommendsClearOne() {
        val assessment = WifiChannelAdvisor.assess(
            listOf(1, 2, 6, 11),
            listOf(WifiChannelAdvisor.AccessPoint(2437, -43)),
            currentChannel = 6
        )

        assertTrue(assessment.loads.first { it.channel == 6 }.interference >
            assessment.loads.first { it.channel == 2 }.interference)
        assertEquals(1, assessment.recommendedChannel)
    }

    @Test
    fun currentChannelWinsAnEqualScoreAndEmptyScanHasNoRecommendation() {
        val networks = listOf(WifiChannelAdvisor.AccessPoint(2437, -50))
        assertEquals(11, WifiChannelAdvisor.assess(listOf(1, 6, 11), networks, 11)
            .recommendedChannel)
        assertNull(WifiChannelAdvisor.assess(listOf(1, 6, 11), emptyList(), 6)
            .recommendedChannel)
        assertNull(WifiChannelAdvisor.assess(
            listOf(1, 6, 11), listOf(WifiChannelAdvisor.AccessPoint(5180, -45)), 6
        ).recommendedChannel)
    }

    @Test
    fun wideFiveGhzNeighborCoversAdjacentTwentyMhzChannels() {
        val assessment = WifiChannelAdvisor.assess(
            listOf(36, 40, 44, 48, 149),
            listOf(WifiChannelAdvisor.AccessPoint(5180, -50, 80, 5210)),
            currentChannel = 36
        )

        assertTrue(assessment.loads.first { it.channel == 40 }.interference > 0.0)
        assertEquals(149, assessment.recommendedChannel)
    }

    @Test
    fun recommendsLeastInterferenceAcrossAllSupportedChannels() {
        val accessPoints = listOf(
            WifiChannelAdvisor.AccessPoint(2412, -45),
            WifiChannelAdvisor.AccessPoint(2412, -48),
            WifiChannelAdvisor.AccessPoint(2437, -38),
            WifiChannelAdvisor.AccessPoint(2437, -39),
            WifiChannelAdvisor.AccessPoint(2437, -40),
            WifiChannelAdvisor.AccessPoint(2437, -60),
            WifiChannelAdvisor.AccessPoint(2437, -61),
            WifiChannelAdvisor.AccessPoint(2437, -65),
            WifiChannelAdvisor.AccessPoint(2437, -68),
            WifiChannelAdvisor.AccessPoint(2437, -70),
            WifiChannelAdvisor.AccessPoint(2437, -72),
            WifiChannelAdvisor.AccessPoint(2462, -65),
            WifiChannelAdvisor.AccessPoint(2462, -68),
            WifiChannelAdvisor.AccessPoint(2462, -65)
        )

        val assessment = WifiChannelAdvisor.assess((1..13).toList(), accessPoints, 3)

        assertEquals(0, assessment.loads.first { it.channel == 13 }.nearbyCount)
        assertEquals(3, assessment.loads.first { it.channel == 11 }.nearbyCount)
        assertTrue(assessment.loads.first { it.channel == 13 }.interference <
            assessment.loads.first { it.channel == 11 }.interference)
        assertEquals(13, assessment.recommendedChannel)
    }

    @Test
    fun zeroSameChannelNetworksCanStillHaveAdjacentInterference() {
        val assessment = WifiChannelAdvisor.assess(
            listOf(1, 2, 3), listOf(WifiChannelAdvisor.AccessPoint(2412, -45)), null
        )

        val channelTwo = assessment.loads.first { it.channel == 2 }
        assertEquals(0, channelTwo.nearbyCount)
        assertTrue(channelTwo.interference > 0.0)
    }
}
