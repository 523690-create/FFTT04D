As a senior audio-DSP/ML engineer, I understand the constraints and goals for your desktop application. The focus on pure-Kotlin DSP, no GPU, and specific audio types (coughs, speech, ambient) guides the selection and description of methods. Here's a catalogue of sound fractionation techniques, designed for implementability and comparative analysis within your tool.

---

## Sound Fractionation Methods Catalogue

### 1. Energy Envelope Onset Detection
-   **NAME:** "Energy Onset"
-   **Principle:** Detects significant increases in audio energy, often indicating the start of a new sound event.
-   **Algorithm Steps (Kotlin):**
    1.  **Frame Blocking:** Divide the audio signal into overlapping frames (e.g., 25ms frame, 10ms hop).
    2.  **Energy Calculation:** For each frame, compute the Root Mean Square (RMS) energy.
        `RMS_i = sqrt( (1/N) * sum(x_j^2) )` where `x_j` are samples in frame `i`, `N` is frame size.
    3.  **Smoothing:** Apply a low-pass filter (e.g., moving average or exponential smoothing) to the RMS envelope to reduce noise and short-term fluctuations.
        `Smoothed_RMS_i = alpha * RMS_i + (1 - alpha) * Smoothed_RMS_{i-1}`
    4.  **Adaptive Thresholding:** Maintain a dynamic threshold based on the recent history of the smoothed RMS. A common approach is to use a percentile (e.g., median) or mean of the past `N_history` frames, multiplied by a sensitivity factor.
        `Threshold_i = Sensitivity_Factor * median(Smoothed_RMS_{i-N_history} ... Smoothed_RMS_{i-1}) + Min_Threshold`
    5.  **Onset Detection with Hysteresis:**
        *   An onset is declared when `Smoothed_RMS_i > Threshold_i` AND `Smoothed_RMS_i > Smoothed_RMS_{i-1}` (rising edge).
        *   To prevent multiple detections for a single event, a "hold-off" period (`Min_Onset_Interval`) is enforced after each detected onset.
        *   A "release" threshold (e.g., `Threshold_i * Hysteresis_Factor`) can be used to mark the end of an event, or simply wait for the energy to drop below the main threshold for a duration.
-   **Key Parameters + Defaults (8-16 kHz mono):**
    *   `Frame_Size_ms`: 25 ms (e.g., 400 samples at 16 kHz)
    *   `Hop_Size_ms`: 10 ms (e.g., 160 samples at 16 kHz)
    *   `Smoothing_Alpha`: 0.8 (for exponential smoothing, higher means less smoothing) or `Moving_Avg_Window_Frames`: 3-5
    *   `Sensitivity_Factor`: 1.5 - 2.5 (multiplies adaptive threshold)
    *   `Min_Threshold_dB`: -60 dBFS (minimum absolute energy to consider)
    *   `N_history_frames`: 100-200 (1-2 seconds for adaptive threshold baseline)
    *   `Min_Onset_Interval_ms`: 100-200 ms (minimum time between detected onsets)
    *   `Hysteresis_Factor`: 0.7 (threshold multiplier for offset detection, if desired)
-   **Output:** A list of `(start_time_ms, end_time_ms)` tuples for each detected sound event. If only onsets are detected, it's just `start_time_ms`.
-   **Pros / Cons / Failure Modes:**
    *   **Pros:** Computationally cheap, robust for percussive or sudden sounds, good for initial coarse segmentation. Pure Kotlin.
    *   **Cons:** Sensitive to background noise level changes. Struggles with sustained sounds, gradual onsets, or overlapping sounds of similar energy. Can miss subtle events.
    *   **Failure Modes:** False positives from sudden noise spikes. False negatives for soft speech or sustained ambient sounds.
    *   **Best-fit:** Explosive cough bursts, typing, car horn (transient, high-energy events). Less ideal for voiced speech or sustained ambient hum.
-   **JVM Feasibility:** Pure-Kotlin DSP.

