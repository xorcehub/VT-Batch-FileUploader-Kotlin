package com.vtbatch.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VTResponseParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    // === extractDetectionStats ===

    @Test
    fun `extractDetectionStats returns stats from file report`() {
        val response = """
        {
            "data": {
                "attributes": {
                    "last_analysis_results": {
                        "Engine-A": {"category": "malicious", "result": "Trojan"},
                        "Engine-B": {"category": "harmless", "result": null},
                        "Engine-C": {"category": "undetected", "result": null}
                    }
                }
            }
        }
        """.trimIndent()
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        val stats = VTResponseParser.extractDetectionStats(jsonObj)

        assertNotNull(stats)
        assertEquals("1 malicious, 3 total", stats.description)
        assertEquals("1/3", stats.ratio)
    }

    @Test
    fun `extractDetectionStats returns null when no analysis results`() {
        val response = """{"data": {"attributes": {}}}"""
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        assertNull(VTResponseParser.extractDetectionStats(jsonObj))
    }

    @Test
    fun `extractDetectionStats returns null for empty json`() {
        val response = """{}"""
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        assertNull(VTResponseParser.extractDetectionStats(jsonObj))
    }

    // === extractDetectionStatsFromAnalysis ===

    @Test
    fun `extractDetectionStatsFromAnalysis uses stats object`() {
        val response = """
        {
            "data": {
                "attributes": {
                    "stats": {
                        "malicious": 2,
                        "suspicious": 1,
                        "undetected": 60,
                        "harmless": 10,
                        "timeout": 0,
                        "confirmed-timeout": 0,
                        "failure": 0,
                        "type-unsupported": 0
                    }
                }
            }
        }
        """.trimIndent()
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        assertEquals("2/73", VTResponseParser.extractDetectionStatsFromAnalysis(jsonObj))
    }

    @Test
    fun `extractDetectionStatsFromAnalysis counts individual results`() {
        val response = """
        {
            "data": {
                "attributes": {
                    "results": {
                        "Engine-A": {"category": "malicious"},
                        "Engine-B": {"category": "harmless"}
                    }
                }
            }
        }
        """.trimIndent()
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        assertEquals("1/2", VTResponseParser.extractDetectionStatsFromAnalysis(jsonObj))
    }

    @Test
    fun `extractDetectionStatsFromAnalysis returns null when nothing to parse`() {
        val response = """{"data": {"attributes": {}}}"""
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        assertNull(VTResponseParser.extractDetectionStatsFromAnalysis(jsonObj))
    }

    // === extractSha256 ===

    @Test
    fun `extractSha256 reads data id`() {
        val response = """{"data": {"id": "abc123sha256hash000000000000000000000000000000"}}"""
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        assertEquals("abc123sha256hash000000000000000000000000000000", VTResponseParser.extractSha256(jsonObj))
    }

    @Test
    fun `extractSha256 returns null on missing data`() {
        val response = """{}"""
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        assertNull(VTResponseParser.extractSha256(jsonObj))
    }

    // === extractSha256FromAnalysis ===

    @Test
    fun `extractSha256FromAnalysis prefers meta file_info`() {
        val response = """
        {
            "meta": {"file_info": {"sha256": "from_meta_hash"}},
            "data": {"id": "from_data_id"}
        }
        """.trimIndent()
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        assertEquals("from_meta_hash", VTResponseParser.extractSha256FromAnalysis(jsonObj))
    }

    @Test
    fun `extractSha256FromAnalysis falls back to data id`() {
        val response = """{"data": {"id": "from_data_id"}}"""
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        assertEquals("from_data_id", VTResponseParser.extractSha256FromAnalysis(jsonObj))
    }

    // === extractAnalysisId ===

    @Test
    fun `extractAnalysisId reads data id`() {
        val response = """{"data": {"id": "analysis-12345"}}"""
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        assertEquals("analysis-12345", VTResponseParser.extractAnalysisId(jsonObj))
    }

    @Test
    fun `extractAnalysisId returns null on missing data`() {
        val response = """{}"""
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        assertNull(VTResponseParser.extractAnalysisId(jsonObj))
    }

    // === extractAnalysisStatus ===

    @Test
    fun `extractAnalysisStatus reads status`() {
        val response = """{"data": {"attributes": {"status": "completed"}}}"""
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        assertEquals("completed", VTResponseParser.extractAnalysisStatus(jsonObj))
    }

    @Test
    fun `extractAnalysisStatus returns null on missing status`() {
        val response = """{"data": {"attributes": {}}}"""
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        assertNull(VTResponseParser.extractAnalysisStatus(jsonObj))
    }

    // === extractLastAnalysisDate ===

    @Test
    fun `extractLastAnalysisDate reads epoch seconds`() {
        val response = """{"data": {"attributes": {"last_analysis_date": 1700000000}}}"""
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        assertEquals(1700000000L, VTResponseParser.extractLastAnalysisDate(jsonObj))
    }

    @Test
    fun `extractLastAnalysisDate returns null when missing`() {
        val response = """{"data": {"attributes": {}}}"""
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        assertNull(VTResponseParser.extractLastAnalysisDate(jsonObj))
    }

    @Test
    fun `extractEngineHitsFromAnalysis reads per-engine results`() {
        val response = """
        {
            "data": {
                "attributes": {
                    "results": {
                        "Kaspersky": {"engine_name": "Kaspersky", "category": "malicious", "result": "Trojan.Win32.Generic"},
                        "Microsoft": {"engine_name": "Microsoft", "category": "malicious", "result": "Trojan:Win32/Wacatac"},
                        "CleanAV": {"engine_name": "CleanAV", "category": "harmless", "result": null}
                    }
                }
            }
        }
        """.trimIndent()
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        val hits = VTResponseParser.extractEngineHitsFromAnalysis(jsonObj)
        assertNotNull(hits)
        assertEquals(2, hits.size)
        assertEquals("Kaspersky", hits[0].engine)
        assertEquals("Trojan.Win32.Generic", hits[0].verdict)
        assertEquals("Microsoft", hits[1].engine)
    }

    @Test
    fun `extractEngineHitsFromAnalysis returns null when no malicious hits`() {
        val response = """
        {
            "data": {
                "attributes": {
                    "results": {
                        "CleanAV": {"engine_name": "CleanAV", "category": "harmless", "result": null}
                    }
                }
            }
        }
        """.trimIndent()
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        assertNull(VTResponseParser.extractEngineHitsFromAnalysis(jsonObj))
    }

    @Test
    fun `extractEngineHitsFromAnalysis returns null when results missing`() {
        val response = """{"data": {"attributes": {}}}"""
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        assertNull(VTResponseParser.extractEngineHitsFromAnalysis(jsonObj))
    }

    // === extractFileDetails ===

    @Test
    fun `extractFileDetails extracts all fields`() {
        val response = """
        {
            "data": {
                "attributes": {
                    "last_analysis_stats": {
                        "malicious": 5, "suspicious": 2, "undetected": 60,
                        "harmless": 10, "timeout": 1
                    },
                    "last_analysis_results": {
                        "Kaspersky": {
                            "engine_name": "Kaspersky",
                            "category": "malicious",
                            "result": "Trojan.Win32.Generic"
                        },
                        "Microsoft": {
                            "engine_name": "Microsoft",
                            "category": "malicious",
                            "result": "Trojan:Win32/Wacatac"
                        },
                        "CleanAV": {
                            "engine_name": "CleanAV",
                            "category": "harmless",
                            "result": null
                        }
                    },
                    "popular_threat_classification": {
                        "suggested_threat_label": "trojan"
                    },
                    "type_description": "Win64 EXE",
                    "tags": ["executable", "windows"],
                    "meaningful_name": "malware.exe",
                    "times_submitted": 42,
                    "reputation": -10,
                    "first_submission_date": 1690000000,
                    "last_submission_date": 1700000000,
                    "total_votes": {"harmless": 3, "malicious": 7}
                }
            }
        }
        """.trimIndent()
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        val details = VTResponseParser.extractFileDetails(jsonObj)

        assertNotNull(details)
        assertEquals(5, details.detectionCount)
        assertEquals("trojan", details.popularThreatLabel)
        assertEquals("Win64 EXE", details.typeDescription)
        assertEquals(listOf("executable", "windows"), details.tags)
        assertEquals("malware.exe", details.meaningfulName)
        assertEquals(42, details.timesSubmitted)
        assertEquals(-10, details.reputation)
        assertEquals(3, details.totalVotesHarmless)
        assertEquals(7, details.totalVotesMalicious)
        assertEquals("trojan", details.suggestedThreatLabel)
        // Per-engine detections: only malicious engines, sorted by name
        val hits = details.engineHits
        assertNotNull(hits)
        assertEquals(2, hits.size)
        assertEquals("Kaspersky", hits[0].engine)
        assertEquals("Trojan.Win32.Generic", hits[0].verdict)
        assertEquals("Microsoft", hits[1].engine)
    }

    @Test
    fun `extractFileDetails returns null when no attributes`() {
        val response = """{"data": {}}"""
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        assertNull(VTResponseParser.extractFileDetails(jsonObj))
    }

    @Test
    fun `extractFileDetails returns empty engineHits when nothing is malicious`() {
        val response = """
        {
            "data": {
                "attributes": {
                    "last_analysis_results": {
                        "CleanAV": {"engine_name": "CleanAV", "category": "harmless", "result": null}
                    }
                }
            }
        }
        """.trimIndent()
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        val details = VTResponseParser.extractFileDetails(jsonObj)
        assertNotNull(details)
        assertNull(details.engineHits) // no malicious engines -> null (not empty list)
    }

    @Test
    fun `extractFileDetails handles partial data gracefully`() {
        val response = """
        {
            "data": {
                "attributes": {
                    "type_description": "PDF"
                }
            }
        }
        """.trimIndent()
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        val details = VTResponseParser.extractFileDetails(jsonObj)

        assertNotNull(details)
        assertEquals("PDF", details.typeDescription)
        assertNull(details.popularThreatLabel)
        assertNull(details.tags)
        assertNull(details.detectionCount)
    }

    @Test
    fun `extractFileDetails formats lastAnalysisStats correctly`() {
        val response = """
        {
            "data": {
                "attributes": {
                    "last_analysis_stats": {
                        "malicious": 5, "suspicious": 2, "undetected": 60,
                        "harmless": 10, "timeout": 1
                    }
                }
            }
        }
        """.trimIndent()
        val jsonObj = json.parseToJsonElement(response) as JsonObject
        val details = VTResponseParser.extractFileDetails(jsonObj)

        assertNotNull(details)
        val stats = details.lastAnalysisStats!!
        assertTrue(stats.contains("5 malicious"), "Should contain '5 malicious', got: $stats")
        assertTrue(stats.contains("2 suspicious"), "Should contain '2 suspicious', got: $stats")
        assertTrue(stats.contains("10 harmless"), "Should contain '10 harmless', got: $stats")
    }
}
