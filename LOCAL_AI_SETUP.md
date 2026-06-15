# Local AI connections — how to (re)establish them

Authoritative notes for reconnecting the three local/cloud assistants used to offload and
cross-check subtasks. Written 2026-06-15. Machine: this Windows 11 box. **All delegated output must
be reviewed** — these are drafters, not committers (especially the 4B/8B local models).

Quality seen on a structured task: **Gemini 2.5 Flash ≈ qwen2.5-coder:7b (correct) > Llama-3-8B
(minor errors) > qwen3:4b (unusable)**. Each model has produced confidently-wrong output at least
once — never ship unreviewed.

---

## 1. Ollama (local) — updated to 0.30.8

- **Binary:** `C:\Users\belil\AppData\Local\Programs\Ollama\ollama.exe` · tray app: `ollama app.exe`
  (same folder). The old 0.24.0 tray crash-looped (`exit status 1`); **0.30.8 fixed it**.
- **Start:** launch the tray (`ollama app.exe`) or run `ollama serve`. Verify:
  `curl http://127.0.0.1:11434/api/tags`
- **API:** `http://127.0.0.1:11434` — generate (non-stream):
  ```bash
  curl -s http://127.0.0.1:11434/api/generate \
    -d '{"model":"qwen2.5-coder:7b","stream":false,"prompt":"...","options":{"temperature":0.2}}'
  # response is JSON; read .response
  ```
- **Models present:** `qwen2.5-coder:7b` (good for code/instructions), `qwen3:4b` (weak; even with
  `"think":false` it leaks reasoning — avoid for anything but trivial drafts). Add more:
  `ollama pull qwen2.5-coder:7b` (RTX 4060 Ti / 8 GB handles 7–8B comfortably).
- **Installer gotcha (if updating again):** `OllamaSetup.exe` is an Inno **stub** that extracts to
  `OllamaSetup.tmp` and **detaches** — the launching command returns "exit 0" long before install
  finishes. Wait until no `OllamaSetup*` process remains, and ensure **no `ollama.exe` is running
  during the copy** (a running instance locks the binary → silent no-op / reboot-deferred).

## 2. GPT4All (local) — needs the app open

- **Exe:** `C:\Users\belil\gpt4all\bin\chat.exe`
- **Local API server** is enabled (`serverChat=true` in `%APPDATA%\nomic.ai\GPT4All.ini`) but only
  runs **while the GUI app is open**. Launch `chat.exe`, then verify:
  `curl http://localhost:4891/v1/models`
- **API:** OpenAI-compatible at `http://localhost:4891/v1` →
  ```bash
  curl -s http://localhost:4891/v1/chat/completions \
    -d '{"model":"Llama 3 8B Instruct","stream":false,"messages":[{"role":"user","content":"..."}]}'
  ```
- **Models:** general instruct → **"Llama 3 8B Instruct"** (also a "Reasoner"). GGUFs live in
  `D:\models\` (most are creative-writing fine-tunes; `llama-3.1-8b-instruct` is the general one).

## 3. Gemini (cloud) — best quality; use the REST API, NOT the CLI

- **API key:** in the **User** environment as `GEMINI_API_KEY` (set once via
  `setx GEMINI_API_KEY "…"`). NOTE `setx` only affects **new** processes — a shell started earlier
  won't see it. Read it without printing:
  ```bash
  KEY=$(powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('GEMINI_API_KEY','User')" | tr -d '\r')
  ```
- **Use REST, not the `gemini` CLI.** The CLI (`C:\Users\belil\AppData\Roaming\npm\gemini.cmd`,
  v0.46, from `npm i -g @google/gemini-cli`) is **agentic**: run in a project folder it reads the
  repo's files and overrides your prompt with their contents. For clean prompt→response:
  ```bash
  curl -s "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$KEY" \
    -H "Content-Type: application/json" \
    -d '{"contents":[{"parts":[{"text":"..."}]}]}'
  # read .candidates[0].content.parts[0].text   (python urllib is reliable for this)
  ```
- **Limits:** free tier = **5 requests/min**; transient **503** ("high demand") under load → retry
  after ~20–35 s. If you must use the CLI headless: `--skip-trust` + `GEMINI_CLI_TRUST_WORKSPACE=true`.

---

## Quick health check (all three)
```bash
curl -s http://127.0.0.1:11434/api/tags        | head -c 120   # Ollama (start tray if empty)
curl -s http://localhost:4891/v1/models        | head -c 120   # GPT4All (start chat.exe if empty)
KEY=$(powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('GEMINI_API_KEY','User')" | tr -d '\r'); echo "gemini key len: ${#KEY}"
```

## When to use which
- **Substantive subtask I can verify cheaply** (draft docs/release notes, summarize long logs,
  generate many similar stubs, second-opinion on a fix): **Gemini Flash (REST)** or **qwen2.5-coder:7b**.
- **Cross-check / triangulate** a tricky answer: ask two, compare, verify against ground truth
  (this caught a flipped API-level claim from qwen and an agentic-hijack from the Gemini CLI).
- **Never** delegate code edits or repo-wide reasoning — Claude keeps those.
