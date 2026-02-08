package com.llamafarm.atmosphere.router

import android.util.Log
import com.llamafarm.atmosphere.core.CapabilityAnnouncement
import kotlin.math.max
import kotlin.math.min

private const val TAG = "HashMatcher"

/**
 * Lightweight hash-based capability matching.
 * 
 * Uses a 3-tier cascade:
 * 1. SimHash comparison (64-bit hash of semantic embedding)
 * 2. Keyword overlap matching
 * 3. Fuzzy text matching
 * 
 * NO embedding dependencies - works entirely on hashes and keywords.
 * Perfect for resource-constrained devices.
 */
object HashMatcher {
    
    /**
     * Match result with explanation.
     */
    data class MatchResult(
        val capability: CapabilityAnnouncement,
        val score: Float,
        val method: MatchMethod,
        val explanation: String
    )
    
    enum class MatchMethod {
        HASH_EXACT,      // Hash match > 0.95
        HASH_SIMILAR,    // Hash match 0.7-0.95
        KEYWORD_EXACT,   // Exact keyword match
        KEYWORD_OVERLAP, // Keyword overlap > 0.3
        FALLBACK,        // Best-effort when no semantic match
        TEXT_FUZZY,      // Fuzzy text similarity
        NO_MATCH         // No match found
    }
    
    /**
     * Find best matching capability using hash-first cascade.
     * 
     * @param query User query string
     * @param queryHash Pre-computed 64-bit SimHash of query embedding (0 = compute from keywords)
     * @param capabilities List of available capabilities
     * @param minScore Minimum score threshold (0-1)
     * @return Best matching capability with score and explanation, or null
     */
    fun findBestMatch(
        query: String,
        queryHash: Long = 0L,
        capabilities: List<CapabilityAnnouncement>,
        minScore: Float = 0.3f
    ): MatchResult? {
        if (capabilities.isEmpty()) {
            return null
        }
        
        val queryLower = query.lowercase()
        val queryKeywords = extractKeywords(queryLower)
        
        // Compute query hash from keywords if not provided
        val actualQueryHash = if (queryHash == 0L) {
            computeKeywordHash(queryKeywords)
        } else {
            queryHash
        }
        
        val results = mutableListOf<MatchResult>()
        
        Log.d(TAG, "🔎 Matching query='$query', queryHash=$actualQueryHash, queryKeywords=$queryKeywords")
        
        capabilities.forEach { cap ->
            Log.d(TAG, "  📊 Cap ${cap.capabilityId}: hash=${cap.embeddingHash}, keywords=${cap.keywords}, triggers=${cap.triggers}")
            
            // Tier 1: Hash matching (if both have hashes)
            if (actualQueryHash != 0L && cap.embeddingHash != 0L) {
                val hashScore = computeHashSimilarity(actualQueryHash, cap.embeddingHash)
                Log.d(TAG, "    Hash score: $hashScore")
                if (hashScore >= 0.95f) {
                    results.add(MatchResult(
                        capability = cap,
                        score = hashScore,
                        method = MatchMethod.HASH_EXACT,
                        explanation = "Hash exact match (${(hashScore * 100).toInt()}% similar)"
                    ))
                    return@forEach
                } else if (hashScore >= 0.7f) {
                    results.add(MatchResult(
                        capability = cap,
                        score = hashScore,
                        method = MatchMethod.HASH_SIMILAR,
                        explanation = "Hash similar (${(hashScore * 100).toInt()}% similar)"
                    ))
                    return@forEach
                }
            }
            
            // Tier 2: Keyword matching
            val keywordScore = computeKeywordOverlap(queryKeywords, cap.keywords)
            Log.d(TAG, "    Keyword score: $keywordScore")
            if (keywordScore >= 0.8f) {
                results.add(MatchResult(
                    capability = cap,
                    score = keywordScore,
                    method = MatchMethod.KEYWORD_EXACT,
                    explanation = "Exact keyword match: ${findCommonKeywords(queryKeywords, cap.keywords).joinToString()}"
                ))
                return@forEach
            } else if (keywordScore >= 0.3f) {
                results.add(MatchResult(
                    capability = cap,
                    score = keywordScore,
                    method = MatchMethod.KEYWORD_OVERLAP,
                    explanation = "Keyword overlap (${(keywordScore * 100).toInt()}%): ${findCommonKeywords(queryKeywords, cap.keywords).joinToString()}"
                ))
                return@forEach
            }
            
            // Tier 3: Fuzzy text matching (fallback)
            val textScore = computeFuzzyTextSimilarity(queryLower, cap.label, cap.description, cap.keywords)
            if (textScore >= minScore) {
                results.add(MatchResult(
                    capability = cap,
                    score = textScore,
                    method = MatchMethod.TEXT_FUZZY,
                    explanation = "Text similarity match (${(textScore * 100).toInt()}%)"
                ))
            }
        }
        
        // Return best match
        return results.maxByOrNull { it.score }
    }
    
