The acceptance probe is maintainer tooling only. It is not a terminal-library API,
production feature, network service or downloadable release asset. It must only be
installed on a disposable emulator with synthetic test data. It does not bypass
Android authentication, SSH host pins, app encryption or agent hook review.

The direct-input investigation checked upstream Termux's InputConnection handling:
https://github.com/termux/termux-app/blob/master/terminal-view/src/main/java/com/termux/view/TerminalView.java

Shello retains its own InputConnection implementation and Unicode/VT encoder. Its
raw editor uses a valid TYPE_CLASS_TEXT with visible-password variation and no
forced ASCII flag; confirmed Unicode commits remain supported. No Termux source
was copied into this probe or the terminal library.
