package com.llamafarm.atmosphere.router

/**
 * Unified 64-bit SimHash implementation for cross-platform capability matching.
 *
 * SimHash is a locality-sensitive hashing algorithm where similar documents
 * produce hashes with high Hamming similarity (few differing bits).
 *
 * This implementation is designed to produce IDENTICAL results to the Python
 * implementation in atmosphere/router/simhash.py.
 *
 * Algorithm:
 * 1. Tokenize text into words (lowercase, min 3 chars, no stopwords)
 * 2. For each token, compute a stable 64-bit hash (FNV-1a)
 * 3. For each bit position, accumulate +1 (bit=1) or -1 (bit=0)
 * 4. Final hash: set bit i to 1 if sum[i] > 0, else 0
 */
object SimHash {

    // FNV-1a 64-bit constants (MUST match Python implementation)
    private const val FNV64_OFFSET_BASIS = -0x340d631b7bdddcdbL  // 0xcbf29ce484222325 as signed
    private const val FNV64_PRIME = 0x00000100000001B3L

    // Stopwords - MUST be identical to Python implementation
    private val STOPWORDS = setOf(
        "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
        "of", "with", "by", "from", "as", "is", "was", "are", "were", "be",
        "been", "being", "have", "has", "had", "do", "does", "did", "will",
        "would", "should", "could", "may", "might", "can", "this", "that",
        "what", "which", "who", "when", "where", "why", "how", "i", "you",
        "he", "she", "it", "we", "they", "my", "your", "his", "her", "its",
        "our", "their", "all", "each", "every", "both", "few", "more", "most",
        "other", "some", "such", "no", "not", "only", "same", "so", "than",
        "too", "very", "just", "about", "into", "through", "during", "before",
        "after", "above", "below", "between", "under", "again", "further",
        "then", "once", "here", "there"
    )

    private const val MIN_WORD_LENGTH = 3

    /**
     * Compute FNV-1a 64-bit hash of a string.
     *
     * This is a simple, fast, and deterministic hash function.
     * Produces identical results to Python implementation.
     *
     * @param text String to hash
     * @return 64-bit hash value
     */
    fun fnv1a64(text: String): Long {
        var hash = FNV64_OFFSET_BASIS
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (b in bytes) {
            hash = hash xor (b.toLong() and 0xFF)
            hash *= FNV64_PRIME
        }
        return hash
    }

    /**
     * Extract tokens from text for SimHash computation.
     *
     * Uses identical logic to Python implementation:
     * - Lowercase
     * - Split on non-alphanumeric
     * - Filter short words and stopwords
     * - Deduplicate but preserve order
     *
     * @param text Input text
     * @param maxTokens Maximum number of tokens to return
     * @return List of lowercase tokens
     */
    fun extractTokens(text: String, maxTokens: Int = 50): List<String> {
        if (text.isEmpty()) return emptyList()

        // Extract words (3+ alphanumeric chars)
        val wordPattern = Regex("\\b[a-zA-Z0-9]{3,}\\b")
        val words = wordPattern.findAll(text.lowercase()).map { it.value }.toList()

        // Filter stopwords and deduplicate while preserving order
        val seen = mutableSetOf<String>()
        val tokens = mutableListOf<String>()
        
        for (word in words) {
            if (word !in STOPWORDS && word !in seen) {
                seen.add(word)
                tokens.add(word)
                if (tokens.size >= maxTokens) break
            }
        }

        return tokens
    }

    /**
     * Compute 64-bit SimHash of text.
     *
     * SimHash produces locality-sensitive hashes where similar texts
     * have hashes with high Hamming similarity (few differing bits).
     *
     * @param text Input text (description, keywords joined, etc.)
     * @return 64-bit SimHash value
     */
    fun computeSimHash(text: String): Long {
        val tokens = extractTokens(text)
        return computeSimHashFromTokens(tokens)
    }

    /**
     * Compute 64-bit SimHash from pre-extracted tokens.
     *
     * Use this when you've already extracted keywords/tokens.
     *
     * @param tokens List of tokens (already lowercase, filtered)
     * @return 64-bit SimHash value
     */
    fun computeSimHashFromTokens(tokens: List<String>): Long {
        if (tokens.isEmpty()) return 0L

        // Accumulator for each bit position
        val bitSums = IntArray(64)

        for (token in tokens) {
            val tokenHash = fnv1a64(token.lowercase())

            // For each bit position, add +1 or -1
            for (i in 0 until 64) {
                if ((tokenHash shr i) and 1L == 1L) {
                    bitSums[i] += 1
                } else {
                    bitSums[i] -= 1
                }
            }
        }

        // Build final hash from sign of sums
        var result = 0L
        for (i in 0 until 64) {
            if (bitSums[i] > 0) {
                result = result or (1L shl i)
            }
        }

        return result
    }

