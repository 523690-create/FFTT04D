package com.example.FFTT04M.desktop

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File

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
            val p = SoftmaxLR.probs(w, b, feature(word))
            val best = p.indices.maxByOrNull { p[it] } ?: return "?" to 0.0
            return classes[best] to p[best]
        }
    }

    private fun lettersOf(word: List<String>) = word.map { if (it == "?") "?" else it.takeWhile { c -> c.isLetter() } }

    /** Feature vector = unigram + **bigram** letter fractions. Bigrams ("N>B") capture sequential
     *  structure — e.g. a cough BRACKETED by noise (…N>B…B>N…) reads differently from pure noise,
     *  which the order-blind unigram histogram can't see. Vocabulary is learned from the training set. */
    private fun featurize(word: List<String>, vocab: List<String>): DoubleArray {
        val ls = lettersOf(word)
        val x = DoubleArray(vocab.size); var tot = 0
        for (l in ls) { val i = vocab.indexOf(l); if (i >= 0) x[i]++; tot++ }
        for (k in 0 until ls.size - 1) { val i = vocab.indexOf("${ls[k]}>${ls[k + 1]}"); if (i >= 0) x[i]++ }
        if (tot > 0) for (i in x.indices) x[i] /= tot
        return x
    }

    /** Observed unigrams + bigrams across the samples → fixed feature vocabulary. */
    private fun buildVocab(samples: List<Pair<List<String>, String>>): List<String> {
        val uni = LinkedHashSet<String>(); val bi = LinkedHashSet<String>()
        for ((word, _) in samples) {
            val ls = lettersOf(word); uni.addAll(ls)
            for (k in 0 until ls.size - 1) bi.add("${ls[k]}>${ls[k + 1]}")
        }
        return uni.toList() + bi.toList()
    }

    fun train(samples: List<Pair<List<String>, String>>, classes: List<String>): Model {
        val vocab = buildVocab(samples)
        val xs = samples.map { featurize(it.first, vocab) }
        val ys = samples.map { classes.indexOf(it.second) }
        val (w, b) = SoftmaxLR.train(xs, ys, classes.size)
        return Model(vocab, classes, w, b)
    }

    /** k-fold accuracy (deterministic split). Vocabulary is built per train-fold (no leakage). */
    fun crossVal(samples: List<Pair<List<String>, String>>, classes: List<String>, folds: Int = 5): Double {
        if (samples.size < folds) return 0.0
        var correct = 0; var total = 0
        for (k in 0 until folds) {
            val test = samples.filterIndexed { i, _ -> i % folds == k }
            val trainSet = samples.filterIndexed { i, _ -> i % folds != k }
            if (trainSet.isEmpty() || test.isEmpty()) continue
            val model = train(trainSet, classes)
            for ((word, label) in test) { total++; if (model.predict(word).first == label) correct++ }
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
