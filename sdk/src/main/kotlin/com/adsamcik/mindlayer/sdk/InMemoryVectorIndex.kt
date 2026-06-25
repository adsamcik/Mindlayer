package com.adsamcik.mindlayer.sdk

/**
 * In-memory vector index for cosine similarity search.
 *
 * **Scope:** simple brute-force top-K cosine over a few thousand vectors,
 * kept fully in client-process RAM. Not an ANN index. Not persistent.
 * Not multi-process. If you need disk persistence, use SQLCipher in your
 * own app; if you need ANN, integrate a real library. Search is O(n) and
 * intended for <10K entries.
 *
 * **Vectors must be L2-normalized** for cosine = dot product. Use
 * [Mindlayer.embed] with `normalize = true` (default) or call [normalize]
 * here before insertion.
 *
 * **Sensitivity:** vectors are derivable to original text via inversion
 * attacks. Do NOT log entries. Do NOT export across UIDs without explicit
 * consent. See docs/architecture/AIDL_STABILITY.md § Embeddings privacy.
 *
 * Thread-safe: searches snapshot entries before scoring so long-running reads
 * don't block writers; writes use a single lock.
 */
class InMemoryVectorIndex {
    private data class Entry(val id: String, val vector: FloatArray, val payload: Any? = null) {
        override fun equals(other: Any?) = other is Entry && id == other.id
        override fun hashCode() = id.hashCode()
    }

    private val lock = Any()
    private val entries = mutableListOf<Entry>()
    private var dim: Int = -1

    val size: Int get() = synchronized(lock) { entries.size }
    val dimension: Int get() = synchronized(lock) { dim }

    /** Insert / replace by id. Returns true if a previous entry was replaced. */
    fun put(id: String, vector: FloatArray, payload: Any? = null): Boolean = synchronized(lock) {
        require(vector.isNotEmpty()) { "Vector must be non-empty" }
        if (dim == -1) dim = vector.size
        require(vector.size == dim) {
            "Vector dimension ${vector.size} does not match index dimension $dim"
        }
        val copy = vector.copyOf()
        val existing = entries.indexOfFirst { it.id == id }
        if (existing >= 0) {
            entries[existing] = Entry(id, copy, payload)
            true
        } else {
            entries += Entry(id, copy, payload)
            false
        }
    }

    fun remove(id: String): Boolean = synchronized(lock) { entries.removeAll { it.id == id } }

    fun clear(): Unit = synchronized(lock) { entries.clear(); dim = -1 }

    /** All ids currently indexed. */
    fun ids(): List<String> = synchronized(lock) { entries.map { it.id } }

    /**
     * Top-[k] by cosine similarity to [query]. Returns descending by score.
     * Assumes both [query] and stored vectors are L2-normalized — score is
     * just dot product. If [query] is not normalized, call [normalize] first
     * or rescore via cosine. Runtime is O(n) over indexed entries.
     */
    fun search(query: FloatArray, k: Int): List<Hit> {
        require(k > 0) { "k must be positive" }
        val snapshot = synchronized(lock) {
            require(query.size == dim || dim == -1) { "Query dim ${query.size} != index dim $dim" }
            entries.toList()
        }
        if (snapshot.isEmpty()) return emptyList()
        val heap = java.util.PriorityQueue<Hit>(k, compareBy { it.score })
        for (e in snapshot) {
            val score = dot(query, e.vector)
            if (heap.size < k) heap.add(Hit(e.id, score, e.payload))
            else if (score > (heap.peek()?.score ?: Float.NEGATIVE_INFINITY)) {
                heap.poll()
                heap.add(Hit(e.id, score, e.payload))
            }
        }
        return heap.toList().sortedByDescending { it.score }
    }

    data class Hit(val id: String, val score: Float, val payload: Any? = null)

    companion object {
        /** Compute L2 norm and divide. Returns a new array; safe on zero vectors (returns zeros). */
        fun normalize(v: FloatArray): FloatArray {
            var sum = 0.0
            for (x in v) sum += x.toDouble() * x.toDouble()
            val n = kotlin.math.sqrt(sum).toFloat()
            if (n == 0f) return v.copyOf()
            val out = FloatArray(v.size)
            for (i in v.indices) out[i] = v[i] / n
            return out
        }

        private fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0.0
            for (i in a.indices) s += a[i].toDouble() * b[i].toDouble()
            return s.toFloat()
        }
    }
}
