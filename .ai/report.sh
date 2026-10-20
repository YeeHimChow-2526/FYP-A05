#!/usr/bin/env bash
# 泊車副駕 v2.9.1 — Pi 回合報告產生器
# 用法：.ai/report.sh "<測試指令>"
#       REPORT_BASE=<commit> .ai/report.sh "<測試指令>"   # 手動指定由邊個 commit 之後開始報
# 輸出：.ai/LAST_RUN.md（每次覆寫），交 Gemini 審查。
# 差異範圍：上次報告之後嘅所有 commit（記錄喺 .ai/.last_report_head，唔入庫），
#           所以一個回合提交幾多次都唔會漏；首次或記錄失效時用 HEAD~1。
set -u

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "report.sh：唔喺 git repo 入面" >&2; exit 2; }
cd "$ROOT" || exit 2
mkdir -p .ai
OUT=".ai/LAST_RUN.md"
DIFF_CAP=150
FENCE='````'  # 四個反引號：內容（例如 .md diff）含 ``` 時都唔會斷開

EXCLUDES=(
  ':(exclude,glob)**/*.lock'
  ':(exclude,glob)**/pubspec.lock'
  ':(exclude,glob)**/build/**'
  ':(exclude,glob)**/.dart_tool/**'
  ':(exclude,glob)**/.gradle/**'
  ':(exclude,glob)android/.gradle/**'
  ':(exclude,glob)**/*.g.dart'
  ':(exclude,glob)**/*.freezed.dart'
  ':(exclude,glob)**/assets/*.png'
  ':(exclude,glob)**/assets/*.jpg'
  ':(exclude).ai/LAST_RUN.md'
)

TEST_CMD="${1:-}"
NOW="$(TZ=Asia/Hong_Kong date '+%Y-%m-%d %H:%M:%S HKT')"
BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '(未有 commit)')"
LAST_COMMIT="$(git log -1 --oneline 2>/dev/null || echo '(未有 commit)')"

TEST_LOG="$(mktemp)"
trap 'rm -f "$TEST_LOG"' EXIT
if [ -n "$TEST_CMD" ]; then
  bash -c "$TEST_CMD" >"$TEST_LOG" 2>&1
  EXIT_CODE=$?
  if [ "$EXIT_CODE" -eq 0 ]; then STATUS="SUCCESS"; else STATUS="FAILED"; fi
else
  EXIT_CODE="-"
  STATUS="未提供測試指令"
fi

# 已提交差異（上次報告之後所有 commit）＋未提交差異
BASE_FILE=".ai/.last_report_head"
COMMIT_LIST=""
if git rev-parse --verify -q HEAD >/dev/null; then
  BASE="${REPORT_BASE:-}"
  BASE_SRC="REPORT_BASE"
  if [ -z "$BASE" ] && [ -f "$BASE_FILE" ]; then BASE="$(tr -d ' \r\n' <"$BASE_FILE")"; BASE_SRC="上次報告"; fi
  # 記錄要係 HEAD 嘅祖先而且唔等於 HEAD，否則退返 HEAD~1
  if [ -n "$BASE" ]; then
    BASE_HASH="$(git rev-parse --verify -q "${BASE}^{commit}" 2>/dev/null || true)"
    if [ -z "$BASE_HASH" ] || [ "$BASE_HASH" = "$(git rev-parse HEAD)" ] || ! git merge-base --is-ancestor "$BASE_HASH" HEAD 2>/dev/null; then
      BASE=""
    else
      BASE="$BASE_HASH"
    fi
  fi
  if [ -z "$BASE" ] && git rev-parse --verify -q HEAD~1 >/dev/null; then BASE="$(git rev-parse HEAD~1)"; BASE_SRC="HEAD~1"; fi
  if [ -n "$BASE" ]; then
    SHORT_BASE="$(git rev-parse --short "$BASE")"
    COMMITTED_DIFF="$(git diff "$BASE" HEAD -- . "${EXCLUDES[@]}")"
    COMMITTED_STAT="$(git diff --stat "$BASE" HEAD -- . "${EXCLUDES[@]}")"
    COMMITTED_LABEL="git diff $SHORT_BASE HEAD（起點：$BASE_SRC）"
    COMMIT_LIST="$(git log --oneline "$BASE"..HEAD)"
  else
    COMMITTED_DIFF="$(git show --stat --format='%h %s' HEAD -- . "${EXCLUDES[@]}")"
    COMMITTED_STAT="$COMMITTED_DIFF"
    COMMITTED_LABEL="git show --stat HEAD（首個 commit）"
    COMMIT_LIST="$(git log --oneline -1)"
  fi
  UNCOMMITTED_DIFF="$(git diff HEAD -- . "${EXCLUDES[@]}")"
  UNCOMMITTED_STAT="$(git diff --stat HEAD -- . "${EXCLUDES[@]}")"
