## 3. Concrete End-to-End Pipeline for Unsupervised Acoustic Unit Discovery

The goal is to move from whole-clip classification to sub-clip "phoneme" tokenization for non-speech sounds like coughs and ambient noise. This requires a robust pipeline that can segment audio, extract meaningful features from these segments, cluster them into discrete units, and represent clips as sequences of these units.

### 3.1 Sub-Clip Segmentation Methods

Effective segmentation is paramount for non-speech sounds, which often lack the clear periodic structure of speech. For coughs, understanding their typical phases can guide segmentation: an initial "burst" (expiratory effort), an "intermediate" phase (airflow), and sometimes a "voiced" or "post-tussive" phase.

1.  **Energy-Based Onset Detection:** This is the simplest and often most robust method for non-speech events. It identifies points where the audio signal's energy (e.g., RMS energy) crosses a threshold, indicating the start of a sound event.
    *   **Pros:** Computationally inexpensive, easy to implement in Kotlin/JVM, effective for transient sounds like cough bursts or distinct ambient noises.
    *   **Cons:** Can be sensitive to background noise levels, may struggle with continuous sounds or very quiet events.
2.  **Spectral Flux Onset Detection:** This method detects changes in the spectral content of the audio signal. A sudden shift in the frequency distribution often indicates a new sound event.
    *   **Pros:** More robust to overall loudness changes than simple energy, good for detecting percussive sounds or rapid spectral shifts characteristic of coughs.
    *   **Cons:** Slightly more complex than energy-based methods, still threshold-dependent.
3.  **Syllable Nucleus / Mermelstein Algorithm (Adapted):** While originally designed for speech to find vowel nuclei (peaks of sonority), the underlying principle of finding local energy maxima within a smoothed energy contour can be adapted for non-speech. For coughs, this could identify the peak intensity of the burst.
    *   **Pros:** Can identify salient "peaks" within a sound event, potentially useful for segmenting multi-phase coughs.
    *   **Cons:** Requires careful adaptation for non-speech, as "syllables" don't directly apply.
4.  **Hidden Markov Model (HMM) Resegmentation:** This is a more advanced approach. Once initial "rough" segments are obtained (e.g., via energy detection), an HMM can be trained for specific sound types (e.g., "cough," "speech," "noise"). The Viterbi algorithm can then be used to re-segment the audio optimally according to the trained HMM states, potentially aligning better with the underlying acoustic structure (e.g., cough phases).
    *   **Pros:** Can provide more precise and acoustically meaningful segmentation, especially for structured events like coughs.
    *   **Cons:** Requires pre-training HMMs, which adds complexity; not suitable for the minimal first implementation.

**Recommendation for Minimal Implementation:** Start with a combination of **energy-based and spectral flux onset detection**. These are computationally light and effective for identifying discrete events in non-speech audio. For coughs, this can effectively separate individual coughs and even delineate the initial burst.

### 3.2 Feature Extraction Per Sub-Unit

Once segments are identified, a fixed-size acoustic feature vector must be extracted for each. The existing system uses a 14-dimensional DSP/MFCC vector per *whole clip*. For sub-clip analysis, this approach needs to be applied to each *segment*.

*   **MFCC (Mel-Frequency Cepstral Coefficients):** The existing 14-dim MFCCs are a good starting point. They are robust to speaker variability (less relevant for non-speech but still useful) and capture the spectral envelope.
    *   **Process:** For each detected segment, calculate the MFCCs. Since segments will have varying durations, a common approach is to take the mean and variance (or other statistics) of MFCCs across the frames within that segment to produce a single fixed-dimension vector per segment. Alternatively, for very short segments, a single MFCC frame or a few concatenated frames might suffice.
*   **Other DSP Features:** Beyond MFCCs, other simple DSP features like Zero-Crossing Rate (ZCR), Spectral Centroid, Spectral Rolloff, and Spectral Flatness can be highly discriminative for non-speech sounds (e.g., ZCR for differentiating voiced vs. unvoiced, spectral features for timbre). These could be concatenated with MFCCs to form a richer feature vector.

**Recommendation:** For the minimal implementation, **reuse the existing 14-dim MFCC extraction logic** for each sub-clip segment. This maintains consistency and leverages existing code. If segments are very short, consider taking the mean of MFCCs over the segment or using a fixed number of frames.

### 3.3 Unit Clustering and Codebook Building

With feature vectors extracted for each sub-clip segment, the next step is to group similar segments into "acoustic units" or "phonemes."

