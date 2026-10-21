# Ghostty Android: vim color verification
# 中文背景 / 高亮 / 反色

def render_color(name):
    message = "color target: orange string"
    for index in range(3):
        print(name, index, message)
    return "color target: search me"

# Visual selection must invert complete cells.
# CursorLine and status line must cover erased spaces.
render_color("color target")

# End of disposable color fixture.
