package io.intenttrace.systembenchmark

fun main(args: Array<String>) {
    val config = BenchmarkConfig.parse(args)
    SystemBenchmark(config).run()
    println("Wrote ${config.output.resolve("metrics.json")}")
}
