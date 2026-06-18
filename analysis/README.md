# Session analysis tooling (2026-06-18)

Scripts from the fractionation method-agreement study + local-AI offload.

- `agreement.py` — boundary-agreement F1 across the 7 fractionation methods (HuBERT as the
  reference). Edit `SRC` for the input dir; `TOL_MS` is the boundary match tolerance.
- `send_helpers.py` — sends the same agreement assignment to GPT4All (`:4891`) and Ollama (`:11434`).
- `gemini_ask.py` — Gemini REST UI code-suggestion query (needs `GEMINI_API_KEY` in env).
- `agreement_out.txt`, `helper_responses.txt`, `gemini_out.txt` — captured outputs (the findings).

Findings recap: with HuBERT as gold standard, agreement order was Spectral Flux ≈ Energy Onset >
Feature Changepoint ≈ Syllable Nucleus > Cough Phases > Feature Clusters. GPT4All (Llama-3-8B) ranked
it correctly; Ollama (qwen2.5-coder) mis-sorted.

Note: scripts hardcode the home `FFTT04M_fractionation` data dir; adjust after the workspace move /
data consolidation (see `../MOVE_PLAN.md`).