### 2. Spectral Flux Onset Detection
-   **NAME:** "Spectral Flux Onset"
-   **Principle:** Detects changes in the spectral content of the audio signal, often indicating the start of a new sound or a significant timbral shift.
-   **Algorithm Steps (Kotlin):**
    1.  **Frame Blocking & Windowing:** Same as Energy Onset. Apply a window function (e.g., Hanning) to each frame.
    2.  **FFT & Magnitude Spectrum:** Compute FFT for each windowed frame. Take the magnitude of the complex FFT output to get the magnitude spectrum `M_i(k)` for frame `i` and frequency bin `k`.
    3.  **Spectral Flux Calculation:** Calculate the difference between consecutive magnitude spectra. A common approach is the half-wave rectified Euclidean distance:
        `SF_i = sum_k( max(0, M_i(k) - M_{i-1}(k)) )`
        This emphasizes increases in spectral energy. Normalize by the sum of `M_i(k)` or `M_{i-1}(k)` to make it less sensitive to overall loudness.
    4.  **Smoothing:** Apply a low-pass filter (e.g., moving average) to the `SF` envelope.
    5.  **Adaptive Thresholding & Hysteresis:** Similar to Energy Onset, use an adaptive threshold based on the smoothed `SF` history, and apply hysteresis with a `Min_Onset_Interval`.
    6.  **(Optional) High-Frequency Content Variant:** Instead of summing over all `k`, sum `max(0, M_i(k) - M_{i-1}(k))` only for `k` corresponding to frequencies above a certain cutoff (e.g., 2-4 kHz). This makes it more sensitive to high-frequency transients.
-   **Key Parameters + Defaults (8-16 kHz mono):**
    *   `Frame_Size_ms`: 25 ms
    *   `Hop_Size_ms`: 10 ms
    *   `FFT_Size`: Power of 2, >= `Frame_Size_samples` (e.g., 512 or 1024)
    *   `Smoothing_Window_Frames`: 3-7
    *   `Sensitivity_Factor`: 1.0 - 2.0
    *   `N_history_frames`: 100-200
    *   `Min_Onset_Interval_ms`: 100-200 ms
    *   `High_Freq_Cutoff_Hz`: 2000-4000 Hz (for high-frequency variant)
-   **Output:** List of `(start_time_ms, end_time_ms)` tuples.
-   **Pros / Cons / Failure Modes:**
    *   **Pros:** More sensitive to timbral changes than pure energy. Good for detecting changes even if overall loudness doesn't change much. Pure Kotlin.
    *   **Cons:** Can be sensitive to noise. May struggle with very gradual spectral changes. Phase deviation variants are more complex to implement robustly without specialized libraries.
    *   **Failure Modes:** Can trigger on subtle changes in background noise. May miss events with very little spectral change but significant energy change (e.g., a pure tone suddenly appearing).
    *   **Best-fit:** Explosive cough bursts, speech consonants (plosives, fricatives), certain ambient sounds with distinct spectral signatures (e.g., car horn). Complements energy onset well.
-   **JVM Feasibility:** Pure-Kotlin DSP.

### 3. Syllable Nucleus / Vowel-like Detection
-   **NAME:** "Syllable Nucleus"
-   **Principle:** Identifies regions of high sonority, typically corresponding to vowels or vowel-like sounds, which form the nucleus of syllables.
-   **Algorithm Steps (Kotlin):**
    1.  **Frame Blocking & Feature Extraction:**
        *   **Energy Envelope:** Compute RMS energy per frame (as in method 1).
        *   **Spectral Centroid:** Calculate the spectral centroid for each frame. `SC_i = sum_k(k * M_i(k)) / sum_k(M_i(k))`. Lower SC often indicates more vowel-like sound.
        *   **Zero Crossing Rate (ZCR):** Count zero crossings per frame. Lower ZCR often indicates voiced, vowel-like sound.
    2.  **Sonority/Loudness Envelope Construction:** Combine features to create a "sonority" or "vowel-likelihood" envelope. A simple approach is to smooth the RMS energy and then apply a non-linear compression (e.g., log) to emphasize peaks. Mermelstein's approach uses a loudness envelope that emphasizes peaks and detects dips.
        *   `Loudness_i = log10(RMS_i + epsilon)`
        *   Smooth `Loudness_i` with a longer window (e.g., 50-100 ms).
    3.  **Peak Detection:** Identify local maxima in the smoothed loudness/sonority envelope.
        *   A peak `P_i` is detected if `Loudness_i > Loudness_{i-1}` and `Loudness_i > Loudness_{i+1}`.
    4.  **Thresholding & Refinement:**
        *   Filter peaks based on a minimum amplitude relative to the surrounding minima (e.g., `P_i - min(Loudness_before, Loudness_after) > Min_Prominence`).
        *   Enforce a minimum distance between peaks (`Min_Peak_Interval`) to avoid over-segmentation.
        *   Segment boundaries can be defined by the valleys (local minima) surrounding each detected peak.
