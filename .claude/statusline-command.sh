#!/usr/bin/env bash
# Claude Code status line script
# Displays: cwd | git branch | model name | context usage | token count

input=$(cat)

# --- Current working directory ---
cwd=$(echo "$input" | jq -r '.cwd // .workspace.current_dir // empty')
# Shorten home directory to ~
home="$HOME"
short_cwd="${cwd/#$home/~}"

# --- Git branch ---
git_branch=$(GIT_DIR="$cwd/.git" git --git-dir="$cwd/.git" rev-parse --abbrev-ref HEAD 2>/dev/null)
if [ -z "$git_branch" ]; then
  git_branch=$(cd "$cwd" 2>/dev/null && git rev-parse --abbrev-ref HEAD 2>/dev/null)
fi

# --- Model display name ---
model=$(echo "$input" | jq -r '.model.display_name // .model.id // "Claude"')

# --- Context window usage ---
used_pct=$(echo "$input" | jq -r '.context_window.used_percentage // empty')

# --- Token count (total input tokens in context, formatted as e.g. 12.3k) ---
total_tokens=$(echo "$input" | jq -r '.context_window.total_input_tokens // empty')

# --- Assemble the status line with ANSI colors ---
#   Cyan    = \033[36m  for path
#   Green   = \033[32m  for git branch
#   Blue    = \033[34m  for model
#   Yellow  = \033[33m  for context
#   Magenta = \033[35m  for token count
#   Reset   = \033[0m

printf "\033[36m%s\033[0m" "$short_cwd"

if [ -n "$git_branch" ]; then
  printf " \033[32m(%s)\033[0m" "$git_branch"
fi

printf " \033[34m[%s]\033[0m" "$model"

if [ -n "$used_pct" ]; then
  printf " \033[33mctx:%.0f%%\033[0m" "$used_pct"
fi

if [ -n "$total_tokens" ] && [ "$total_tokens" != "0" ]; then
  tok_k=$(echo "$total_tokens" | awk '{printf "%.1fk", $1/1000}')
  printf " \033[35m%s tok\033[0m" "$tok_k"
fi