*   **K-means Clustering:** This is the existing method for clip-level analysis and is an excellent choice for the initial sub-clip implementation.
    *   **Process:** Collect all feature vectors from all segments across a representative dataset. Apply k-means to these vectors. Each cluster centroid represents a unique "acoustic unit" (a "phoneme"). The cluster ID assigned to each segment becomes its token.
    *   **Choosing K:** The number of clusters, K, is crucial.
        *   **Elbow Method:** Plot the within-cluster sum of squares (WCSS) against K. The "elbow" point indicates a good K.
        *   **Silhouette Score:** Measures how similar an object is to its own cluster compared to other clusters. Higher scores indicate better-defined clusters.
        *   **Domain Knowledge:** Consider how many distinct "types" of cough components or noise events are expected.
    *   **Pros:** Simple, fast, widely understood, and directly reuses existing k-means infrastructure.
    *   **Cons:** Requires pre-specifying K, sensitive to initial centroid placement (can be mitigated with k-means++).
*   **Bayesian Nonparametric Clustering (e.g., DPGMM):** As mentioned in the preamble, Dirichlet Process Gaussian Mixture Models (DPGMM) automatically determine the optimal number of clusters (K) from the data.
    *   **Pros:** No need to choose K manually, more flexible for discovering novel units.
    *   **Cons:** Computationally more intensive, more complex to implement in JVM.

**Recommendation:** For the minimal implementation, **start with K-means clustering**. Experiment with K using the elbow method and silhouette score on a representative dataset of segmented coughs and ambient sounds. This provides immediate discrete tokens.

### 3.4 Sequence Representation

Once segments are clustered, each audio clip can be represented as a sequence of these acoustic unit tokens.

*   **Token Strings:** The simplest and most intuitive representation is a string of cluster IDs, e.g., "A B C A D E" for a single cough event, or "N N N C C C N N" for noise followed by a cough.
*   **Token IDs:** Internally, these can be integer IDs (0, 1, 2...).

**Recommendation:** Use **token strings or integer sequences**. This is straightforward to implement in Kotlin and forms the basis for sequence-level analysis.

### 3.5 Sequence-Level Modeling

With clips represented as token sequences, the next step is to analyze and classify these sequences.

1.  **Edit Distance (Levenshtein Distance):** Measures the minimum number of single-character edits (insertions, deletions, substitutions) required to change one sequence into another.
    *   **Pros:** Simple to understand and implement, effective for comparing sequences with slight variations.
    *   **Cons:** Does not account for acoustic similarity between different tokens (e.g., "A" and "B" might be acoustically similar but count as a full substitution).
2.  **Dynamic Time Warping (DTW):** A more sophisticated algorithm that finds the optimal alignment between two time series (or token sequences) that may vary in speed or duration. It can use a custom cost matrix for token similarity.
    *   **Pros:** Robust to temporal distortions, can incorporate acoustic similarity between tokens (e.g., distance between cluster centroids). Excellent for comparing varying-length cough sequences.
    *   **Cons:** Computationally more intensive than Levenshtein, but still feasible for short sequences.
3.  **N-gram Language Models (LMs):** These statistical models predict the likelihood of a token sequence based on the probability of n-grams (sequences of n tokens).
    *   **Process:** Train separate n-gram models for "cough sequences," "speech sequences," and "noise sequences" from labeled data. Classify new sequences based on which model assigns the highest probability.
    *   **Pros:** Captures local sequential dependencies, relatively simple.
    *   **Cons:** Requires a significant amount of labeled token sequences to train robust models.
4.  **Neural Unit LMs (e.g., RNNs, Transformers):** More advanced models that can learn complex, long-range dependencies in token sequences.
    *   **Pros:** Can achieve high accuracy, learn rich representations.
    *   **Cons:** Requires substantial data, more complex to implement and train, potentially overkill for initial classification.

**Recommendation for Minimal Implementation:** Implement **Dynamic Time Warping (DTW)** using the Euclidean distance between cluster centroids as the cost function for token mismatches. This allows for robust comparison of cough sequences against known prototypes (e.g., a "reference cough sequence" vs. a "reference speech sequence") to classify new clips. This directly addresses the need to classify and separate cough vs speech vs environmental tokens at the sequence level.

## 4. Bootstrapping Phoneme Libraries from External Databases

To build a robust "phoneme" library for non-speech, leveraging existing public datasets is crucial. These datasets can provide a diverse range of coughs, respiratory sounds, and environmental noises to seed the initial codebook and enable the system to generalize.

### 4.1 Relevant Public Datasets and Their Contributions