-   **Key Parameters + Defaults (8-16 kHz mono):**
    *   `Frame_Size_ms`: 25 ms
    *   `Hop_Size_ms`: 10 ms
    *   `Loudness_Smoothing_Window_ms`: 50-100 ms
    *   `Min_Prominence_dB`: 3-6 dB (minimum peak height relative to valleys)
    *   `Min_Peak_Interval_ms`: 100-250 ms (typical syllable rate)
    *   `Min_Absolute_Loudness_dB`: -50 dBFS (to ignore very quiet segments)
-   **Output:** List of `(start_time_ms, end_time_ms)` tuples, where each segment corresponds to a detected syllable nucleus.
-   **Pros / Cons / Failure Modes:**
    *   **Pros:** Effective for segmenting continuous speech into syllable-like units. Less sensitive to initial transients, focuses on sustained voiced parts. Pure Kotlin.
    *   **Cons:** Poor for non-speech sounds (coughs, ambient noise) that lack clear sonority peaks. Can struggle with very fast speech or whispered speech.
    *   **Failure Modes:** Over-segmentation in sustained vowels, under-segmentation in very fast speech. Misses unvoiced consonants entirely.
    *   **Best-fit:** Voiced speech segments. Can help isolate the "voiced" phase of a cough if present.
-   **JVM Feasibility:** Pure-Kotlin DSP.

### 4. Cough-Phase Segmentation
-   **NAME:** "Cough Phases"
-   **Principle:** Segments a cough into its canonical three phases: inspiratory (pre-cough), expiratory (burst), and voiced/intermediate (post-cough). This is a rule-based approach leveraging specific acoustic features.
-   **Algorithm Steps (Kotlin):**
    1.  **Pre-processing:** Apply a general onset detector (e.g., Energy Onset or Spectral Flux) to roughly identify the start of a potential cough event. This method refines *within* that event.
    2.  **Feature Extraction (per frame):**
        *   **RMS Energy:** For overall loudness.
        *   **Spectral Centroid / High-Frequency Energy:** To detect the initial explosive burst (high SC/HF energy).
        *   **Pitch Detection (e.g., Autocorrelation, YIN):** To detect voiced components.
        *   **Zero Crossing Rate (ZCR):** High ZCR for unvoiced burst, low ZCR for voiced.
    3.  **Phase Identification (State Machine / Rule-based):**
        *   **Inspiratory Phase (Optional, often hard to detect reliably):** Look for a sharp *decrease* in energy followed by a sharp *increase* just before the main burst, or a specific "intake" sound signature (e.g., high ZCR, low energy, short duration). This is often omitted or detected as part of the pre-cough silence.
        *   **Expiratory Burst (P-phase):**
            *   **Detection:** Characterized by a rapid, large increase in RMS energy AND high spectral centroid/high-frequency energy, often accompanied by high ZCR. This is the primary trigger.
            *   **Boundary:** Start is the onset. End is when RMS energy drops significantly (e.g., 6-10 dB from peak) AND/OR spectral centroid drops below a threshold, AND/OR ZCR drops.
        *   **Intermediate/Voiced Phase (I/V-phase):**
            *   **Detection:** Immediately following the burst. Characterized by the presence of detected pitch (voiced), lower ZCR, and sustained (but decaying) RMS energy.
            *   **Boundary:** Start is the end of the burst. End is when pitch detection fails consistently, or RMS energy drops below a background threshold, or ZCR rises significantly (indicating unvoiced decay).
        *   **Decay/Silence:** The remaining part of the initial cough event, often just decaying noise or silence.
    4.  **Refinement:** Apply minimum duration constraints for each phase. Smooth feature envelopes before applying rules.
