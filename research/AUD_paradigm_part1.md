# Technical Report: Unsupervised Acoustic Unit Discovery for Cough and Ambient Sound Analysis

**Project Context:**
The current Android application records short audio clips (coughs + ambient sound), which are then analyzed by a desktop JVM companion. The existing analysis method, relying on a single 14-dimensional DSP/MFCC feature vector per whole clip fed into a k-means codebook, has proven suboptimal. This report evaluates the hypothesis that treating each recording as a sequence of phoneme-like sub-units, interspersed with speech and non-human sounds, will yield superior analysis. The goal is to fractionate recordings, analyze sub-units, build custom "phoneme" libraries, and represent recordings as token sequences for improved classification and separation.

---

## 1. Is Montreal Forced Aligner (MFA) Appropriate Here?

The Montreal Forced Aligner (MFA) is a powerful, open-source toolkit designed for **forced alignment** of speech audio. Forced alignment is the process of automatically aligning an orthographic transcript of an utterance to its corresponding audio recording, producing time-stamped phoneme and word boundaries.

### What MFA Actually Requires:

1.  **Transcripts:** This is the most critical requirement. MFA needs a precise, word-level transcript for each audio file it processes. Without a transcript, MFA has no reference to align against.
2.  **Pronunciation Dictionary (Lexicon):** A dictionary that maps each word in the transcript to its sequence of phonemes (e.g., "cough" -> /k ɑ f/). MFA uses this to understand the expected phonemic structure of the spoken words.
3.  **Acoustic Model:** A statistical model (traditionally HMM-GMM, more recently DNN-based) trained on speech data. This model learns the acoustic characteristics of different phonemes and how they combine. MFA typically uses pre-trained acoustic models for various languages.

### Why MFA Can't Bootstrap Unit Discovery for *This* Project:

MFA is fundamentally a **supervised alignment tool for speech**, not an unsupervised discovery tool for arbitrary sounds.

*   **Absence of Transcripts for Non-Speech:** Our primary goal is to discover "phoneme-like" units for *coughs* and *ambient sounds* (typing, car horns). These sounds do not have standard linguistic transcripts or established phonemic representations. We are trying to *discover* these units, not align pre-defined ones.
*   **Speech-Centric Acoustic Models:** MFA's acoustic models are trained exclusively on human speech. They are optimized to differentiate between speech phonemes (e.g., /p/, /b/, /a/, /i/). They lack the capacity to model, recognize, or differentiate between non-speech events like different types of coughs, the distinct sounds of a keyboard click, or a car horn. Applying a speech acoustic model to non-speech sounds would yield meaningless results, as the model would attempt to map these sounds to speech phonemes it was never designed to represent.
*   **Irrelevance of Pronunciation Dictionaries:** Since we are not dealing with spoken words, the concept of a pronunciation dictionary for "cough" or "typing" is nonsensical in the linguistic context MFA operates within.

### Where (If Anywhere) MFA Fits Later:

While MFA is inappropriate for *unsupervised unit discovery* of non-speech, it could potentially fit into a *very specific, downstream* part of the project, under highly constrained circumstances:

1.  **Alignment of *Actual Speech Segments*:** If, after segmenting recordings, we identify segments that *are* actual human speech (e.g., a user speaking after a cough), and we have transcripts for *those specific speech segments*, then MFA could be used to align those speech segments to their transcripts. This would allow for precise timing of spoken words and phonemes within the speech portions of the recordings. However, this is a secondary concern to the core problem of non-speech unit discovery.
2.  **Hypothetical "Cough Language" Alignment (Highly Speculative):** If, through extensive unsupervised AUD, we successfully discover and *manually label* a robust set of discrete "cough phonemes" (e.g., `[onset_burst, glottal_release, exhalation_hiss]`) and then manually "transcribe" sequences of these units for specific cough types, one could *theoretically* adapt an MFA-like framework to align these "cough transcripts" to cough audio. This would require building a custom "cough acoustic model" and "cough pronunciation dictionary" from scratch, which is a significant research undertaking far beyond the scope of using MFA as an off-the-shelf tool.

**Conclusion for MFA:** For the primary goal of unsupervised acoustic unit discovery in non-speech (coughs, ambient sounds), MFA is **not an appropriate tool**. Its requirements (transcripts, speech-centric models) fundamentally clash with the problem of discovering units in unlabeled, non-linguistic audio. We need a paradigm shift towards unsupervised learning.

