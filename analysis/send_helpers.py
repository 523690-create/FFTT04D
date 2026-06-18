#!/usr/bin/env python3
"""Send the same fractionation-agreement assignment to GPT4All (Llama 3 8B) and Ollama (qwen2.5-coder)."""
import json, urllib.request

PROMPT = """You are comparing 7 audio "fractionation" methods. Each cuts a cough recording into
segments at boundary times. We measured how often two methods place a boundary at the same time
(within +/-50 ms), as an F1 score from 0 (never agree) to 1 (identical), averaged over 40 clips.

Mean boundaries per clip (granularity):
  HuBERT K-Means Units      12.3  (finest, a learned neural model = the gold standard)
  Spectral Flux Onset        5.7
  Energy Onset               4.5
  Syllable Nucleus           4.2
  Feature Changepoint        4.0
  Feature Cluster Boundaries 2.3
  Cough Phases               2.3  (coarsest)

Pairwise agreement F1 (symmetric):
                       CoughPh Energy Changept Cluster HuBERT SpecFlux Syllable
  Cough Phases           1.00   0.24    0.21    0.40   0.12    0.14    0.09
  Energy Onset           0.24   1.00    0.49    0.06   0.35    0.63    0.28
  Feature Changepoint    0.21   0.49    1.00    0.06   0.31    0.37    0.36
  Feature Cluster Bound. 0.40   0.06    0.06    1.00   0.11    0.09    0.10
  HuBERT K-Means         0.12   0.35    0.31    0.11   1.00    0.35    0.30
  Spectral Flux Onset    0.14   0.63    0.37    0.09   0.35    1.00    0.46
  Syllable Nucleus       0.09   0.28    0.36    0.10   0.30    0.46    1.00

Agreement WITH HuBERT (the HuBERT row/column): Energy 0.35, Spectral Flux 0.35, Feature Changepoint
0.31, Syllable Nucleus 0.30, Cough Phases 0.12, Feature Cluster Boundaries 0.11.

TASKS (be concise):
1. Which methods agree with EACH OTHER the most? Name the top 2-3 agreeing pairs and the F1.
2. Treating HuBERT as the gold standard, RANK the other 6 methods from MOST to LEAST agreement with
   HuBERT, as a numbered list with the F1 and a one-line reason each (consider granularity).
"""

def ask_ollama(model="qwen2.5-coder:7b"):
    body = json.dumps({"model": model, "prompt": PROMPT, "stream": False,
                       "options": {"temperature": 0.2}}).encode()
    req = urllib.request.Request("http://127.0.0.1:11434/api/generate", body,
                                 {"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=300))["response"]

def ask_gpt4all(model="Llama 3 8B Instruct"):
    body = json.dumps({"model": model, "messages": [{"role": "user", "content": PROMPT}],
                       "temperature": 0.2, "max_tokens": 900}).encode()
    req = urllib.request.Request("http://localhost:4891/v1/chat/completions", body,
                                 {"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=600))["choices"][0]["message"]["content"]

for name, fn in [("OLLAMA qwen2.5-coder:7b", ask_ollama), ("GPT4ALL Llama-3-8B", ask_gpt4all)]:
    print("=" * 70)
    print(name)
    print("=" * 70)
    try:
        print(fn().strip())
    except Exception as e:
        print(f"[ERROR] {type(e).__name__}: {e}")
    print()