-   **Key Parameters + Defaults (8-16 kHz mono):**
    *   `Frame_Size_ms`: 25 ms, `Hop_Size_ms`: 10 ms
    *   `Burst_RMS_Threshold_dB_rel`: 8-12 dB (relative to pre-cough baseline)
    *   `Burst_SC_Threshold_Hz`: 3000-5000 Hz (for 16kHz audio)
    *   `Burst_ZCR_Threshold_norm`: 0.4-0.6 (normalized ZCR)
    *   `Voiced_Min_Pitch_Confidence`: 0.7 (from YIN or similar)
    *   `Voiced_Min_Duration_ms`: 50-100 ms
    *   `Min_Burst_Duration_ms`: 30-80 ms
-   **Output:** A list of `(phase_name, start_time_ms, end_time_ms)` tuples for each cough event. E.g., `("Burst", 1000, 1150), ("Voiced", 1150, 1400)`.
-   **Pros / Cons / Failure Modes:**
    *   **Pros:** Provides domain-specific, interpretable segmentation for coughs. Pure Kotlin.
    *   **Cons:** Highly specific to coughs; will not work for other sound types. Requires careful tuning of multiple thresholds. Inspiratory phase is notoriously difficult to reliably detect.
    *   **Failure Modes:** Atypical coughs (e.g., very weak burst, no voiced component) can confuse the rules. Background noise can interfere with feature extraction (pitch, ZCR).
    *   **Best-fit:** Cough recordings, especially for detailed analysis of cough dynamics.
-   **JVM Feasibility:** Pure-Kotlin DSP, requires robust pitch detection (e.g., YIN algorithm).

### 5. HMM / Changepoint Resegmentation
-   **NAME:** "Feature Changepoint"
-   **Principle:** Detects points in a sequence of acoustic features where the underlying statistical properties of the signal change significantly, indicating a segment boundary. We'll focus on a CUSUM-like approach for pure Kotlin.
-   **Algorithm Steps (Kotlin):**
    1.  **Feature Extraction:** Extract a sequence of acoustic feature vectors (e.g., MFCCs, spectral features, energy, ZCR) for each audio frame.
        `F_i = [MFCC1_i, MFCC2_i, ..., Energy_i, ZCR_i, ...]`
    2.  **Feature Difference / Divergence:** Calculate a measure of difference between consecutive feature vectors, or between a short-term average and a long-term average. A simple approach is Euclidean distance or cosine distance between `F_i` and `F_{i-1}`.
        `Diff_i = distance(F_i, F_{i-1})`
        A more robust approach for changepoint detection (like CUSUM) involves tracking the cumulative sum of deviations from an expected mean.
    3.  **CUSUM (Cumulative Sum) Algorithm:**
        *   Initialize `S_pos = 0`, `S_neg = 0`.
        *   For each `Diff_i`:
            *   `S_pos = max(0, S_pos + Diff_i - K_pos)`
            *   `S_neg = max(0, S_neg + (Avg_Diff - Diff_i) - K_neg)` (where `Avg_Diff` is the long-term average of `Diff_i`)
        *   A changepoint is detected when `S_pos > H` or `S_neg > H`. When a changepoint is detected, reset `S_pos` and `S_neg` to 0.
        *   `K_pos`, `K_neg` are drift parameters (e.g., 0.5 * standard deviation of `Diff_i`). `H` is the threshold.
    4.  **Smoothing & Refinement:** Apply a median filter to the `Diff_i` stream before CUSUM. Enforce `Min_Segment_Duration` after a changepoint.
-   **Key Parameters + Defaults (8-16 kHz mono):**
    *   `Frame_Size_ms`: 25 ms, `Hop_Size_ms`: 10 ms
    *   `MFCC_Coefficients`: 12-20 (plus energy, delta/delta-delta if desired)
    *   `CUSUM_K_Factor`: 0.5 (multiplier for standard deviation of difference signal)
    *   `CUSUM_H_Threshold`: 2-5 (number of standard deviations for detection)
    *   `Min_Segment_Duration_ms`: 100-300 ms
    *   `Feature_Smoothing_Window_Frames`: 3-5
