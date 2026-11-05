package io.intenttrace.benchmarks

import io.intenttrace.provenance.ContractProvenanceJson
import io.intenttrace.provenance.ReproductionMetadata
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ContractResultsTest {
    private val metadata = ReproductionMetadata("contract-unit-drift", "1", "a".repeat(40), null,
        "runtime", "17", "os", emptyMap())

    @Test
    fun `contract records retain raw reference and observed evidence with canonical JSON`() {
        val records = contractRecords(metadata)
        assertEquals(3, records.size)
        records.forEach {
            assertEquals(it, ContractProvenanceJson.decode(ContractProvenanceJson.encode(it)))
            assertNull(it.detected)
            assertEquals("2000", it.reproduction.configuration["logicalDurationMillis"])
            assertFailsWith<IllegalArgumentException> { it.copy(schemaVersion = 2) }
        }
        repeat(3) { assertEquals(records, contractRecords(metadata)) }
        assertEquals("{\"inputId\":\"session-duration-001\",\"contractVersion\":1,\"duration\":2000}",
            records.first().referenceWirePayload)
    }

    @Test
    fun `runner replaces a parseable three-record file deterministically`() {
        val directory = Files.createTempDirectory("intenttrace-contract-results")
        val output = directory.resolve("results.jsonl")
        val args = arrayOf("--output", output.toString(), "--source-commit", "a".repeat(40))
        try {
            ContractUnitDriftMain.main(args)
            val bytes = Files.readString(output)
            assertEquals(3, Files.readAllLines(output).map(ContractProvenanceJson::decode).size)
            ContractUnitDriftMain.main(args)
            assertEquals(bytes, Files.readString(output))
            assertFailsWith<IllegalArgumentException> { ContractUnitDriftMain.main(arrayOf("--unknown", "x")) }
            assertFailsWith<IllegalArgumentException> { ContractUnitDriftMain.main(arrayOf("--source-commit", "invalid")) }
        } finally {
            Files.deleteIfExists(output)
            Files.deleteIfExists(directory)
        }
    }
}