    /**
     * Find all matching capabilities above threshold.
     */
    fun findAllMatches(
        query: String,
        queryHash: Long = 0L,
        capabilities: List<CapabilityAnnouncement>,
        minScore: Float = 0.3f,
        maxResults: Int = 10
    ): List<MatchResult> {
        if (capabilities.isEmpty()) {
            return emptyList()
        }
        
        val queryLower = query.lowercase()
        val queryKeywords = extractKeywords(queryLower)
        
        val actualQueryHash = if (queryHash == 0L) {
            computeKeywordHash(queryKeywords)
        } else {
            queryHash
        }
        
        val results = mutableListOf<MatchResult>()
        
        capabilities.forEach { cap ->
            var bestScore = 0f
            var bestMethod = MatchMethod.NO_MATCH
            var explanation = ""
            
            // Try hash match
            if (actualQueryHash != 0L && cap.embeddingHash != 0L) {
                val hashScore = computeHashSimilarity(actualQueryHash, cap.embeddingHash)
                if (hashScore > bestScore) {
                    bestScore = hashScore
                    bestMethod = if (hashScore >= 0.95f) MatchMethod.HASH_EXACT else MatchMethod.HASH_SIMILAR
                    explanation = "Hash ${if (hashScore >= 0.95f) "exact" else "similar"} (${(hashScore * 100).toInt()}%)"
                }
            }
            
            // Try keyword match
            val keywordScore = computeKeywordOverlap(queryKeywords, cap.keywords)
            if (keywordScore > bestScore) {
                bestScore = keywordScore
                bestMethod = if (keywordScore >= 0.8f) MatchMethod.KEYWORD_EXACT else MatchMethod.KEYWORD_OVERLAP
                val common = findCommonKeywords(queryKeywords, cap.keywords)
                explanation = "Keywords (${(keywordScore * 100).toInt()}%): ${common.take(3).joinToString()}"
            }
            
            // Try fuzzy text match
            val textScore = computeFuzzyTextSimilarity(queryLower, cap.label, cap.description, cap.keywords)
            if (textScore > bestScore) {
                bestScore = textScore
                bestMethod = MatchMethod.TEXT_FUZZY
                explanation = "Text similarity (${(textScore * 100).toInt()}%)"
            }
            
            if (bestScore >= minScore) {
                results.add(MatchResult(
                    capability = cap,
                    score = bestScore,
                    method = bestMethod,
                    explanation = explanation
                ))
            }
        }
        
        return results.sortedByDescending { it.score }.take(maxResults)
    }
    
    /**
     * Compute similarity between two 64-bit SimHashes.
     * Returns 0-1 where 1 = identical.
     *
     * Uses Hamming similarity: 1 - (differing_bits / 64)
     * This produces identical results to the Python implementation.
     *
     * @see SimHash.similarity
     */
    private fun computeHashSimilarity(hash1: Long, hash2: Long): Float {
        return SimHash.similarity(hash1, hash2)
    }
    
    /**
     * Compute keyword overlap score.
     * Returns 0-1 based on Jaccard similarity.
     */
    private fun computeKeywordOverlap(keywords1: List<String>, keywords2: List<String>): Float {
        if (keywords1.isEmpty() || keywords2.isEmpty()) return 0f
        
        val set1 = keywords1.map { it.lowercase() }.toSet()
        val set2 = keywords2.map { it.lowercase() }.toSet()
        
        val intersection = set1.intersect(set2).size
        val union = set1.union(set2).size
        
        return if (union > 0) {
            intersection.toFloat() / union.toFloat()
        } else {
            0f
        }
    }
    