-   **Output:** List of `(start_time_ms, end_time_ms)` tuples marking segments with relatively consistent acoustic features.
-   **Pros / Cons / Failure Modes:**
    *   **Pros:** More robust to various sound types as it relies on general feature changes. Can detect subtle changes that energy/flux might miss. Pure Kotlin for CUSUM.
    *   **Cons:** Feature selection is crucial. CUSUM parameters can be tricky to tune. Does not inherently understand "what" the segments are, only that a change occurred. HMM training for segmentation is more complex for pure Kotlin.
    *   **Failure Modes:** Over-segmentation in noisy or highly dynamic signals. Under-segmentation in very slowly changing signals.
    *   **Best-fit:** General purpose segmentation for speech, coughs, and ambient sounds where distinct acoustic states are expected. Good for refining boundaries from coarser methods.
-   **JVM Feasibility:** Pure-Kotlin DSP for feature extraction and CUSUM. Implementing a full HMM training and Viterbi decoding from scratch is a significant undertaking but inference is feasible. CUSUM is simpler.

### 6. Self-supervised Boundary Detection (HuBERT + K-Means)
-   **NAME:** "HuBERT K-Means Units"
-   **Principle:** Leverages powerful pre-trained self-supervised models (like HuBERT) to extract rich, context-aware acoustic representations. These representations are then clustered, and segment boundaries are placed where the assigned cluster changes.
-   **Algorithm Steps (Kotlin + ONNX):**
    1.  **HuBERT Feature Extraction (ONNX):**
        *   Load a pre-trained HuBERT model (e.g., `facebook/hubert-base-ls960`) via ONNX Runtime.
        *   Input raw audio (16 kHz, mono) to the HuBERT model.
        *   Extract the hidden state embeddings (e.g., from the last layer) for each audio frame. These are high-dimensional vectors (e.g., 768-1024 dimensions) and are typically produced at a 20ms frame rate.
        *   **JVM Feasibility:** This step *requires* ONNX Runtime integration (e.g., `onnxruntime-java`).
    2.  **K-Means Clustering (Kotlin):**
        *   Collect all HuBERT feature vectors from the recording.
        *   Apply K-Means clustering to these vectors to group similar acoustic frames into `K` distinct clusters.
        *   Assign a cluster ID to each HuBERT feature vector.
        *   **JVM Feasibility:** Pure-Kotlin implementation of K-Means is straightforward.
    3.  **Boundary Detection:**
        *   Iterate through the sequence of cluster IDs.
        *   A segment boundary is declared whenever the current frame's cluster ID is different from the previous frame's cluster ID.
        *   Apply a `Min_Segment_Duration` to merge very short segments.
-   **Key Parameters + Defaults (8-16 kHz mono):**
    *   `HuBERT_Model_Path`: Path to ONNX model file (e.g., `hubert_base.onnx`)
    *   `K_Means_Clusters_K`: 50-200 (number of acoustic units to discover)
    *   `K_Means_Iterations`: 100-300
    *   `Min_Segment_Duration_ms`: 40-100 ms (HuBERT frame rate is typically 20ms)
-   **Output:** List of `(start_time_ms, end_time_ms, cluster_id)` tuples, where each segment represents a sequence of frames assigned to the same acoustic cluster.
-   **Pros / Cons / Failure Modes:**
    *   **Pros:** Leverages state-of-the-art acoustic representations, leading to highly discriminative and context-aware segmentation. Can discover "phoneme-like" units without explicit supervision. Good for both speech and complex non-speech sounds.
    *   **Cons:** **Requires ONNX Runtime**, which adds a dependency and potentially a larger application footprint. Computationally more expensive due to large model inference. The meaning of `K` clusters is abstract.
    *   **Failure Modes:** `K` value for K-Means is critical; too few or too many can lead to under/over-segmentation. Out-of-distribution sounds might not be well represented.
    *   **Best-fit:** High-precision segmentation for building "cough phoneme" codebooks, speech analysis, and general acoustic unit discovery. Excellent for distinguishing subtle differences.
-   **JVM Feasibility:** **Requires ONNX Runtime for HuBERT inference.** K-Means is pure Kotlin.

