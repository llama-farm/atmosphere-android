package com.llamafarm.atmosphere.rag

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Local RAG (Retrieval Augmented Generation) store for on-device knowledge retrieval.
 * 
 * Uses a simple TF-IDF/BM25-inspired approach for text similarity since we don't
 * have dedicated embedding models on-device yet. This provides surprisingly good
 * results for many use cases without requiring GPU-heavy embedding computation.
 * 
 * Future: Can be upgraded to use actual embeddings when available.
 */
class LocalRagStore {
    
    companion object {
        private const val TAG = "LocalRagStore"
        private const val DEFAULT_TOP_K = 3
        
        // BM25 parameters
        private const val K1 = 1.2
        private const val B = 0.75
        
        // Common English stopwords
        private val STOPWORDS = setOf(
            "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
            "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
            "this", "but", "his", "by", "from", "they", "we", "say", "her",
            "she", "or", "an", "will", "my", "one", "all", "would", "there",
            "their", "what", "so", "up", "out", "if", "about", "who", "get",
            "which", "go", "me", "when", "make", "can", "like", "time", "no",
            "just", "him", "know", "take", "people", "into", "year", "your",
            "good", "some", "could", "them", "see", "other", "than", "then",
            "now", "look", "only", "come", "its", "over", "think", "also",
            "back", "after", "use", "two", "how", "our", "work", "first",
            "well", "way", "even", "new", "want", "because", "any", "these",
            "give", "day", "most", "us", "is", "are", "was", "were", "been",
            "has", "had", "did", "does", "doing", "am"
        )
    }
    
    /**
     * A single document in the index.
     */
    data class Document(
        val id: String,
        val content: String,
        val metadata: JSONObject = JSONObject(),
        // Pre-computed for fast retrieval
        val tokens: List<String> = emptyList(),
        val termFrequencies: Map<String, Int> = emptyMap()
    )
    
    /**
     * A RAG index containing documents.
     */
    data class RagIndex(
        val id: String,
        val documents: MutableList<Document> = mutableListOf(),
        val createdAt: Long = System.currentTimeMillis(),
        // Index-level statistics
        var avgDocLength: Double = 0.0,
        var documentFrequencies: MutableMap<String, Int> = mutableMapOf()
    )
    
    /**
     * Query result with relevance score.
     */
    data class QueryResult(
        val document: Document,
        val score: Double,
        val matchedTerms: List<String>
    )
    
    // All indexes by ID
    private val indexes = ConcurrentHashMap<String, RagIndex>()
    
    /**
     * Create a new RAG index from documents.
     * 
     * @param indexId Unique ID for the index
     * @param documents List of documents to index
     * @return The created index
     */
    suspend fun createIndex(
        indexId: String, 
        documents: List<Pair<String, String>>  // (id, content)
    ): RagIndex = withContext(Dispatchers.Default) {
        Log.i(TAG, "Creating index '$indexId' with ${documents.size} documents")
        
        val index = RagIndex(id = indexId)
        
        // Process each document
        var totalLength = 0
        
        for ((docId, content) in documents) {
            val tokens = tokenize(content)
            val termFreqs = tokens.groupingBy { it }.eachCount()
            
            val doc = Document(
                id = docId,
                content = content,
                tokens = tokens,
                termFrequencies = termFreqs
            )
            
            index.documents.add(doc)
            totalLength += tokens.size
            
            // Update document frequencies
            termFreqs.keys.forEach { term ->
                index.documentFrequencies[term] = 
                    (index.documentFrequencies[term] ?: 0) + 1
            }
        }
        
        // Compute average document length
        if (documents.isNotEmpty()) {
            index.avgDocLength = totalLength.toDouble() / documents.size
        }
        
        indexes[indexId] = index
        Log.i(TAG, "Index '$indexId' created with ${index.documents.size} documents, " +
                   "${index.documentFrequencies.size} unique terms")
        
        index
    }
    
    /**
     * Create index from JSON array of documents.
     */
    suspend fun createIndexFromJson(indexId: String, documentsJson: String): RagIndex {
        val jsonArray = JSONArray(documentsJson)
        val documents = mutableListOf<Pair<String, String>>()
        
        for (i in 0 until jsonArray.length()) {
            val docObj = jsonArray.getJSONObject(i)
            val id = docObj.optString("id", "doc_$i")
            val content = docObj.optString("content", "")
            
            // Also try 'text' field if content is empty
            val text = if (content.isBlank()) docObj.optString("text", "") else content
            
            if (text.isNotBlank()) {
                documents.add(id to text)
            }
        }
        
        return createIndex(indexId, documents)
    }
    
