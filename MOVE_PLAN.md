# Workspace move + consolidation plan (D: → NVMe G:)

Status: **Part 1 (data consolidation) DONE 2026-06-18** — `Workspace` refactor committed; C: data
migrated to `D:\AndroidProjects\data\`. **Part 2 (physical move) pending** the user's Disk-Management
step. D: decrypt is finished. Chosen variant: **volume mount point** (data at G: root, mount G: at
`D:\AndroidProjects`, drop the G: letter) — no dependency on the G: drive letter.

## Why
The current workspace lives on `D:\AndroidProjects`, a drive that was crashing to BitLocker
recovery (now decrypting). A new 1 TB NVMe SSD (currently `G:`) is faster and healthy. Goal: move
the workspace to the NVMe **with minimal path pain** and consolidate the scattered runtime data into
one root.

Lessons from the H:→D: move (what made it painful): hardcoded absolute paths that didn't move with
the files, *and* a folder-structure change (`H:\FFTT04*` → `D:\AndroidProjects\FFTT04*`). This plan
avoids both.

## Strategy: Option C — directory junction (chosen)
Keep the `D:\AndroidProjects\…` paths working but serve them from the NVMe via a **junction**, so
only that one directory redirects and the rest of `D:` (and every other app expecting data on `D:`)
is untouched.

```
D:\AndroidProjects\   ──junction──►  G:\AndroidProjects\   (physically on the NVMe)
  ├─ FFTT04D\  FFTT04M\  FFTT04L\     (repos)
  ├─ ALLDATA\  true_cough\           (datasets)
  └─ data\                            (consolidated app data — see Part 1)
       ├─ fractionation\
       ├─ fractionation_validation\
       ├─ usb_import\
       └─ manual_comments.json
