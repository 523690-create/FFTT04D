@echo off
REM ============================================================================
REM  Workspace backup: live NVMe  ->  DATA-drive copy.   LIVE -> BACKUP only.
REM  Incremental: updates changed files + adds new ones. Never deletes (/E, not /MIR),
REM  so a broken mount or an accidental delete can't wipe the backup.
REM  Regenerables (.venv / build / .gradle) are skipped — rebuild on restore.
REM ============================================================================
set "SRC=D:\AndroidProjects"
set "DST=D:\AndroidProjects_old"
set "LOG=C:\Users\belil\workspace_backup.log"

REM Safety: abort unless the live workspace looks real (guards against an inactive mount).
if not exist "%SRC%\FFTT04D\settings.gradle.kts" (
  echo [ABORT] "%SRC%" does not look like the live workspace ^(mount inactive?^). Backup skipped.
  exit /b 1
)

echo Backing up "%SRC%"  ->  "%DST%"  (incremental, no-delete)...
robocopy "%SRC%" "%DST%" /E /MT:16 /R:1 /W:1 /XD .venv build .gradle "System Volume Information" "$RECYCLE.BIN" /XJ /NP /NDL /NFL /LOG:"%LOG%"
set RC=%ERRORLEVEL%
echo Done. robocopy code %RC%  (0-7 = success, 8+ = error).  Full log: "%LOG%"
exit /b 0
