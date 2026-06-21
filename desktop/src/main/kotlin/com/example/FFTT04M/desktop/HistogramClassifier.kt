package com.example.FFTT04M.desktop

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import kotlin.math.exp

/**
 * Clip classifier over the phoneme **histogram** (a clip = bag of phoneme letters, à la bag-of-words).
 * Multinomial logistic regression (softmax) with L2 + class-balanced loss, so the whole word's
 * distribution decides the class instead of one borderline fragment (the dominant-letter rule's
 * fragility). Feature = letter-fraction vector (incl. `?`); output = label + probability.
 *
 * Pure Kotlin; trained on the desktop from the labelled clips' decoded words and serialised to
 * `data/codebooks/<tag>_classifier.json` for reuse (and later for the phone).
 */
object HistogramClassifier {

    class Model(
        val letters: List<String>,     // feature order (phoneme letters + "?")
        val classes: List<String>,     // output labels
        val w: Array<DoubleArray>,     // [class][feature]
        val b: DoubleArray,            // [class]
    ) {
        fun feature(word: List<String>): DoubleArray = featurize(word, letters)

        /** label, probability. */
        fun predict(word: List<String>): Pair<String, Double> {
            val x = feature(word)
            val logits = DoubleArray(classes.size) { c -> var s = b[c]; for (f in x.indices) s += w[c][f] * x[f]; s }
            val mx = logits.max(); var z = 0.0
            val p = DoubleArray(classes.size) { c -> val e = exp(logits[c] - mx); z += e; e }
            for (c in p.indices) p[c] /= z
            val best = p.indices.maxByOrNull { p[it] } ?: return "?" to 0.0
            return classes[best] to p[best]
        }
    }

    /** Letter-fraction feature vector for a decoded word ("?" is its own feature). Letter granularity
     *  (not 129 sparse phoneme codes) suits the short clips here — phoneme-level overfits. */
    private fun featurize(word: List<String>, vocab: List<String>): DoubleArray {
        val x = DoubleArray(vocab.size); var tot = 0
        for (code in word) {
            val key = if (code == "?") "?" else code.takeWhile { it.isLetter() }
            val idx = vocab.indexOf(key); if (idx >= 0) x[idx]++
            tot++
        }
        if (tot > 0) for (i in x.indices) x[i] /= tot
        return x
    }

    fun train(
        samples: List<Pair<List<String>, String>>, letters: List<String>, classes: List<String>,
        l2: Double = 0.02, lr: Double = 0.5, iters: Int = 500,
    ): Model {
        val f = letters.size; val c = classes.size
        val xs = samples.map { featurize(it.first, letters) }
        val ys = samples.map { classes.indexOf(it.second) }
        val freq = IntArray(c); for (y in ys) if (y >= 0) freq[y]++
        val cw = DoubleArray(c) { if (freq[it] > 0) samples.size.toDouble() / (c * freq[it]) else 0.0 } // class-balanced
        val w = Array(c) { DoubleArray(f) }; val b = DoubleArray(c)
        val m = xs.size.toDouble()
        repeat(iters) {
            val gw = Array(c) { DoubleArray(f) }; val gb = DoubleArray(c)
            for (n in xs.indices) {
                val yi = ys[n]; if (yi < 0) continue
                val x = xs[n]
                val logits = DoubleArray(c) { k -> var s = b[k]; for (j in 0 until f) s += w[k][j] * x[j]; s }
                val mx = logits.max(); var z = 0.0
                val p = DoubleArray(c) { k -> val e = exp(logits[k] - mx); z += e; e }; for (k in 0 until c) p[k] /= z
                val weight = cw[yi]
                for (k in 0 until c) {
                    val err = (p[k] - if (k == yi) 1.0 else 0.0) * weight
                    gb[k] += err; for (j in 0 until f) gw[k][j] += err * x[j]
                }
            }
            for (k in 0 until c) { b[k] -= lr * gb[k] / m; for (j in 0 until f) w[k][j] -= lr * (gw[k][j] / m + l2 * w[k][j]) }
        }
        return Model(letters, classes, w, b)
    }

    /** Stratified-ish k-fold accuracy (deterministic split by hash). */
    fun crossVal(samples: List<Pair<List<String>, String>>, letters: List<String>, classes: List<String>, folds: Int = 5): Double {
        if (samples.size < folds) return 0.0
        val byFold = samples.indices.groupBy { it % folds }
        var correct = 0; var total = 0
        for (k in 0 until folds) {
            val testIdx = byFold[k] ?: continue
            val trainSet = samples.filterIndexed { i, _ -> i % folds != k }
            if (trainSet.isEmpty()) continue
            val model = train(trainSet, letters, classes)
            for (i in testIdx) {
                val (pred, _) = model.predict(samples[i].first)
                total++
                if (pred == samples[i].second) correct++
            }
        }
        return if (total > 0) correct.toDouble() / total else 0.0
    }

    // ---- serialisation ----
    fun save(model: Model, out: File) {
        out.writeText(GsonBuilder().create().toJson(mapOf(
            "letters" to model.letters, "classes" to model.classes, "w" to model.w, "b" to model.b)))
    }

    @Suppress("UNCHECKED_CAST")
    fun load(f: File): Model? = try {
        val o = JsonParser.parseString(f.readText()).asJsonObject
        val letters = o.getAsJsonArray("letters").map { it.asString }
        val classes = o.getAsJsonArray("classes").map { it.asString }
        val w = o.getAsJsonArray("w").map { row -> row.asJsonArray.map { it.asDouble }.toDoubleArray() }.toTypedArray()
        val b = o.getAsJsonArray("b").map { it.asDouble }.toDoubleArray()
        Model(letters, classes, w, b)
    } catch (e: Exception) { System.err.println("classifier load: ${e.message}"); null }
}
