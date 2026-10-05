-- nvcat.lua: print the current buffer to stdout with nvim's own highlighting, as ANSI colours.
-- Run by the nvcat script: nvim --headless ... -c "luafile nvcat.lua" FILE
--
-- Colours come from nvim itself, so they match the editor: treesitter highlight captures
-- (with injections, e.g. code blocks in markdown) when the buffer has a parser, else the
-- legacy :syntax groups. Options arrive as globals set with --cmd:
--   g:nvcat_number    1 to print line numbers
--   g:nvcat_bg        1 to paint the colourscheme's background too
--   g:nvcat_filetype  filetype to use instead of the detected one

local api, fn = vim.api, vim.fn
local buf = api.nvim_get_current_buf()

local out = io.stdout
local function finish(code)
  out:flush()
  vim.cmd((code == 0) and "qa!" or ("cq! " .. code))
end

if vim.g.nvcat_filetype and vim.g.nvcat_filetype ~= "" then
  vim.bo[buf].filetype = vim.g.nvcat_filetype
end

local lines = api.nvim_buf_get_lines(buf, 0, -1, false)

-- hl[row][byte] = group name, prio[row][byte] = priority of the capture that set it.
local hl, prio = {}, {}
for row = 1, #lines do
  hl[row], prio[row] = {}, {}
end

local function mark(group, sr, sc, er, ec, priority)
  for row = sr, er do
    local text = lines[row + 1]
    if text then
      local from = (row == sr) and sc or 0
      local to = (row == er) and ec or #text
      local h, p = hl[row + 1], prio[row + 1]
      for col = from + 1, math.min(to, #text) do
        if (p[col] or -1) <= priority then
          h[col], p[col] = group, priority
        end
      end
    end
  end
end

local function from_treesitter()
  local ok, parser = pcall(vim.treesitter.get_parser, buf)
  if not ok or not parser then return false end
  -- Parse everything, injections included (the argument is ignored before nvim 0.10).
  pcall(parser.parse, parser, true)
  local any = false
  parser:for_each_tree(function(tree, ltree)
    local lang = ltree:lang()
    local qok, query = pcall(vim.treesitter.query.get, lang, "highlights")
    if not qok or not query then return end
    any = true
    for id, node, metadata in query:iter_captures(tree:root(), buf, 0, -1) do
      local name = query.captures[id]
      if name ~= "spell" and name ~= "nospell" and name:sub(1, 1) ~= "_" then
        local p = metadata.priority or (metadata[id] and metadata[id].priority)
        local sr, sc, er, ec = node:range()
        mark("@" .. name .. "." .. lang, sr, sc, er, ec, tonumber(p) or 100)
      end
    end
  end)
  return any
end

local function from_syntax()
  if vim.bo[buf].syntax == "" then
    vim.cmd("syntax enable")
    vim.bo[buf].syntax = vim.bo[buf].filetype
  end
  for row = 1, #lines do
    local h = hl[row]
    for col = 1, #lines[row] do
      local id = fn.synIDtrans(fn.synID(row, col, 1))
      if id ~= 0 then h[col] = fn.synIDattr(id, "name") end
    end
  end
end

if not from_treesitter() then from_syntax() end

-- Resolve groups to colours. "@keyword.function.lua" falls back to "@keyword.function", then "@keyword".
local cache = {}
local function attrs(group)
  if cache[group] ~= nil then return cache[group] end
  local name, a = group, nil
  while name do
    local ok, got = pcall(api.nvim_get_hl, 0, { name = name, link = false })
    if ok and got and next(got) then a = got break end
    name = name:match("^(.*)%.[^.]*$")
  end
  cache[group] = a or false
  return cache[group]
end

local function sgr(a, paint_bg, normal)
  local codes = {}
  local fg = a and (a.reverse and a.bg or a.fg) or normal.fg
  local bg = a and (a.reverse and a.fg or a.bg) or nil
  if a and a.bold then codes[#codes + 1] = "1" end
  if a and a.italic then codes[#codes + 1] = "3" end
  if a and (a.underline or a.undercurl) then codes[#codes + 1] = "4" end
  if a and a.strikethrough then codes[#codes + 1] = "9" end
  if fg then
    codes[#codes + 1] = string.format("38;2;%d;%d;%d", bit.rshift(fg, 16), bit.band(bit.rshift(fg, 8), 255), bit.band(fg, 255))
  end
  bg = bg or (paint_bg and normal.bg) or nil
  if bg then
    codes[#codes + 1] = string.format("48;2;%d;%d;%d", bit.rshift(bg, 16), bit.band(bit.rshift(bg, 8), 255), bit.band(bg, 255))
  end
  return "\27[0;" .. table.concat(codes, ";") .. "m"
end

local normal = attrs("Normal") or {}
local paint_bg = vim.g.nvcat_bg == 1
local numbers = vim.g.nvcat_number == 1
local linenr = attrs("LineNr")
local tabstop = vim.bo[buf].tabstop
local width = #tostring(#lines)

local chunk = {}
for row = 1, #lines do
  local text, h = lines[row], hl[row]
  local parts = {}
  if numbers then
    parts[#parts + 1] = sgr(linenr, paint_bg, normal) .. string.format("%" .. width .. "d ", row)
  end
  local col, start, display = 1, 1, 0
  while start <= #text do
    local group = h[start]
    col = start
    while col <= #text and h[col] == group do col = col + 1 end
    local raw = text:sub(start, col - 1)
    -- Expand tabs to the buffer's tabstop, counting display columns.
    local seg = raw:gsub("([^\t]*)\t", function(before)
      display = display + fn.strdisplaywidth(before)
      local pad = tabstop - (display % tabstop)
      display = display + pad
      return before .. string.rep(" ", pad)
    end)
    display = display + fn.strdisplaywidth(raw:match("[^\t]*$") or "")
    parts[#parts + 1] = sgr(group and attrs(group), paint_bg, normal) .. seg
    start = col
  end
  parts[#parts + 1] = "\27[0m\n"
  chunk[#chunk + 1] = table.concat(parts)
  if #chunk >= 256 then
    out:write(table.concat(chunk))
    chunk = {}
  end
end
out:write(table.concat(chunk))
finish(0)