    /**
     * Query an index for relevant documents.
     * 
     * @param indexId The index to query
     * @param query Natural language query
     * @param topK Number of results to return
     * @return List of matching documents with scores
     */
    suspend fun query(
        indexId: String,
        query: String,
        topK: Int = DEFAULT_TOP_K
    ): List<QueryResult> = withContext(Dispatchers.Default) {
        val index = indexes[indexId] 
            ?: throw IllegalArgumentException("Index not found: $indexId")
        
        val queryTokens = tokenize(query).toSet()
        Log.d(TAG, "Querying '$indexId' with ${queryTokens.size} terms: $queryTokens")
        
        if (queryTokens.isEmpty()) {
            return@withContext emptyList()
        }
        
        val numDocs = index.documents.size
        val results = mutableListOf<QueryResult>()
        
        for (doc in index.documents) {
            var score = 0.0
            val matchedTerms = mutableListOf<String>()
            
            for (term in queryTokens) {
                val tf = doc.termFrequencies[term] ?: 0
                if (tf == 0) continue
                
                matchedTerms.add(term)
                
                // BM25 scoring
                val df = index.documentFrequencies[term] ?: 0
                val idf = if (df > 0) {
                    kotlin.math.ln((numDocs - df + 0.5) / (df + 0.5) + 1.0)
                } else {
                    0.0
                }
                
                val docLength = doc.tokens.size.toDouble()
                val lengthNorm = 1 - B + B * (docLength / index.avgDocLength)
                
                val tfNorm = (tf * (K1 + 1)) / (tf + K1 * lengthNorm)
                
                score += idf * tfNorm
            }
            
            if (score > 0) {
                results.add(QueryResult(
                    document = doc,
                    score = score,
                    matchedTerms = matchedTerms
                ))
            }
        }
        
        // Sort by score descending and take top K
        val topResults = results.sortedByDescending { it.score }.take(topK)
        Log.d(TAG, "Query returned ${topResults.size} results (top score: ${topResults.firstOrNull()?.score})")
        
        topResults
    }
    
    /**
     * Query and format results as context for LLM.
     */
    suspend fun queryForContext(
        indexId: String,
        query: String,
        topK: Int = DEFAULT_TOP_K
    ): String {
        val results = query(indexId, query, topK)
        
        if (results.isEmpty()) {
            return "No relevant information found."
        }
        
        return results.mapIndexed { i, result ->
            "Document ${i + 1} (relevance: ${String.format("%.2f", result.score)}):\n${result.document.content}"
        }.joinToString("\n\n---\n\n")
    }
    
    /**
     * Delete an index.
     */
    fun deleteIndex(indexId: String): Boolean {
        val removed = indexes.remove(indexId)
        if (removed != null) {
            Log.i(TAG, "Deleted index '$indexId'")
            return true
        }
        return false
    }
    
    /**
     * Get index information.
     */
    fun getIndex(indexId: String): RagIndex? = indexes[indexId]
    
    /**
     * List all indexes.
     */
    fun listIndexes(): List<RagIndex> = indexes.values.toList()
    
    /**
     * Tokenize text into terms for indexing/querying.
     * Simple but effective tokenization.
     */
    private fun tokenize(text: String): List<String> {
        return text
            .lowercase()
            // Remove punctuation except apostrophes
            .replace(Regex("[^a-z0-9'\\s]"), " ")
            // Split on whitespace
            .split(Regex("\\s+"))
            // Filter out stopwords and very short terms
            .filter { it.length > 2 && it !in STOPWORDS }
            // Stem very basic suffixes (simple Porter-like)
            .map { stemWord(it) }
    }
    
    /**
     * Very simple word stemming.
     */
    private fun stemWord(word: String): String {
        var result = word
        
        // Handle common suffixes
        if (result.endsWith("ing") && result.length > 5) {
            result = result.dropLast(3)
        } else if (result.endsWith("ed") && result.length > 4) {
            result = result.dropLast(2)
        } else if (result.endsWith("ly") && result.length > 4) {
            result = result.dropLast(2)
        } else if (result.endsWith("tion") && result.length > 5) {
            result = result.dropLast(4) + "t"
        } else if (result.endsWith("ness") && result.length > 5) {
            result = result.dropLast(4)
        } else if (result.endsWith("ment") && result.length > 5) {
            result = result.dropLast(4)
        } else if (result.endsWith("able") && result.length > 5) {
            result = result.dropLast(4)
        } else if (result.endsWith("ible") && result.length > 5) {
            result = result.dropLast(4)
        } else if (result.endsWith("ies") && result.length > 4) {
            result = result.dropLast(3) + "y"
        } else if (result.endsWith("es") && result.length > 4) {
            result = result.dropLast(2)
        } else if (result.endsWith("s") && result.length > 3 && !result.endsWith("ss")) {
            result = result.dropLast(1)
        }
        
        return result
    }
}