### 7. Unsupervised Acoustic Unit Discovery (Simplified Feature-Cluster Boundary)
-   **NAME:** "Feature Cluster Boundaries"
-   **Principle:** A simplified, pure-Kotlin approximation of Unsupervised Acoustic Unit Discovery. It involves extracting low-level features, clustering them to define discrete acoustic states, and then segmenting the audio based on transitions between these states.
-   **Algorithm Steps (Kotlin):**
    1.  **Feature Extraction:** Extract a rich set of frame-level acoustic features. This could include:
        *   MFCCs (e.g., 13-20 coefficients)
        *   Log Energy
        *   Spectral Centroid, Spread, Skewness, Kurtosis
        *   Zero Crossing Rate
        *   Pitch (if present, e.g., F0 and confidence)
        *   Delta and Delta-Delta coefficients for all above (capturing temporal dynamics).
        Normalize features (mean 0, std 1) across the entire recording.
    2.  **Dimensionality Reduction (Optional but Recommended):** Apply PCA to the concatenated feature vectors to reduce dimensionality and decorrelate features, if the feature vector is very large. This helps K-Means.
    3.  **K-Means Clustering:** Apply K-Means clustering to the sequence of feature vectors to assign each frame to one of `K` acoustic clusters. These clusters represent recurring acoustic patterns.
    4.  **Boundary Detection:**
        *   Scan the sequence of cluster assignments.
        *   A segment boundary is placed whenever the cluster ID changes from the previous frame.
        *   Apply a `Min_Segment_Duration` to merge very short segments, and potentially a `Min_Cluster_Homogeneity` (e.g., a segment must contain at least X% of frames from its assigned cluster).
-   **Key Parameters + Defaults (8-16 kHz mono):**
    *   `Frame_Size_ms`: 25 ms, `Hop_Size_ms`: 10 ms
    *   `Feature_Set`: MFCCs (13-20), Energy, ZCR, Spectral Centroid/Spread. Add Delta/Delta-Delta.
    *   `PCA_Components`: 10-30 (if PCA is used)
    *   `K_Means_Clusters_K`: 50-150 (number of acoustic units)
    *   `K_Means_Iterations`: 100-200
    *   `Min_Segment_Duration_ms`: 50-150 ms
-   **Output:** List of `(start_time_ms, end_time_ms, cluster_id)` tuples. Similar to HuBERT K-Means, but the `cluster_id` is derived from simpler features.
-   **Pros / Cons / Failure Modes:**
    *   **Pros:** Pure Kotlin, no external ML models needed. Can discover recurring acoustic patterns and segment based on them. More general than rule-based methods.
    *   **Cons:** Feature engineering is critical. The "units" are purely acoustic and may not align perfectly with linguistic or perceptual units. Less powerful than HuBERT-based methods due to simpler features and lack of self-supervised pre-training.
    *   **Failure Modes:** `K` value is crucial. Can be sensitive to noise if features are not robust. May over-segment or under-segment depending on `K` and `Min_Segment_Duration`.
    *   **Best-fit:** When a pure-Kotlin, unsupervised, data-driven segmentation is desired for general acoustic unit discovery, but HuBERT/ONNX is not an option. Good for building a basic "acoustic state" codebook.
-   **JVM Feasibility:** Pure-Kotlin DSP for feature extraction, PCA, and K-Means.

---

## Recommended Workflows

These workflows combine the above methods to achieve specific goals, guiding the user through a sequence of "button presses."

### 1. "Cough-Only Fast Triage"
*   **Goal:** Quickly identify and segment the main cough events in a recording, focusing on the burst.
*   **Buttons to Press & Order:**
    1.  **"Energy Onset"**: Apply with `Sensitivity_Factor` 2.0, `Min_Onset_Interval_ms` 200.
        *   *Why:* This provides a fast, initial pass to catch the most prominent sound events, which are likely coughs or other loud transients. It's a good starting point for filtering out long silences.
    2.  **"Cough Phases"**: Apply this *within* each segment identified by "Energy Onset".
        *   *Why:* This refines the segments, specifically targeting the characteristic burst phase of a cough. It helps distinguish true coughs from other loud noises by looking for