1.  **AudioSet (Google):**
    *   **Provides:** A massive collection of over 2 million human-labeled 10-second sound events from YouTube videos, covering 632 event classes organized hierarchically. Includes many environmental sounds, animal sounds, and some human sounds like "cough" and "speech."
    *   **How it helps:**
        *   **Environmental Noise:** Excellent source for a wide variety of background noises (e.g., "traffic noise," "wind," "door slam") to build a diverse "noise" phoneme library.
        *   **Cough Examples:** Contains a significant number of "cough" labeled segments, which can be extracted to seed the initial cough-specific phoneme clusters.
        *   **Speech Examples:** Provides ample "speech" segments to help differentiate speech-related units from non-speech.
        *   **Pre-trained Embeddings:** Models trained on AudioSet (e.g., VGGish, YAMNet) can provide rich, general-purpose acoustic embeddings, which could be an alternative to MFCCs in the longer term.
2.  **FSD50K (Free Sound Dataset 50k):**
    *   **Provides:** 51,000 sound events from Freesound, manually annotated with 200 classes from the AudioSet ontology. Higher quality and more curated than raw AudioSet.
    *   **How it helps:** Similar to AudioSet but with higher quality and more precise annotations, making it easier to extract clean examples for specific sound classes (e.g., "cough," specific environmental sounds).
3.  **ESC-50 (Environmental Sound Classification):**
    *   **Provides:** A small, curated dataset of 2000 environmental sound recordings (5-second clips) belonging to 50 classes.
    *   **How it helps:** Excellent for bootstrapping specific environmental sound "phonemes" due to its clean, well-defined categories. Useful for ensuring the system can distinguish common ambient sounds relevant to the Android app's environment.
4.  **COUGHVID (EPFL):**
    *   **Provides:** A large, crowdsourced dataset of cough recordings, often accompanied by self-reported metadata (symptoms, demographics). Contains various cough types (dry, wet, shallow, deep).
    *   **How it helps:**
        *   **Cough-Specific Phonemes:** Invaluable for building a comprehensive library of cough "phonemes." The diversity of coughs will help the system learn different acoustic components (burst characteristics, voicing, duration).
        *   **Variability:** Captures real-world variability in cough sounds, which is crucial for a robust system.
5.  **Coswara (IISc):**
    *   **Provides:** Another crowdsourced dataset, primarily from India, focusing on respiratory sounds (coughs, breath, speech) with medical metadata.
    *   **How it helps:** Similar to COUGHVID, provides additional geographical and demographic diversity for cough and respiratory sound "phonemes." Can help identify regional variations or specific respiratory patterns.
6.  **ICBHI 2017 Respiratory Sound Database:**
    *   **Provides:** A clinical dataset of respiratory sounds (wheezes, crackles, normal breath sounds) recorded from patients, often with expert annotations.
    *   **How it helps:** Crucial for differentiating pathological respiratory sounds from normal coughs or speech. While not directly "phonemes" in the traditional sense, the distinct acoustic characteristics of wheezes or crackles can be clustered into unique units, expanding the system's ability to identify specific respiratory events.
7.  **FluSense (UMass Amherst):**
    *   **Provides:** A dataset collected using smart devices, containing coughs, sneezes, speech, and environmental sounds, specifically for flu detection.
    *   **How it helps:** Provides real-world, device-recorded data, which is highly relevant to the project's Android recording context. Good for training units that are robust to recording conditions.

### 4.2 Incremental / Active-Learning Growth Strategy

A static phoneme library will quickly become insufficient. A strategy for incremental growth and active learning is essential:

1.  **Initial Codebook Generation:**
    *   Start by curating a diverse subset of audio from the public datasets (COUGHVID, ICBHI, AudioSet for noise/speech).
    *   Run the full pipeline (segmentation, feature extraction, k-means clustering) on this curated data to generate the initial "phoneme" codebook (cluster centroids).
    *   Assign initial labels to these clusters if possible (e.g., "cough burst," "speech-like," "noise-like") based on the source data.
2.  **New Data Ingestion (from Android App):**
    *   When new audio clips arrive from the Android app, process them through segmentation and feature extraction.
    *   For each new segment, find the *closest existing cluster centroid* in the current codebook (e.g., using Euclidean distance).
    *   Assign the corresponding token ID to the segment.
