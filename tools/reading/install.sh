#!/usr/bin/env bash
# Link nvcat, ccview and rd into $PREFIX/bin (Termux) or ~/.local/bin, pointing back at this
# checkout so a `git pull` updates them. Run again after moving the checkout.
set -euo pipefail

here=$(dirname "$(readlink -f "$0")")
if [ -n "${PREFIX:-}" ] && [ -d "$PREFIX/bin" ]; then bin=$PREFIX/bin; else bin=$HOME/.local/bin; fi
mkdir -p "$bin"
for tool in nvcat ccview rd; do
  chmod +x "$here/$tool"
  ln -sf "$here/$tool" "$bin/$tool"
  echo "linked $bin/$tool"
done

command -v nvim >/dev/null || echo "note: nvcat needs nvim (pkg install neovim)"
python3 -c 'import pygments' 2>/dev/null || echo "optional: pip install pygments, for highlighted code in ccview"
command -v glow >/dev/null || echo "optional: pkg install glow, for markdown in rd"
