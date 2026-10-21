set nocompatible
set background=dark
set t_Co=256
set laststatus=2
set number
set cursorline
set hlsearch
set incsearch
set noswapfile
set nobackup
set nowritebackup
syntax on
hi Normal ctermfg=252 ctermbg=234
hi LineNr ctermfg=244 ctermbg=234
hi CursorLine cterm=NONE ctermbg=236
hi CursorLineNr cterm=bold ctermfg=220 ctermbg=236
hi Search cterm=NONE ctermfg=16 ctermbg=226
hi IncSearch cterm=reverse ctermfg=231 ctermbg=160
hi Visual cterm=reverse ctermfg=NONE ctermbg=NONE
hi StatusLine cterm=bold ctermfg=231 ctermbg=24
hi StatusLineNC cterm=NONE ctermfg=250 ctermbg=238
hi Comment ctermfg=114
hi String ctermfg=215
hi Statement ctermfg=81
hi Type ctermfg=141
hi EndOfBuffer ctermfg=244 ctermbg=234
set statusline=%f\ COLOR\ CHECK%=%l:%c\ %p%%
set viminfo=