    /**
     * Compute Hamming distance between two 64-bit hashes.
     *
     * Hamming distance = number of differing bits.
     *
     * @param hash1 First 64-bit hash
     * @param hash2 Second 64-bit hash
     * @return Number of differing bits (0-64)
     */
    fun hammingDistance(hash1: Long, hash2: Long): Int {
        val xor = hash1 xor hash2
        return java.lang.Long.bitCount(xor)
    }

    /**
     * Compute similarity between two SimHashes.
     *
     * Returns value in [0, 1] where:
     * - 1.0 = identical hashes
     * - 0.0 = completely different (64 bits differ)
     *
     * @param hash1 First 64-bit SimHash
     * @param hash2 Second 64-bit SimHash
     * @return Similarity score in [0, 1]
     */
    fun similarity(hash1: Long, hash2: Long): Float {
        if (hash1 == 0L || hash2 == 0L) return 0f
        val distance = hammingDistance(hash1, hash2)
        return 1f - (distance / 64f)
    }

    /**
     * Check if two SimHashes are similar.
     *
     * @param hash1 First 64-bit SimHash
     * @param hash2 Second 64-bit SimHash
     * @param threshold Minimum similarity (default 0.7 = max 19 differing bits)
     * @return True if similarity >= threshold
     */
    fun isSimilar(hash1: Long, hash2: Long, threshold: Float = 0.7f): Boolean {
        return similarity(hash1, hash2) >= threshold
    }

    /**
     * Generate explanation of SimHash computation for debugging.
     */
    fun explain(text: String): String {
        val tokens = extractTokens(text)
        val simhash = computeSimHash(text)

        val sb = StringBuilder()
        sb.appendLine("SimHash Analysis")
        sb.appendLine("================")
        sb.appendLine("Input: ${text.take(100)}${if (text.length > 100) "..." else ""}")
        sb.appendLine("Tokens (${tokens.size}): ${tokens.take(10).joinToString()}${if (tokens.size > 10) "..." else ""}")
        sb.appendLine("SimHash: 0x${simhash.toULong().toString(16).padStart(16, '0')}")

        sb.appendLine()
        sb.appendLine("Token hashes:")
        tokens.take(5).forEach { token ->
            val h = fnv1a64(token)
            sb.appendLine("  $token: 0x${h.toULong().toString(16).padStart(16, '0')}")
        }
        if (tokens.size > 5) {
            sb.appendLine("  ... and ${tokens.size - 5} more tokens")
        }

        return sb.toString()
    }

    /**
     * Verification test vectors.
     * These MUST match the Python implementation exactly.
     */
    object TestVectors {
        // FNV-1a test vectors (input -> expected hash as signed Long)
        // Generated by Python implementation - DO NOT MODIFY
        val fnv1aTests = listOf(
            "" to -3750763034362895579L,          // 0xcbf29ce484222325
            "hello" to -6615550055289275125L,     // 0xa430d84680aabd0b
            "world" to 5717881983045765875L,      // 0x4f59ff5e730c8af3
            "test" to -439409999022904539L,       // 0xf9e6e6ef197c2b25
            "capability" to -8734568275770876249L, // 0x86c8946e5047dea7
            "router" to -6855704586828942012L     // 0xa0dba500590b7544
        )

        /**
         * Verify FNV-1a implementation matches expected values.
         */
        fun verifyFnv1a(): Boolean {
            return fnv1aTests.all { (input, expected) ->
                fnv1a64(input) == expected
            }
        }

        /**
         * Run all verification tests.
         */
        fun runAll(): String {
            val sb = StringBuilder()
            sb.appendLine("SimHash Verification")
            sb.appendLine("====================")

            sb.appendLine("\nFNV-1a Tests:")
            fnv1aTests.forEach { (input, expected) ->
                val actual = fnv1a64(input)
                val status = if (actual == expected) "✓" else "✗"
                sb.appendLine("  $status \"$input\" -> 0x${actual.toULong().toString(16).padStart(16, '0')} " +
                    "(expected 0x${expected.toULong().toString(16).padStart(16, '0')})")
            }

            sb.appendLine("\nSimHash Similarity Test:")
            val text1 = "Generate images from text descriptions"
            val text2 = "Create pictures from text prompts"
            val text3 = "Play music on speakers"

            val hash1 = computeSimHash(text1)
            val hash2 = computeSimHash(text2)
            val hash3 = computeSimHash(text3)

            sb.appendLine("  \"$text1\" vs")
            sb.appendLine("  \"$text2\"")
            sb.appendLine("  Similarity: ${(similarity(hash1, hash2) * 100).toInt()}% (should be ~70%)")

            sb.appendLine("\n  \"$text1\" vs")
            sb.appendLine("  \"$text3\"")
            sb.appendLine("  Similarity: ${(similarity(hash1, hash3) * 100).toInt()}% (should be ~37%)")

            return sb.toString()
        }
    }
}
