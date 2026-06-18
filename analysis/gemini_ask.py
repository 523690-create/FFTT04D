#!/usr/bin/env python3
"""Ask Gemini 2.5 Flash (REST) for Kotlin/Swing code suggestions on two desktop-UI features.
Key comes from the GEMINI_API_KEY env var (set by the PowerShell caller). Retries on 503."""
import json, os, time, urllib.request, urllib.error

KEY = os.environ["GEMINI_API_KEY"]
URL = ("https://generativelanguage.googleapis.com/v1beta/models/"
       "gemini-2.5-flash:generateContent?key=" + KEY)

PROMPT = """You are advising on a Kotlin + Java Swing DESKTOP app (single JFrame `AnalyzerWindow`)
that analyzes cough/audio recordings. Relevant existing pieces:
- `recordingsList: JList<String>` currently lists loaded recordings (model class `AudioRecording`
  with fields: id, audioFile: File, sampleRate: Int, plus analysis comments available).
- `SpectrogramRenderer.renderFftPng(pcm: FloatArray, sr: Int, out: File)` renders an FFT spectrogram
  PNG. `AudioDecoder.decode(file): FloatArray?` decodes a wav to mono PCM.
- `MfccExtractor` can produce per-frame MFCC coefficient arrays.
- Existing analysis can produce each clip's top-3 best-match labels.

Give CONCISE, idiomatic Kotlin/Swing code suggestions (snippets + the key APIs/classes), not essays,
for these three features:

(1) Move the recordings panel to the LEFT side of the window in a collapsible/expandable region that
can ALSO pop out ("break out") into its own resizable JFrame and dock back. What layout
(JSplitPane?), and the cleanest pattern to move the same component between the main window and a
detached frame without rebuilding it.

(2) A grid/gallery view of recordings (toggle between this grid and the current list). Each cell:
the clip's FFT spectrogram thumbnail (call SpectrogramRenderer, cache the PNG, recompute only if
missing); LEFT-CLICK plays the .wav (javax.sound.sampled); RIGHT-CLICK shows a JPopupMenu with
Delete / Move / Edit comments; a checkbox/checkmark at the corner for multi-selection; and an
auto-generated caption line showing the top-3 best matches. Suggest the component structure
(JList with custom ListCellRenderer + fixed-size cells in a wrap layout, vs a JPanel of cells in a
GridLayout/WrapLayout inside a JScrollPane), how to do efficient thumbnail caching, multi-select
with checkboxes, and wiring left/right click.

(3) Render an MFCC heatmap natively in Kotlin/Swing (a librosa-specshow equivalent): given a 2D
array [frames][coeffs] from MfccExtractor, draw a BufferedImage heatmap (time on X, coefficient on
Y, color = magnitude with a diverging colormap) and show it in a JLabel/JComponent. Include the
normalization and color-mapping approach.

Keep each section short and code-first."""


def main():
    body = json.dumps({"contents": [{"parts": [{"text": PROMPT}]}],
                       "generationConfig": {"maxOutputTokens": 8192, "temperature": 0.3}}).encode()
    for attempt in range(5):
        try:
            req = urllib.request.Request(URL, body, {"Content-Type": "application/json"})
            resp = json.load(urllib.request.urlopen(req, timeout=120))
            cand = resp["candidates"][0]
            parts = cand.get("content", {}).get("parts", [])
            print("".join(p.get("text", "") for p in parts))
            print(f"\n[finishReason: {cand.get('finishReason')}]")
            return
        except urllib.error.HTTPError as e:
            if e.code == 503 and attempt < 4:
                time.sleep(3 * (attempt + 1)); continue
            print(f"[HTTPError {e.code}] {e.read().decode()[:300]}"); return
        except Exception as e:
            print(f"[ERROR] {type(e).__name__}: {e}"); return


if __name__ == "__main__":
    main()
