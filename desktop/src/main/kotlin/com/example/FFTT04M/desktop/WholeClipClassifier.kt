package com.example.FFTT04M.desktop

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import kotlin.math.sqrt

/**
 * Whole-clip "second opinion" classifier: one 14-dim [WholeClipFeatures] vector per clip (including
 * `syllabic`, which IS meaningful at clip scale — high for speech/counting), z-normalised, fed to a
 * softmax LR ([SoftmaxLR]). Unlike the fragment histogram it does NOT depend on fragment count, so it
 * stays stable on the short coughs here (median ~4 fragments). Complements the phoneme decode.
 *
 * Serialised to `data/codebooks/<tag>_wholeclip.json` (classes, mean, std, weights) for reuse.
 */
object WholeClipClassifier {

    class Model(
        val classes: List<String>, val mean: DoubleArray, val std: DoubleArray,
        val w: Array<DoubleArray>, val b: DoubleArray,
    ) {
        private fun z(x: DoubleArray) = DoubleArray(x.size) { (x[it] - mean[it]) / std[it] }

        /** label, probability. */
        fun predict(feature: DoubleArray): Pair<String, Double> {
            val p = SoftmaxLR.probs(w, b, z(feature))
            val best = p.indices.maxByOrNull { p[it] } ?: return "?" to 0.0
            return classes[best] to p[best]
        }
    }

    fun train(samples: List<Pair<DoubleArray, String>>, classes: List<String>): Model {
        val d = samples.first().first.size
        val mean = DoubleArray(d); val std = DoubleArray(d)
        for ((x, _) in samples) for (i in 0 until d) mean[i] += x[i]
        for (i in 0 until d) mean[i] /= samples.size
        for ((x, _) in samples) for (i in 0 until d) { val e = x[i] - mean[i]; std[i] += e * e }
        for (i in 0 until d) std[i] = sqrt(std[i] / samples.size).coerceAtLeast(1e-9)
        val xs = samples.map { (x, _) -> DoubleArray(d) { (x[it] - mean[it]) / std[it] } }
        val ys = samples.map { classes.indexOf(it.second) }
        val (w, b) = SoftmaxLR.train(xs, ys, classes.size)
        return Model(classes, mean, std, w, b)
    }

    fun crossVal(samples: List<Pair<DoubleArray, String>>, classes: List<String>, folds: Int = 5): Double {
        if (samples.size < folds) return 0.0
        var correct = 0; var total = 0
        for (k in 0 until folds) {
            val test = samples.filterIndexed { i, _ -> i % folds == k }
            val trainSet = samples.filterIndexed { i, _ -> i % folds != k }
            if (trainSet.isEmpty() || test.isEmpty()) continue
            val model = train(trainSet, classes)
            for ((x, label) in test) { total++; if (model.predict(x).first == label) correct++ }
        }
        return if (total > 0) correct.toDouble() / total else 0.0
    }

    fun save(model: Model, out: File) {
        out.writeText(GsonBuilder().create().toJson(mapOf(
            "classes" to model.classes, "mean" to model.mean, "std" to model.std, "w" to model.w, "b" to model.b)))
    }

    fun load(f: File): Model? = try {
        val o = JsonParser.parseString(f.readText()).asJsonObject
        Model(o.getAsJsonArray("classes").map { it.asString },
            o.getAsJsonArray("mean").map { it.asDouble }.toDoubleArray(),
            o.getAsJsonArray("std").map { it.asDouble }.toDoubleArray(),
            o.getAsJsonArray("w").map { row -> row.asJsonArray.map { it.asDouble }.toDoubleArray() }.toTypedArray(),
            o.getAsJsonArray("b").map { it.asDouble }.toDoubleArray())
    } catch (e: Exception) { System.err.println("wholeclip load: ${e.message}"); null }
}
