package com.example.disksize.index

internal enum class FinalizePolicy(val wireName: String, val sparseOwners: Boolean, val directoriesOnly: Boolean) {
    BASELINE("baseline", false, false),
    DEDUP("dedup", true, false),
    DIRECTORIES("directories", false, true),
    OPTIMIZED("optimized", true, true);

    companion object {
        val DEFAULT = OPTIMIZED
        fun benchmark(name: String): FinalizePolicy = entries.single { it.wireName == name }
    }
}