3.  **Novel Unit Detection / Active Learning Candidates:**
    *   **Distance Thresholding:** If a new segment's feature vector is "far" from *all* existing cluster centroids (i.e., its distance to the closest centroid exceeds a predefined threshold), it's a candidate for a novel acoustic unit.
    *   **Low Confidence Assignments:** Segments that are equidistant to multiple clusters, or whose assigned cluster has a very low confidence score (if using probabilistic clustering), can also be flagged.
    *   **Human-in-the-Loop (Active Learning):** Present these "novel" or "ambiguous" segments to a human expert for review.
        *   The expert can confirm if it's a truly new type of sound (e.g., a new type of cough component, a previously unencountered ambient noise).
        *   If confirmed as new, it can seed a new cluster.
        *   If it's a variation of an existing unit, it can be used to refine that cluster.
4.  **Codebook Refinement and Stability:**
    *   **Periodic Re-clustering:** Periodically (e.g., weekly or after accumulating a certain amount of new data), re-run the k-means clustering on the *entire accumulated dataset* (initial curated data + all new, reviewed data). This allows the codebook to adapt and new clusters to form naturally from novel data.
    *   **Online Clustering (Longer Term):** For continuous growth, consider online clustering algorithms that can update centroids incrementally without re-processing all data. However, these are more complex.
    *   **Fixed-Size Codebook with Replacement:** To maintain a stable number of units, if a new unit is added, consider merging the two most similar existing units or removing the least frequently used unit. This is a more advanced strategy.
    *   **Nonparametric Methods:** Moving to DPGMM in the longer term inherently handles new unit discovery and optimal K, making the codebook more adaptive.
    *   **Weighting:** When re-clustering, older data could be down-weighted, or a moving window of recent data could be used to ensure the codebook remains relevant to current usage patterns.

By combining a strong initial seed from public datasets with a structured incremental growth and active learning strategy, the "phoneme" library can evolve and become more comprehensive over time, accurately reflecting the acoustic landscape of the collected data.

## 5. Honest Assessment of the NVIDIA Riva "VoxPopuli German Data-Preparation" Tutorial

The NVIDIA Riva "VoxPopuli German Data-Preparation" tutorial focuses on preparing a large-scale, multilingual dataset (VoxPopuli) for Automatic Speech Recognition (ASR) using self-supervised pre-training and supervised fine-tuning. While it deals with audio and self-supervised learning, its direct utility for *this specific project* (unsupervised non-speech unit discovery in Kotlin/JVM) is limited, but it offers valuable conceptual insights.

### 5.1 What Transfers (Conceptual Value)

1.  **Manifest/Data-Preparation Workflow Conventions:** The tutorial demonstrates a structured approach to organizing large audio datasets. It uses manifest files (JSON lines) to link audio paths, metadata (like speaker IDs, durations), and labels (transcripts in their case).
    *   **Transferability:** This convention is highly transferable. For our project, we would create manifest files linking audio paths to segment boundaries, extracted feature vectors, and eventually, the assigned acoustic unit tokens. This ensures data integrity, reproducibility, and efficient batch processing, regardless of the specific ML task. The general principles of data curation, splitting into train/validation/test sets, and managing metadata are universally applicable.
2.  **The Self-Supervised Pre-training on Unlabeled Audio CONCEPT:** The VoxPopuli project, and the tutorial implicitly, embodies the paradigm of self-supervised learning (SSL). It leverages vast amounts of *unlabeled* audio to learn rich, general-purpose acoustic representations (e.g., via wav2vec 2.0 or HuBERT, which are mentioned in our preamble). These representations capture fundamental acoustic properties without needing explicit transcripts.
    *   **Transferability:** This is the *core conceptual alignment* with our project. Our project's goal of unsupervised acoustic unit discovery is a direct application of this SSL paradigm. The idea is to use a pre-trained SSL model (like HuBERT) to generate embeddings for our non-speech audio, which are then clustered to form our "phonemes." The tutorial reinforces the power and methodology behind learning from raw audio.

### 5.2 What Does NOT Transfer (Practical Implementation)

1.  **German ASR Specifics:** The tutorial is entirely focused on preparing data for German ASR. This involves:
    *   **Transcripts:** The primary "label" is the spoken text, which is completely irrelevant for our unsupervised non-speech unit discovery. We are *generating* units, not transcribing speech.
    *   **Language Modeling:** ASR pipelines heavily rely on language models (LMs) specific to the target language (German in this case). This has no direct counterpart in our non-speech tokenization.
    *   **Phoneme/Grapheme Mapping:** ASR requires mapping acoustic units to linguistic phonemes and then to graphemes (text). Our project's "phonemes" are purely acoustic and have no linguistic meaning.