---

## 2. The Correct Paradigm: Unsupervised Acoustic Unit Discovery (AUD) and Self-Supervised Discrete Units

The core idea for our project is to treat each recording as a sequence of discrete, phoneme-like sub-units. This necessitates **Unsupervised Acoustic Unit Discovery (AUD)**, where we learn these units directly from raw audio data without explicit labels. The paradigm involves extracting rich representations from audio and then clustering these representations to identify discrete "acoustic tokens."

Here's a comparison of concrete methods, considering their pros, cons, data/compute needs, and realism for a desktop JVM (optionally calling Python/ONNX) and an Android ON-DEVICE decoder.

### 2.1. HuBERT / wav2vec2 + k-means Cluster IDs

*   **Mechanism:** These are state-of-the-art self-supervised learning (SSL) models, primarily developed for speech.
    *   **wav2vec2:** Learns contextualized representations by predicting masked latent speech representations and using a contrastive loss.
    *   **HuBERT (Hidden Unit BERT):** Iteratively improves representations by clustering MFCCs (or other features) to generate pseudo-labels, then training a BERT-like model to predict these pseudo-labels from masked inputs.
    *   **Unit Discovery:** After obtaining frame-level embeddings from an intermediate layer of a pre-trained wav2vec2/HuBERT model, these continuous embeddings are clustered using k-means (or another clustering algorithm) to derive discrete "acoustic units" or "pseudo-labels." Each cluster ID becomes a unique unit.
*   **Pros:**
    *   **State-of-the-Art Representations:** These models learn incredibly rich, context-aware, and robust acoustic representations, far surpassing traditional DSP features like MFCCs in their ability to capture subtle distinctions.
    *   **Transfer Learning:** Pre-trained models (e.g., on LibriSpeech, VoxPopuli) provide a strong starting point, avoiding the need for massive data and compute for initial training.
    *   **Robustness:** The learned embeddings are often robust to noise and variations in recording conditions.
*   **Cons:**
    *   **Speech-Centric Bias:** The primary limitation is that these models are predominantly trained on *human speech*. While they learn general acoustic patterns, their representations might not be optimal for purely non-speech sounds like coughs, typing, or car horns without domain adaptation or fine-tuning. They might encode speech-specific features that are irrelevant or detrimental to non-speech tasks.
    *   **Computational Cost (Inference):** Even for inference, these models can be computationally intensive, requiring GPUs for efficient processing, especially for large datasets.
    *   **Python Dependency:** The most common implementations are in PyTorch or TensorFlow, requiring Python for execution.
*   **Data Needs:** Large amounts of unlabeled audio for fine-tuning or domain adaptation if the pre-trained models are insufficient for non-speech.
*   **Compute Needs:** GPU for efficient inference on the desktop. Training/fine-tuning requires significant GPU resources.
*   **Realism for JVM/Android:**
    *   **Desktop JVM:** Feasible by calling Python scripts (e.g., via `ProcessBuilder`) or, more robustly, by converting the models to **ONNX format** and using ONNX Runtime for Java. This is a common and recommended approach for integrating deep learning models into JVM applications.
    *   **Android ON-DEVICE:** Challenging for full models due to size and computational demands. Requires model quantization (e.g., to INT8), pruning, or distillation into smaller architectures. ONNX Runtime Mobile or TensorFlow Lite could be used, but performance and battery drain are significant concerns. A highly compressed embedding extractor might be feasible.

### 2.2. VQ-VAE / VQ-CPC

*   **Mechanism:**
    *   **VQ-VAE (Vector Quantized Variational Autoencoder):** An autoencoder architecture that learns to map continuous input features (e.g., spectrograms) to discrete latent codes. The encoder outputs a continuous latent vector, which is then "quantized" by replacing it with the closest vector from a learnable codebook. The decoder reconstructs the input from this quantized code. The vectors in the codebook *are* the discrete acoustic units.
    *   **VQ-CPC (Vector Quantized Contrastive Predictive Coding):** Combines the predictive learning of CPC (predicting future representations from past ones using a contrastive loss) with vector quantization to discretize the learned representations.
*   **Pros:**
    *   **Explicitly Designed for Discrete Units:** These models are inherently built to learn discrete representations, making them a direct fit for AUD.
    *   **Domain Agnostic (in principle):** Can be trained from scratch on *any* type of audio data (speech, non-speech, mixed) to learn domain-specific units without relying on linguistic assumptions.
    *   **End-to-End Learning:** Can learn features and units simultaneously.
