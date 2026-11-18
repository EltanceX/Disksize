package com.example.disksize.index

/** Only the measurement build reads a policy supplied by the benchmark runner. */
internal class ImportPolicy private constructor(
    val name: String,
    val actionLimit: Int,
    val maxActiveNanos: Long,
    val typedBindings: Boolean,
    val keepNodeParentIndex: Boolean,
) {
    companion object {
        val DEFAULT = ImportPolicy("4096-eager-list", 4096, 0L, false, true)

        fun benchmark(name: String): ImportPolicy {
            val parts = name.split('-')
            require(parts.size in 3..4 && (parts.size == 3 || parts[3] == "lean"))
            val limit = parts[0].toInt().also { require(it in setOf(256, 1024, 4096)) }
            require(parts[1] in setOf("eager", "paced") && parts[2] in setOf("list", "typed"))
            return ImportPolicy(name, limit, if (parts[1] == "paced") 250_000_000L else 0L,
                parts[2] == "typed", parts.size == 3)
        }
    }
}