2.  **Supervised Fine-tuning:** The tutorial's ultimate goal is supervised fine-tuning of a pre-trained ASR model using transcribed data.
    *   **Irrelevance:** Our project's initial focus is *unsupervised* discovery. While we might eventually use *labeled sequences* for classification (e.g., "this sequence of tokens means cough"), the process of *creating* the tokens is unsupervised. The fine-tuning steps involving CTC loss, tokenizers, and character error rates are specific to supervised ASR.
3.  **NVIDIA Riva Ecosystem:** The tutorial is deeply embedded within the NVIDIA Riva ecosystem, using specific tools and frameworks optimized for GPU-accelerated deep learning (e.g., NeMo, PyTorch).
    *   **Limited Direct Use:** Our project uses Kotlin/JVM on a desktop, and potentially ONNX Runtime. While the *concept* of using a pre-trained model is relevant, the specific implementation details, scripts, and dependencies from the Riva tutorial are not directly portable to our Kotlin/JVM environment. We would need to find JVM-compatible equivalents for data loading, model inference (e.g., ONNX Runtime for Java), and clustering.

### 5.3 Clear Verdict

The NVIDIA Riva "VoxPopuli German Data-Preparation" tutorial is **not directly useful for the practical implementation details** of our project's unsupervised non-speech unit discovery pipeline in Kotlin/JVM. Its focus on German ASR, transcripts, and the specific Riva/NeMo ecosystem means its code and step-by-step instructions are largely inapplicable.

However, it is **highly valuable for understanding the conceptual underpinnings of self-supervised learning** on unlabeled audio, which is the paradigm our project aims to leverage (e.g., using HuBERT embeddings). It also provides an excellent example of robust data management and manifest file generation, which are general best practices that *should* be adopted.

In summary, it's a good conceptual reference for *why* and *what* self-supervised learning achieves, but not a practical guide for *how* to implement our specific unsupervised non-speech unit discovery in our chosen technology stack. We would need to adapt the *ideas* to our Kotlin/JVM environment and ONNX Runtime.

## 6. Recommended Minimal First Implementation + Longer-Term Path

Given the project constraints (small team, shippable, Android Kotlin recording, Desktop JVM Kotlin analysis, existing 14-dim DSP/MFCC + k-means), a phased approach is critical.

### 6.1 Recommended Minimal First Implementation (Low-Risk Increment)

This phase focuses on leveraging existing components and adding minimal new complexity to achieve sub-clip tokenization and sequence-level analysis.

1.  **Implement DSP Onset Segmentation (Kotlin/JVM):**
    *   **Action:** Develop a simple energy-based and/or spectral flux onset detection algorithm in Kotlin on the JVM.
    *   **Details:** Process the raw audio clips from the Android app. Identify segment start and end points. A common approach is to calculate short-time energy or spectral flux, smooth it, and apply a threshold with a minimum segment duration and inter-segment gap.
    *   **Output:** For each audio clip, generate a list of `(start_time, end_time)` tuples representing the sub-clip segments.
    *   **Reference:** Concepts from Librosa's `onset_detect` can be adapted (e.g., using `librosa.onset.onset_detect` for spectral flux, or simple RMS energy calculations).
2.  **Feature Extraction Per Segment (Kotlin/JVM):**
    *   **Action:** For each identified segment, extract the *same 14-dimensional DSP/MFCC vector* as the existing system.
    *   **Details:** If segments are very short (e.g., <50ms), calculate MFCCs for the entire segment and take the mean of the resulting frames to get a single 14-dim vector. For longer segments, you might take the mean and standard deviation across frames, or simply use a fixed number of frames (e.g., 3 frames concatenated to a 42-dim vector). Start with the mean for simplicity.
    *   **Output:** A list of 14-dim feature vectors, one per segment, for each audio clip.
3.  **Reuse/Retrain K-means for Tokenization (Kotlin/JVM):**
    *   **Action:** Apply the existing k-means clustering logic to these new per-segment feature vectors.
    *   **Details:**
        *   **Option A (Reuse):** If the existing k-means model was trained on *whole-clip* features, it might not be optimal for *segment-level* features. However, as a minimal step, you could try assigning segment features to the existing centroids.
        *   **Option B (Recommended - Retrain):** Collect a representative dataset of *segmented* audio (e.g., 1000 clips, yielding 5000-10000 segments). Retrain the k-means model on these segment-level 14-dim MFCC vectors. This creates a new, segment-aware codebook. Experiment with `K` (e.g., 10-50) using the elbow method.
    *   **Output:** A trained k-means model (cluster centroids) and, for each segment, an assigned cluster ID (e.g., 0, 1, 2... or 'A', 'B', 'C'...).
4.  **Represent Clip as Token Sequence (Kotlin/JVM):**
