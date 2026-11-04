package io.intenttrace.benchmarks

import io.intenttrace.evaluation.ClientContractDecision
import io.intenttrace.evaluation.ContractCompatibilityInvariant
import io.intenttrace.evaluation.ContractCompatibilityResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class DurationUnit { MILLISECONDS, SECONDS }

/** Both contract versions use the same syntactic shape; unit meaning is external. */
@Serializable
data class DurationWirePayload(val inputId: String, val contractVersion: Long, val duration: Long) {
    init {
        require(inputId.isNotBlank() && contractVersion > 0 && duration >= 0)
    }
}

class DurationProducer(private val contractVersion: Long, private val unit: DurationUnit) {
    init { require(contractVersion > 0) }

    fun encode(inputId: String, durationMillis: Long): String {
        require(durationMillis >= 0) { "Duration must not be negative" }
        val value = when (unit) {
            DurationUnit.MILLISECONDS -> durationMillis
            DurationUnit.SECONDS -> {
                require(durationMillis % 1000 == 0L) { "Integer seconds cannot represent this input exactly" }
                durationMillis / 1000
            }
        }
        return Json.encodeToString(DurationWirePayload(inputId, contractVersion, value))
    }
}

/** Models a permissive client that parses a payload but applies its own unit assumption. */
class DurationConsumer(
    private val clientId: String,
    private val contractVersion: Long,
    private val assumedUnit: DurationUnit
) {
    init { require(clientId.isNotBlank() && contractVersion > 0) }

    fun decide(wire: String): ClientContractDecision {
        val payload = Json.decodeFromString<DurationWirePayload>(wire)
        val millis = when (assumedUnit) {
            DurationUnit.MILLISECONDS -> payload.duration
            DurationUnit.SECONDS -> Math.multiplyExact(payload.duration, 1000L)
        }
        return ClientContractDecision(payload.inputId, clientId, contractVersion, "durationMillis:$millis")
    }
}

data class ContractUnitExperiment(
    val caseId: String,
    val logicalDurationMillis: Long,
    val producerUnit: DurationUnit,
    val consumerUnit: DurationUnit,
    val wirePayload: String,
    val evaluation: ContractCompatibilityResult
)

/** Execute real serialization and unit interpretation instead of supplying precomputed outcomes. */
class ContractUnitDriftScenario {
    fun run(durationMillis: Long = 2000): List<ContractUnitExperiment> {
        val inputId = "session-duration-001"
        val reference = DurationConsumer("web", 1, DurationUnit.MILLISECONDS).decide(
            DurationProducer(1, DurationUnit.MILLISECONDS).encode(inputId, durationMillis)
        )
        fun experiment(id: String, version: Long, producerUnit: DurationUnit): ContractUnitExperiment {
            val wire = DurationProducer(version, producerUnit).encode(inputId, durationMillis)
            val observed = DurationConsumer("mobile", version, DurationUnit.SECONDS).decide(wire)
            return ContractUnitExperiment(id, durationMillis, producerUnit, DurationUnit.SECONDS, wire,
                ContractCompatibilityInvariant().evaluate(reference, observed))
        }
        return listOf(
            experiment("compatible-upgrade", 2, DurationUnit.SECONDS),
            experiment("unit-drift", 2, DurationUnit.MILLISECONDS),
            experiment("same-version-drift", 1, DurationUnit.MILLISECONDS)
        )
    }
}

object ContractUnitDriftMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "This fixed demonstration accepts no arguments" }
        ContractUnitDriftScenario().run().forEach {
            println("${it.caseId}: producerUnit=${it.producerUnit}, consumerUnit=${it.consumerUnit}, " +
                "reference=${it.evaluation.reference.outcome}, observed=${it.evaluation.observed.outcome}, " +
                "${it.evaluation.status}, detected=${it.evaluation.detected}")
        }
    }
}