```

Why C over the alternatives:
- **A (reletter NVMe to `D:`)** — zero edits, but kills the old `D:` other apps rely on. Rejected.
- **B (use `G:` + find/replace `D:`→`G:`)** — safe, but needs path edits in 3 repos + venv rebuild.
- **C (junction)** — no path edits, `.venv` survives, rest of `D:` untouched. Only caveat: the
  junction targets the `G:` **letter**, so don't reassign `G:` later (it's an internal NVMe → fine).
  If you want zero letter-dependency, use a **volume mount point** instead (mount the NVMe volume at
  the empty folder `D:\AndroidProjects` and drop the `G:` letter) — same transparency, binds to the
  volume not the letter.

---

## Part 1 — Consolidate app data (code refactor; do FIRST, while D: is stable)
Small (~35 MB) runtime data currently lives under `C:\Users\belil`. Fold it into `…\AndroidProjects\data\`.

**Data sites to repoint (the functional `user.home` ones):**
| File | Line(s) | Current | New |
|---|---|---|---|
| Main.kt | 263, 520, 578, 709 | `~/FFTT04M_usb_import` | `Workspace.dir("usb_import")` |
| Main.kt | 1199 | `~/FFTT04M_fractionation` | `Workspace.dir("fractionation")` |
| FractionateCli.kt | 52 | `~/FFTT04M_fractionation_validation` | `Workspace.dir("fractionation_validation")` |
| RecordingsGrid.kt | 32 | `~/FFTT04M/manual_comments.json` | `Workspace.file("manual_comments.json")` |

Leave alone: the file-picker default folders (`user.home`/`Documents` at Main.kt 829/847/944/983/1046)
— those are dialog defaults, not storage.

**New `Workspace` object** (resolve order, fail-safe):
1. env `FFTT04_DATA` if set;
2. else discover the `AndroidProjects` dir by walking up from the jar/cwd (same up-walk
   `GpuFft`/`HubertKMeansUnits` use) and append `\data`;
3. else fall back to `user.home` (so nothing breaks if discovery fails).

**One-time auto-migration** on startup: if an old `~/FFTT04M_<x>` exists and the new `data\<x>` does
not, move it. ~35 MB, runs once. (Or migrate manually with robocopy before first run.)

Verify: build, launch, confirm fractionation output + manual comments land in `…\data\` and old runs
are still found.

---

## Part 2 — The physical move (junction; do AFTER Part 1 is committed + verified)

**Pre-flight**
1. Confirm D: decrypt finished; no BitLocker-recovery crashes for a while.
2. Confirm `G:` healthy with ≥ ~800 GB free (D: had ~772 GB used).
3. Re-run the path inventory on FFTT04M and FFTT04L (same grep as below) — no surprises.
4. Close the app, stop Gradle daemons (`gradlew --stop`), close anything holding files open.

**Copy (resumable — the anti-pain part).** For a volume mount point the data must sit at the **G:
root** (the mounted folder shows the volume root):
```
robocopy D:\AndroidProjects G:\ /MIR /MT:16 /R:1 /W:1 /Z /NP /XF G:\MOVE_PLAN.md /LOG:C:\move.log
```
- `/MIR` mirror (re-runnable to catch deltas), `/Z` restartable for interrupted files. Log on C: so
  it isn't on either end of the copy. `/XF` keeps the standalone plan copy from being deleted by /MIR.
- **Keep `.venv` and `native\`** (do NOT exclude) — `D:\AndroidProjects\…` survives the mount, so the
  venv and model/DLLs work as-is. (Also includes the new `data\`.)
- Re-run robocopy → a complete copy reports 0 files copied (your verification).

**Switch to the volume mount point** (Disk Management, or elevated cmd — needs admin + a reboot):
```
rename D:\AndroidProjects AndroidProjects_old      :: keep the original as backup
mkdir  D:\AndroidProjects                          :: empty target for the mount
:: Disk Management → right-click the NVMe volume → Change Drive Letter and Paths →
::   Add… → "Mount in the following empty NTFS folder" → D:\AndroidProjects
::   then Remove the G: drive letter (optional; binds to the volume, not a letter)
:: CLI equivalent: mountvol D:\AndroidProjects \\?\Volume{GUID}\   (get GUID via `mountvol`)
```
Reboot, then verify before deleting `AndroidProjects_old`.

**Verify before deleting `_old`**
- `cd D:\AndroidProjects\FFTT04D && gradlew :desktop:compileKotlin`
- `gradlew :desktop:fractionateCli -PuseOnnxGpu --args="D:\AndroidProjects\true_cough D:\AndroidProjects\data\_movecheck"`
  → expect `execution provider: CUDA`, output written.
- Launch the app; confirm recordings/grid work and data lands in `…\data\`.
- Confirm `~/.gradle` (C:) untouched and the GitHub remotes still resolve.
- Only then: `rmdir /S D:\AndroidProjects_old`.

---

## Reference: hardcoded `D:` paths (only matter if you ever pick Option B)
- `FFTT04D\.claude\settings.json` + `D:\AndroidProjects\.claude\settings.json`:
  `additionalDirectories` ×3 and the `java -jar D:/…CoughAnalyzer.jar` allow-rule.
- `Main.kt:980` — `File("D:/AndroidProjects/FFTT04M/app/src/main/assets")` (cross-repo write).
- `Main.kt:114` — ALLDATA picker default (first-run only; remembered after).
- `FractionateCli.kt:51` — CLI default input folder.
- Cosmetic: `build.gradle.kts` comment, `FractionateCli.kt` header, `HANDOFF.md`.
- Re-grep before moving: `rg -n "D:[\\/]+AndroidProjects" --glob '!**/build/**'`

Under Option C none of these need touching.

## Stays on C: (do NOT move)
- `~/.gradle` cache — shared across all Gradle projects, large, not project-specific.
- GPT4All (`C:\Users\belil\gpt4all`) + Ollama installs/models — separate apps. See `local_ai_offload`.

## Disaster recovery (the real continuity anchor)
All three repos are pushed to GitHub (`523690-create/FFTT04{D,M,L}`). Total disk loss recovery:
1. `git clone` the three repos into `G:\AndroidProjects\`.
2. Regenerate the gitignored, NOT-in-git binaries:
   - HuBERT model: `cd FFTT04D\desktop\native\hubert && python export_hubert_onnx.py` (see its README).
   - cuDNN 9 + cuBLAS 12 DLLs: redownload from NVIDIA redist (URLs in `native/hubert/README.md`).
   - `.venv`: `python -m venv .venv` + `pip install torch transformers onnx onnxscript` (CPU index).
   - jars: `gradlew :desktop:fatJar` (add `-PuseOnnxGpu` for the CUDA build).
3. Re-import datasets (ALLDATA via Build ALLDATA; `true_cough` is curated input).
4. The only non-recoverable bit is the ~35 MB of runtime `data\` (fractionation output + manual
   comments) — back that up separately if it matters; analysis output is reproducible.