*   **Cons:**
    *   **High Training Cost:** Training VQ-VAE or VQ-CPC from scratch requires substantial amounts of unlabeled data and significant GPU computational resources and time. This is a major research effort.
    *   **Hyperparameter Sensitivity:** These models can be sensitive to hyperparameter choices (e.g., codebook size, learning rates, loss weights).
    *   **Lack of Pre-trained Non-Speech Models:** While pre-trained VQ-VAEs exist for speech, readily available, high-quality pre-trained models specifically for *non-speech* AUD are less common compared to wav2vec2/HuBERT.
*   **Data Needs:** Very large amounts of unlabeled audio from the target domain (coughs, ambient sounds) for effective training from scratch.
*   **Compute Needs:** Significant GPU resources for training. Inference can be lighter, similar to other deep learning models.
*   **Realism for JVM/Android:**
    *   **Training:** Not realistic for a small team without dedicated ML infrastructure.
    *   **Inference:** Similar to wav2vec2/HuBERT, feasible via ONNX on desktop, challenging for on-device Android without heavy optimization.

### 2.3. Bayesian Nonparametric AUD (HMM/DPGMM, Ondel et al.)

*   **Mechanism:** These methods use probabilistic models to segment and cluster acoustic features.
    *   **Hidden Markov Models (HMMs):** Model sequences of observations (acoustic features) as being generated by a sequence of hidden states (acoustic units).
    *   **Dirichlet Process Gaussian Mixture Models (DPGMMs):** A nonparametric Bayesian extension of GMMs, where the number of clusters (acoustic units) is not fixed beforehand but inferred from the data.
    *   **Ondel et al. (e.g., "Bayesian Nonparametric Discovery of Acoustic Units"):** Often combine HMMs with Dirichlet Process priors to learn both segmentation and the optimal number of acoustic units simultaneously, without requiring pre-specified cluster counts.
*   **Pros:**
    *   **Principled Statistical Approach:** Provides a rigorous, probabilistic framework for AUD.
    *   **Automatic Unit Count:** Nonparametric methods can automatically determine the optimal number of acoustic units, which is a significant advantage over k-means.
    *   **Less Dependent on Deep Learning Infrastructure:** Can be implemented using standard statistical/ML libraries, potentially in JVM, without requiring GPUs or complex deep learning frameworks.
    *   **Interpretability:** The underlying models can be more interpretable than deep neural networks.
*   **Cons:**
    *   **Computational Intensity:** Can be computationally expensive for large datasets, especially for inference and model selection, though typically CPU-bound.
    *   **Feature Engineering:** Performance heavily relies on the quality of hand-crafted acoustic features (e.g., MFCCs, spectral features). May not capture as rich contextual information as deep learning embeddings.
    *   **Implementation Complexity:** Implementing these from scratch can be complex, and readily available JVM libraries for advanced Bayesian nonparametric models might be limited.
*   **Data Needs:** Unlabeled audio.
*   **Compute Needs:** Primarily CPU-intensive. Can be slow for large datasets.
*   **Realism for JVM/Android:**
    *   **Desktop JVM:** Feasible to implement or integrate existing libraries (if available) for training and inference. Might require custom implementation of the Bayesian aspects.
    *   **Android ON-DEVICE:** Inference could be feasible if the models are sufficiently simple and optimized, but training is likely desktop-bound.

### 2.4. Simple DSP Onset + Cluster Baselines

*   **Mechanism:**
    1.  **Segmentation:** Use traditional Digital Signal Processing (DSP) techniques to detect "onsets" or "sound events." This could involve energy-based Voice/Sound Activity Detection (VAD/SAD), spectral flux, zero-crossing rate, or other transient detection algorithms. The goal is to segment the continuous audio into short, distinct sub-clips.
    2.  **Feature Extraction:** For each detected sub-clip, extract a fixed-size vector of traditional DSP features. This could be the existing 14-dim MFCCs, or an expanded set including spectral centroid, bandwidth, energy, zero-crossing rate, pitch (if relevant), etc. These features are typically averaged or pooled over the segment.
    3.  **Clustering:** Apply an unsupervised clustering algorithm like k-means (as currently used) or Gaussian Mixture Models (GMMs) to these feature vectors. Each cluster centroid represents a distinct "acoustic