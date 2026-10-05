# Reading tools for the plain terminal

These print things into the plain shell's scrollback instead of opening them in a full-screen
app. The output then scrolls with Termux's own pixel scrolling, which is smoother than any app's
scrolling. Nothing here is editable; it is for reading.

| Command | What it prints |
|---|---|
| `nvcat FILE` | A file with **nvim's own highlighting**: your config, colourscheme and treesitter parsers (with injections, e.g. code blocks inside markdown). Falls back to `:syntax` for filetypes without a parser. `-n` adds line numbers, `--bg` paints the colourscheme's background, `--clean` skips your config (faster, default colours), `-l FT` sets the filetype (needed for stdin: `git diff \| nvcat -l diff`). |
| `ccview` | A **Claude Code transcript**, with what `/export` leaves out: thinking, tool calls (Bash commands highlighted, edits as red/green diffs, written files highlighted) and tool output (30 lines each; `-n 100` or `--full` for more). Default: the latest session of the current directory. `ccview -l` lists this directory's sessions (`-l -a` lists every project's), `ccview ID` picks one by id prefix, `ccview -t 3` shows only the last 3 prompts, `--no-thinking` and `--no-tools` hide those. |
| `rd FILE` | Picks one of the above: markdown through `glow` (if installed), `*.jsonl` through `ccview`, everything else through `nvcat`. `rd` with no file is `ccview`. |

## Install (on the phone)

```sh
pkg install neovim glow          # glow is optional, for markdown in rd
pip install pygments             # optional, for highlighted code in ccview (else bat, else none)
gh repo clone senti0726/termux-smooth-scroll ~/termux-smooth-scroll
~/termux-smooth-scroll/tools/reading/install.sh
```

`install.sh` links the three commands into `$PREFIX/bin`, pointing back at the checkout, so a
`git pull` there updates them.

## Notes

- **Thinking text.** Recent Claude Code versions save only how long it thought, not the text, in
  the transcript. `ccview` then prints `∴ thought for 34s (text not saved in the transcript)`.
  When the text is saved, it is printed in dim italics.
- **Markdown in nvcat.** `nvcat` reproduces nvim's colours, not render-markdown's virtual text
  (bullets, rendered tables, concealed markers). Use `rd` or `glow` for rendered markdown.
- **Speed.** `nvcat` starts your nvim config once per file. If your config is slow to start,
  `--clean` is much faster but uses default colours and only the parsers bundled with nvim.
- **Long output.** Everything goes into the scrollback (`terminal-transcript-rows`, default 2000
  lines). For a long file or session, raise it, e.g. `terminal-transcript-rows = 20000` in
  `termux.properties`.
- **Width.** Lines wrap at the terminal's width. Pinch-zooming afterwards re-wraps them.