    /**
     * Find common keywords between two lists.
     */
    private fun findCommonKeywords(keywords1: List<String>, keywords2: List<String>): List<String> {
        val set1 = keywords1.map { it.lowercase() }.toSet()
        val set2 = keywords2.map { it.lowercase() }.toSet()
        return set1.intersect(set2).toList()
    }
    
    /**
     * Compute fuzzy text similarity (last resort).
     * Checks for substring matches in label, description, and keywords.
     */
    private fun computeFuzzyTextSimilarity(
        query: String,
        label: String,
        description: String,
        keywords: List<String>
    ): Float {
        val queryWords = query.split(Regex("\\s+")).filter { it.length >= 3 }
        if (queryWords.isEmpty()) return 0f
        
        var matches = 0
        val totalWords = queryWords.size
        
        queryWords.forEach { word ->
            val wordLower = word.lowercase()
            
            // Check label (highest weight)
            if (label.lowercase().contains(wordLower)) {
                matches += 2
            }
            
            // Check description
            if (description.lowercase().contains(wordLower)) {
                matches += 1
            }
            
            // Check keywords
            keywords.forEach { keyword ->
                if (keyword.lowercase().contains(wordLower) || wordLower.contains(keyword.lowercase())) {
                    matches += 1
                }
            }
        }
        
        // Normalize to 0-1
        val maxPossible = totalWords * 4  // 2 (label) + 1 (desc) + 1 (keywords)
        return min(1f, matches.toFloat() / maxPossible.toFloat())
    }
    
    /**
     * Extract keywords from text.
     * Simple implementation - just split and filter.
     */
    fun extractKeywords(text: String): List<String> {
        val stopWords = setOf(
            "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "as", "is", "was", "are", "were", "be",
            "been", "being", "have", "has", "had", "do", "does", "did", "will",
            "would", "should", "could", "may", "might", "can", "this", "that",
            "what", "which", "who", "when", "where", "why", "how", "i", "you",
            "he", "she", "it", "we", "they", "my", "your", "his", "her", "its",
            "our", "their"
        )
        
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 && it !in stopWords }
            .distinct()
    }
    
    /**
     * Compute 64-bit SimHash from keywords.
     *
     * Uses the unified SimHash algorithm that produces identical results
     * on Python and Kotlin. This is a true locality-sensitive hash where
     * similar keyword sets produce hashes with high Hamming similarity.
     *
     * @see SimHash for the underlying algorithm
     */
    fun computeKeywordHash(keywords: List<String>): Long {
        return SimHash.computeSimHashFromTokens(keywords)
    }

    /**
     * Compute 64-bit SimHash from text.
     *
     * Convenience method that extracts tokens and computes SimHash.
     */
    fun computeTextHash(text: String): Long {
        return SimHash.computeSimHash(text)
    }
    
    /**
     * Debug: explain why a match was chosen.
     */
    fun explainMatch(query: String, capability: CapabilityAnnouncement): String {
        val queryKeywords = extractKeywords(query.lowercase())
        val queryHash = computeKeywordHash(queryKeywords)
        
        val sb = StringBuilder()
        sb.appendLine("Match Analysis for: ${capability.label}")
        sb.appendLine("Query: $query")
        sb.appendLine()
        
        // Hash similarity
        if (queryHash != 0L && capability.embeddingHash != 0L) {
            val hashScore = computeHashSimilarity(queryHash, capability.embeddingHash)
            sb.appendLine("Hash similarity: ${(hashScore * 100).toInt()}%")
        } else {
            sb.appendLine("Hash similarity: N/A")
        }
        
        // Keyword overlap
        val keywordScore = computeKeywordOverlap(queryKeywords, capability.keywords)
        val common = findCommonKeywords(queryKeywords, capability.keywords)
        sb.appendLine("Keyword overlap: ${(keywordScore * 100).toInt()}%")
        sb.appendLine("Common keywords: ${common.joinToString()}")
        
        // Text similarity
        val textScore = computeFuzzyTextSimilarity(query.lowercase(), capability.label, capability.description, capability.keywords)
        sb.appendLine("Text similarity: ${(textScore * 100).toInt()}%")
        
        return sb.toString()
    }
}