else
  COMMITTED_DIFF=""; COMMITTED_STAT=""; COMMITTED_LABEL="(未有 commit)"
  UNCOMMITTED_DIFF=""; UNCOMMITTED_STAT=""
fi

count_lines() { if [ -z "$1" ]; then echo 0; else printf '%s\n' "$1" | wc -l | tr -d ' '; fi; }
TOTAL_DIFF_LINES=$(( $(count_lines "$COMMITTED_DIFF") + $(count_lines "$UNCOMMITTED_DIFF") ))

{
  echo "# LAST_RUN — Pi 回合報告"
  echo
  echo "| 項目 | 內容 |"
  echo "|---|---|"
  echo "| 時間 | $NOW |"
  echo "| 分支 | \`$BRANCH\` |"
  echo "| 最後 commit | \`$LAST_COMMIT\` |"
  echo "| 測試指令 | \`${TEST_CMD:-未提供測試指令}\` |"
  echo "| Exit code | $EXIT_CODE |"
  echo "| 狀態 | **$STATUS** |"
  echo
  if [ "$STATUS" = "FAILED" ]; then
    echo "## 測試輸出（最後 20 行）"
    echo
    echo "$FENCE"
    if [ -s "$TEST_LOG" ]; then tail -n 20 "$TEST_LOG"; else echo "(無輸出)"; fi
    echo "$FENCE"
    echo
  fi
  echo "## 本回合 commit"
  echo
  echo "$FENCE"
  if [ -n "$COMMIT_LIST" ]; then printf '%s\n' "$COMMIT_LIST"; else echo "(無)"; fi
  echo "$FENCE"
  echo
  echo "## git status --short"
  echo
  echo "$FENCE"
  STATUS_OUT="$(git status --short --untracked-files=all)"
  if [ -n "$STATUS_OUT" ]; then printf '%s\n' "$STATUS_OUT"; else echo "(工作區乾淨)"; fi
  echo "$FENCE"
  echo
  echo "## 差異"
  echo
  if [ "$TOTAL_DIFF_LINES" -le "$DIFF_CAP" ]; then
    echo "### 已提交：$COMMITTED_LABEL"
    echo
    echo "${FENCE}diff"
    if [ -n "$COMMITTED_DIFF" ]; then printf '%s\n' "$COMMITTED_DIFF"; else echo "(無)"; fi
    echo "$FENCE"
    echo
    echo "### 未提交：git diff HEAD"
    echo
    echo "${FENCE}diff"
    if [ -n "$UNCOMMITTED_DIFF" ]; then printf '%s\n' "$UNCOMMITTED_DIFF"; else echo "(無)"; fi
    echo "$FENCE"
  else
    echo "> 差異共 $TOTAL_DIFF_LINES 行，超過 $DIFF_CAP 行上限，只列 \`git diff --stat\`；需要細節時請 Gemini 指定檔案。"
    echo
    echo "### 已提交：$COMMITTED_LABEL（--stat）"
    echo
    echo "$FENCE"
    if [ -n "$COMMITTED_STAT" ]; then printf '%s\n' "$COMMITTED_STAT"; else echo "(無)"; fi
    echo "$FENCE"
    echo
    echo "### 未提交：git diff --stat HEAD"
    echo
    echo "$FENCE"
    if [ -n "$UNCOMMITTED_STAT" ]; then printf '%s\n' "$UNCOMMITTED_STAT"; else echo "(無)"; fi
    echo "$FENCE"
  fi
} >"$OUT"

# 記低今次報到邊度，下次由呢度開始
git rev-parse --verify -q HEAD >"$BASE_FILE" 2>/dev/null || true

echo "已生成 $OUT（$STATUS）"
if [ "$EXIT_CODE" = "-" ]; then exit 0; else exit "$EXIT_CODE"; fi
