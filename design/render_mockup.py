"""
WhisperFlow UI Mockup — three screens as a triptych on a single landscape PDF.
Embodies the "Quiet Instrument" design philosophy.
"""
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import landscape
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfbase.cidfonts import UnicodeCIDFont
from pathlib import Path

FONT_DIR = Path(
    "/Users/shinelin/Library/Application Support/Claude/local-agent-mode-sessions/"
    "skills-plugin/d2948554-16d0-4ce3-8f9c-33abec298756/"
    "2d4f907c-a368-4088-b158-cc66af6e3c1c/skills/canvas-design/canvas-fonts"
)

# Register fonts
pdfmetrics.registerFont(TTFont("Sans",        str(FONT_DIR / "InstrumentSans-Regular.ttf")))
pdfmetrics.registerFont(TTFont("Sans-Bold",   str(FONT_DIR / "InstrumentSans-Bold.ttf")))
pdfmetrics.registerFont(TTFont("Sans-It",     str(FONT_DIR / "InstrumentSans-Italic.ttf")))
pdfmetrics.registerFont(TTFont("Mono",        str(FONT_DIR / "GeistMono-Regular.ttf")))
pdfmetrics.registerFont(TTFont("Mono-Bold",   str(FONT_DIR / "GeistMono-Bold.ttf")))
pdfmetrics.registerFont(TTFont("Display",     str(FONT_DIR / "BricolageGrotesque-Bold.ttf")))
# Built-in CID Japanese font for the subtitle samples
pdfmetrics.registerFont(UnicodeCIDFont("HeiseiKakuGo-W5"))
JP_FONT = "HeiseiKakuGo-W5"

# Palette — calibrated, monastic
BG          = (0.054, 0.054, 0.063)   # #0E0E10 — outer canvas
PHONE_BG    = (0.063, 0.063, 0.071)   # #10101A — phone screen
SURFACE     = (0.094, 0.094, 0.106)   # #18181B — card surfaces
SURFACE_HI  = (0.118, 0.118, 0.133)   # #1E1E22 — slight lift
DIVIDER     = (0.165, 0.165, 0.184)   # #2A2A2F — hairline
TEXT_HI     = (0.949, 0.949, 0.957)   # #F2F2F4 — headline white
TEXT_MD     = (0.667, 0.667, 0.706)   # #AAAAB4 — body
TEXT_LO     = (0.392, 0.392, 0.439)   # #646470 — caption
TEXT_LL     = (0.255, 0.255, 0.290)   # #41414A — barely
AMBER       = (0.831, 0.635, 0.298)   # #D4A24C — the one accent
AMBER_DIM   = (0.475, 0.357, 0.149)   # #785B26 — amber shadow (used very sparingly)
PULSE       = (0.376, 0.831, 0.510)   # #60D481 — green dot for status
PULSE_DIM   = (0.220, 0.486, 0.298)   # darker green ring

# Geometry
PAGE_W = 1620   # 3 phones × 480 + gutters
PAGE_H = 1700   # extra height for the landscape variant below the triptych
PHONE_W = 420
PHONE_H = 880
PHONE_RADIUS = 32
GUTTER = 40
TOP_MARGIN = 80


def set_fill(c, rgb):
    c.setFillColorRGB(*rgb)


def set_stroke(c, rgb):
    c.setStrokeColorRGB(*rgb)


def hairline(c, x1, y1, x2, y2, color=DIVIDER, width=0.5):
    c.saveState()
    set_stroke(c, color)
    c.setLineWidth(width)
    c.line(x1, y1, x2, y2)
    c.restoreState()


def filled_rect(c, x, y, w, h, color, radius=0):
    c.saveState()
    set_fill(c, color)
    if radius:
        c.roundRect(x, y, w, h, radius, fill=1, stroke=0)
    else:
        c.rect(x, y, w, h, fill=1, stroke=0)
    c.restoreState()


def stroked_rect(c, x, y, w, h, color, radius=0, width=0.6):
    c.saveState()
    set_stroke(c, color)
    c.setLineWidth(width)
    if radius:
        c.roundRect(x, y, w, h, radius, fill=0, stroke=1)
    else:
        c.rect(x, y, w, h, fill=0, stroke=1)
    c.restoreState()


def draw_text(c, text, x, y, font, size, color, align="left", tracking=0):
    c.saveState()
    set_fill(c, color)
    c.setFont(font, size)
    if tracking:
        # apply letter-spacing manually using char-by-char drawing
        if align == "center":
            # measure full width with tracking applied
            total = sum(pdfmetrics.stringWidth(ch, font, size) for ch in text) + tracking * max(0, len(text) - 1)
            cursor = x - total / 2
        elif align == "right":
            total = sum(pdfmetrics.stringWidth(ch, font, size) for ch in text) + tracking * max(0, len(text) - 1)
            cursor = x - total
        else:
            cursor = x
        for ch in text:
            c.drawString(cursor, y, ch)
            cursor += pdfmetrics.stringWidth(ch, font, size) + tracking
    else:
        if align == "center":
            c.drawCentredString(x, y, text)
        elif align == "right":
            c.drawRightString(x, y, text)
        else:
            c.drawString(x, y, text)
    c.restoreState()


def phone_frame(c, x, y):
    """Draw the phone outline + screen, return inner content rect (cx, cy, cw, ch)."""
    # Subtle outer glow / device chrome — minimal, just a hairline
    set_stroke(c, DIVIDER)
    c.setLineWidth(0.6)
    c.roundRect(x - 6, y - 6, PHONE_W + 12, PHONE_H + 12, PHONE_RADIUS + 4, fill=0, stroke=1)
    # Screen
    filled_rect(c, x, y, PHONE_W, PHONE_H, PHONE_BG, radius=PHONE_RADIUS)
    # Top notch suggestion (a thin pill)
    notch_w, notch_h = 92, 5
    filled_rect(c, x + (PHONE_W - notch_w) / 2, y + PHONE_H - 16,
                notch_w, notch_h, (0.04, 0.04, 0.05), radius=2.5)
    return x, y, PHONE_W, PHONE_H


def app_bar(c, px, py, status_text="READY", status_color=PULSE):
    """Status bar + app bar."""
    # status bar (time + indicators) — very subtle, mono
    bar_y = py + PHONE_H - 38
    draw_text(c, "9:41", px + 24, bar_y, "Mono", 10, TEXT_MD)
    # right side indicator dots
    for i, w in enumerate([14, 14, 18]):
        filled_rect(c, px + PHONE_W - 24 - i * 18 - w, bar_y + 1, w, 6,
                    TEXT_LL, radius=1)

    # app bar
    ab_y = py + PHONE_H - 90
    # Wordmark — a small geometric mark + name
    mark_x = px + 24
    mark_y = ab_y + 4
    # The mark: a small amber arc + dot (translation glyph abstraction)
    c.saveState()
    set_stroke(c, AMBER)
    c.setLineWidth(1.4)
    c.arc(mark_x, mark_y - 4, mark_x + 14, mark_y + 10, 30, 240)
    c.restoreState()
    filled_rect(c, mark_x + 14, mark_y + 1, 4, 4, AMBER, radius=2)

    draw_text(c, "WhisperFlow", mark_x + 28, mark_y, "Display", 16, TEXT_HI)

    # Status pill (right)
    pill_w = 78
    pill_h = 24
    pill_x = px + PHONE_W - 24 - pill_w
    pill_y = ab_y - 4
    filled_rect(c, pill_x, pill_y, pill_w, pill_h, SURFACE, radius=pill_h / 2)
    # pulse dot
    dot_x = pill_x + 12
    dot_y = pill_y + pill_h / 2
    c.saveState()
    set_fill(c, status_color)
    c.circle(dot_x, dot_y, 3, fill=1, stroke=0)
    # ring
    set_stroke(c, status_color)
    c.setLineWidth(0.4)
    c.circle(dot_x, dot_y, 6, fill=0, stroke=1)
    c.restoreState()
    draw_text(c, status_text, dot_x + 10, dot_y - 3, "Mono-Bold", 8, TEXT_HI, tracking=0.8)

    # divider under app bar
    hairline(c, px + 24, ab_y - 16, px + PHONE_W - 24, ab_y - 16)


# ─────────────────────────────────────────────────────────────────────────────
# SCREEN 1 — Empty / Landing
# ─────────────────────────────────────────────────────────────────────────────
def draw_screen_1(c, px, py):
    phone_frame(c, px, py)
    app_bar(c, px, py, "READY", PULSE)

    # URL input row — input + GO + FILE all in one row, equal heights
    input_y = py + PHONE_H - 170
    input_h = 44
    pad = 24
    btn_go_w = 56
    btn_file_w = 56
    btn_gap = 8
    input_w = PHONE_W - 2 * pad - btn_go_w - btn_file_w - btn_gap * 2

    # input
    filled_rect(c, px + pad, input_y, input_w, input_h, SURFACE, radius=12)
    stroked_rect(c, px + pad, input_y, input_w, input_h, DIVIDER, radius=12, width=0.5)
    draw_text(c, "Paste video URL or YouTube link", px + pad + 14, input_y + 16,
              "Sans", 11, TEXT_LO)
    # blinking caret hint
    filled_rect(c, px + pad + 14, input_y + 12, 1, 18, TEXT_LL)

    # GO button (filled amber)
    go_x = px + pad + input_w + btn_gap
    filled_rect(c, go_x, input_y, btn_go_w, input_h, AMBER, radius=12)
    draw_text(c, "GO", go_x + btn_go_w / 2, input_y + 16, "Sans-Bold", 12,
              (0.063, 0.063, 0.071), align="center", tracking=1.2)

    # FILE button (outlined)
    file_x = go_x + btn_go_w + btn_gap
    stroked_rect(c, file_x, input_y, btn_file_w, input_h, AMBER, radius=12, width=0.8)
    draw_text(c, "FILE", file_x + btn_file_w / 2, input_y + 16, "Sans-Bold", 11,
              AMBER, align="center", tracking=1.2)

    # Language selector row
    lang_y = input_y - 60
    draw_text(c, "FROM", px + pad, lang_y + 16, "Mono", 9, TEXT_LO, tracking=1.4)
    # from dropdown
    fd_x, fd_w = px + pad + 36, 124
    filled_rect(c, fd_x, lang_y, fd_w, 36, SURFACE, radius=10)
    draw_text(c, "Auto-detect", fd_x + 12, lang_y + 12, "Sans", 11, TEXT_HI)
    # chevron
    c.saveState()
    set_stroke(c, TEXT_LO)
    c.setLineWidth(0.8)
    cx, cy = fd_x + fd_w - 14, lang_y + 18
    c.line(cx - 4, cy + 2, cx, cy - 2)
    c.line(cx, cy - 2, cx + 4, cy + 2)
    c.restoreState()

    # arrow
    arr_x = fd_x + fd_w + 12
    c.saveState()
    set_stroke(c, AMBER)
    c.setLineWidth(1.2)
    c.line(arr_x, lang_y + 18, arr_x + 18, lang_y + 18)
    c.line(arr_x + 14, lang_y + 22, arr_x + 18, lang_y + 18)
    c.line(arr_x + 14, lang_y + 14, arr_x + 18, lang_y + 18)
    c.restoreState()

    # to dropdown
    td_x = arr_x + 30
    td_w = 96
    draw_text(c, "TO", td_x - 26, lang_y + 16, "Mono", 9, TEXT_LO, tracking=1.4)
    filled_rect(c, td_x, lang_y, td_w, 36, SURFACE, radius=10)
    draw_text(c, "English", td_x + 12, lang_y + 12, "Sans", 11, TEXT_HI)
    c.saveState()
    set_stroke(c, TEXT_LO)
    c.setLineWidth(0.8)
    cx, cy = td_x + td_w - 14, lang_y + 18
    c.line(cx - 4, cy + 2, cx, cy - 2)
    c.line(cx, cy - 2, cx + 4, cy + 2)
    c.restoreState()

    # show original toggle row
    tog_y = lang_y - 50
    draw_text(c, "Show original text", px + pad, tog_y + 4, "Sans", 11, TEXT_MD)
    # iOS-style switch (off)
    sw_x, sw_y, sw_w, sw_h = px + PHONE_W - pad - 44, tog_y - 2, 38, 22
    filled_rect(c, sw_x, sw_y, sw_w, sw_h, SURFACE_HI, radius=sw_h / 2)
    c.saveState()
    set_fill(c, TEXT_LO)
    c.circle(sw_x + sw_h / 2 + 1, sw_y + sw_h / 2, sw_h / 2 - 3, fill=1, stroke=0)
    c.restoreState()

    # Center hero — translation glyph + tagline
    hero_y = py + PHONE_H * 0.32
    glyph_size = 56
    # The glyph: a stylised "文/A" mark in a thin square frame
    gx, gy = px + PHONE_W / 2, hero_y + 60
    c.saveState()
    set_stroke(c, AMBER)
    c.setLineWidth(0.8)
    c.roundRect(gx - glyph_size / 2, gy - glyph_size / 2, glyph_size, glyph_size,
                10, fill=0, stroke=1)
    c.restoreState()
    # 文 abstraction — three strokes
    c.saveState()
    set_stroke(c, AMBER)
    c.setLineWidth(1.6)
    c.line(gx - 14, gy + 12, gx + 14, gy + 12)              # top horizontal
    c.line(gx - 4, gy + 14, gx - 14, gy - 8)                # diagonal
    c.line(gx + 4, gy + 14, gx + 14, gy - 8)                # diagonal
    c.line(gx - 16, gy - 12, gx + 16, gy - 12)              # bottom horizontal
    c.restoreState()

    draw_text(c, "Real-time AI translation", px + PHONE_W / 2, hero_y - 4,
              "Display", 18, TEXT_HI, align="center")
    draw_text(c, "for any video", px + PHONE_W / 2, hero_y - 26,
              "Display", 18, TEXT_HI, align="center")

    # subtle horizontal accent line
    hairline(c, px + PHONE_W / 2 - 18, hero_y - 50, px + PHONE_W / 2 + 18, hero_y - 50,
             color=AMBER, width=0.8)

    # Hint copy at bottom
    hint_y = py + 100
    draw_text(c, "Paste a direct video URL  ·  .mp4  .m3u8  ·  YouTube",
              px + PHONE_W / 2, hint_y, "Mono", 8.5, TEXT_LO, align="center", tracking=0.8)
    draw_text(c, "or tap FILE to load from your device",
              px + PHONE_W / 2, hint_y - 16, "Mono", 8.5, TEXT_LL, align="center", tracking=0.8)

    # screen label (tiny, bottom)
    draw_text(c, "01 — READY", px + 24, py - 22, "Mono", 8, TEXT_LO, tracking=2)


# ─────────────────────────────────────────────────────────────────────────────
# SCREEN 2 — Active translation (video-first, subtitle below like YouTube CC)
# ─────────────────────────────────────────────────────────────────────────────
def draw_screen_2(c, px, py):
    phone_frame(c, px, py)
    app_bar(c, px, py, "TRANSLATING", AMBER)

    pad = 24

    # Video player — large, sits high, edge-to-edge with thin pad
    vid_y_top = py + PHONE_H - 110          # below app bar
    vid_w = PHONE_W - 2 * pad
    vid_h = vid_w * 9 / 16
    vid_y = vid_y_top - vid_h
    filled_rect(c, px + pad, vid_y, vid_w, vid_h, (0.024, 0.024, 0.027), radius=12)
    # frame hint inside
    c.saveState()
    set_fill(c, (0.110, 0.094, 0.075))
    c.circle(px + PHONE_W / 2 - 28, vid_y + vid_h / 2 + 6, 16, fill=1, stroke=0)
    set_fill(c, (0.063, 0.063, 0.071))
    c.circle(px + PHONE_W / 2 - 28, vid_y + vid_h / 2 + 6, 5, fill=1, stroke=0)
    set_stroke(c, (0.078, 0.078, 0.086))
    c.setLineWidth(1)
    c.line(px + pad + 20, vid_y + 32, px + PHONE_W - pad - 20, vid_y + 32)
    c.restoreState()

    # Time HUD top-right of video
    draw_text(c, "14:32 / 23:18", px + PHONE_W - pad - 10, vid_y + vid_h - 20,
              "Mono", 9, TEXT_HI, align="right", tracking=0.4)
    # Tiny CC badge top-left of video
    cc_w, cc_h = 26, 14
    filled_rect(c, px + pad + 10, vid_y + vid_h - 24, cc_w, cc_h,
                (0.04, 0.04, 0.05), radius=3)
    stroked_rect(c, px + pad + 10, vid_y + vid_h - 24, cc_w, cc_h,
                 AMBER, radius=3, width=0.5)
    draw_text(c, "CC", px + pad + 10 + cc_w / 2, vid_y + vid_h - 19,
              "Mono-Bold", 7, AMBER, align="center", tracking=1.0)

    # Subtitle band — directly under video, NO card background. Just text.
    sub_top = vid_y - 22
    # Translated (primary)
    draw_text(c, "Today I want to interview a special person.",
              px + PHONE_W / 2, sub_top, "Display", 15, TEXT_HI, align="center")
    # Original (smaller, dim) — shown only because "Show original" is ON
    draw_text(c, "今日は特別な人にインタビューしたいと思います",
              px + PHONE_W / 2, sub_top - 22, JP_FONT, 11, TEXT_LO, align="center")

    # ASR progress capillary — a hair-thin pulse bar below subtitle text
    cap_y = sub_top - 42
    cap_x = px + PHONE_W / 2 - 36
    hairline(c, cap_x, cap_y, cap_x + 72 * 0.65, cap_y, color=AMBER, width=1)
    hairline(c, cap_x + 72 * 0.65, cap_y, cap_x + 72, cap_y, color=AMBER_DIM, width=1)

    # Scrub bar
    bar_y = cap_y - 38
    bar_x = px + pad
    bar_w = PHONE_W - 2 * pad
    hairline(c, bar_x, bar_y, bar_x + bar_w, bar_y, color=DIVIDER, width=2)
    prog = 0.62
    c.saveState()
    set_stroke(c, AMBER)
    c.setLineWidth(2)
    c.line(bar_x, bar_y, bar_x + bar_w * prog, bar_y)
    set_fill(c, AMBER)
    c.circle(bar_x + bar_w * prog, bar_y, 4, fill=1, stroke=0)
    c.restoreState()
    draw_text(c, "14:32", bar_x, bar_y - 16, "Mono", 8, TEXT_LO)
    draw_text(c, "23:18", bar_x + bar_w, bar_y - 16, "Mono", 8, TEXT_LO, align="right")

    # Inline controls row
    ctrl_y = bar_y - 50

    # Left: play button (amber filled circle)
    pcx = px + pad + 18
    pcy = ctrl_y
    c.saveState()
    set_fill(c, AMBER)
    c.circle(pcx, pcy, 14, fill=1, stroke=0)
    set_fill(c, PHONE_BG)
    p = c.beginPath()
    p.moveTo(pcx - 4, pcy - 6); p.lineTo(pcx - 4, pcy + 6); p.lineTo(pcx + 6, pcy); p.close()
    c.drawPath(p, fill=1, stroke=0)
    c.restoreState()

    # Center: lang chip (changeable on-the-fly)
    chip_w, chip_h = 110, 28
    chip_x = px + (PHONE_W - chip_w) / 2
    chip_y = pcy - chip_h / 2
    filled_rect(c, chip_x, chip_y, chip_w, chip_h, SURFACE, radius=chip_h / 2)
    stroked_rect(c, chip_x, chip_y, chip_w, chip_h, DIVIDER, radius=chip_h / 2, width=0.5)
    draw_text(c, "JA", chip_x + 14, chip_y + 9, "Mono-Bold", 9, TEXT_MD, tracking=1.0)
    # arrow
    c.saveState()
    set_stroke(c, AMBER)
    c.setLineWidth(1)
    c.line(chip_x + 36, chip_y + 14, chip_x + 50, chip_y + 14)
    c.line(chip_x + 47, chip_y + 17, chip_x + 50, chip_y + 14)
    c.line(chip_x + 47, chip_y + 11, chip_x + 50, chip_y + 14)
    c.restoreState()
    draw_text(c, "EN", chip_x + 56, chip_y + 9, "Mono-Bold", 9, TEXT_HI, tracking=1.0)
    # chevron
    c.saveState()
    set_stroke(c, TEXT_LO)
    c.setLineWidth(0.8)
    cv_x, cv_y = chip_x + chip_w - 12, chip_y + 14
    c.line(cv_x - 4, cv_y + 2, cv_x, cv_y - 2)
    c.line(cv_x, cv_y - 2, cv_x + 4, cv_y + 2)
    c.restoreState()

    # Right: fullscreen icon (suggests landscape mode)
    fs_x = px + PHONE_W - pad - 18
    fs_y = pcy
    c.saveState()
    set_stroke(c, TEXT_MD)
    c.setLineWidth(1.2)
    sz = 6
    # Four L-shaped corners
    for dx, dy, sx, sy in [(-1, 1, -1, 1), (1, 1, 1, 1), (-1, -1, -1, -1), (1, -1, 1, -1)]:
        x0 = fs_x + dx * 8
        y0 = fs_y + dy * 8
        c.line(x0, y0, x0 - sx * sz, y0)
        c.line(x0, y0, x0, y0 - sy * sz)
    c.restoreState()

    # Recent transcript preview (3 dim lines) — gives sense of context, doesn't dominate
    tr_y = ctrl_y - 50
    draw_text(c, "RECENT", px + pad, tr_y, "Mono", 8, TEXT_LO, tracking=1.6)
    hairline(c, px + pad + 56, tr_y - 4, px + PHONE_W - pad, tr_y - 4)

    recent = [
        ("14:18", "Hello everyone, how are you?"),
        ("14:24", "Today I want to interview a special person."),
        ("14:32", "He was my senior when I taught Japanese in Mexico."),
    ]
    line_y = tr_y - 22
    for ts, txt in recent:
        draw_text(c, ts, px + pad, line_y, "Mono", 8, TEXT_LL, tracking=0.8)
        # truncate-ish
        max_chars = 36
        display = txt if len(txt) <= max_chars else txt[: max_chars - 1] + "…"
        draw_text(c, display, px + pad + 40, line_y, "Sans", 10, TEXT_LO)
        line_y -= 18

    # screen label
    draw_text(c, "02 — TRANSLATING", px + 24, py - 22, "Mono", 8, TEXT_LO, tracking=2)


# ─────────────────────────────────────────────────────────────────────────────
# SCREEN 2L — Landscape variant of screen 2 (fullscreen, subtitle overlay)
# ─────────────────────────────────────────────────────────────────────────────
LANDSCAPE_W = PHONE_H        # 880 — long side becomes width
LANDSCAPE_H = PHONE_W        # 420 — short side becomes height


def draw_screen_2_landscape(c, lx, ly):
    """Phone rotated 90° clockwise — the same screen 2 in landscape mode."""
    # Outer chrome
    set_stroke(c, DIVIDER)
    c.setLineWidth(0.6)
    c.roundRect(lx - 6, ly - 6, LANDSCAPE_W + 12, LANDSCAPE_H + 12,
                PHONE_RADIUS + 4, fill=0, stroke=1)
    filled_rect(c, lx, ly, LANDSCAPE_W, LANDSCAPE_H, PHONE_BG, radius=PHONE_RADIUS)
    # Notch on the side now (left edge in landscape)
    notch_w, notch_h = 5, 92
    filled_rect(c, lx + 16, ly + (LANDSCAPE_H - notch_h) / 2, notch_w, notch_h,
                (0.04, 0.04, 0.05), radius=2.5)

    # The video fills the whole screen (immersive landscape)
    pad = 24
    vid_x = lx + pad
    vid_y = ly + pad
    vid_w = LANDSCAPE_W - 2 * pad
    vid_h = LANDSCAPE_H - 2 * pad
    filled_rect(c, vid_x, vid_y, vid_w, vid_h, (0.024, 0.024, 0.027), radius=10)
    # frame hint
    c.saveState()
    set_fill(c, (0.110, 0.094, 0.075))
    c.circle(vid_x + vid_w / 2 - 60, vid_y + vid_h / 2 + 18, 22, fill=1, stroke=0)
    set_fill(c, (0.063, 0.063, 0.071))
    c.circle(vid_x + vid_w / 2 - 60, vid_y + vid_h / 2 + 18, 7, fill=1, stroke=0)
    set_stroke(c, (0.078, 0.078, 0.086))
    c.setLineWidth(1.2)
    c.line(vid_x + 60, vid_y + 80, vid_x + vid_w - 60, vid_y + 80)
    c.line(vid_x + 80, vid_y + 70, vid_x + vid_w - 80, vid_y + 70)
    c.restoreState()

    # Top-right HUD: status pill + time
    pill_w, pill_h = 100, 22
    pill_x = vid_x + vid_w - pill_w - 14
    pill_y = vid_y + vid_h - pill_h - 14
    filled_rect(c, pill_x, pill_y, pill_w, pill_h, (0.04, 0.04, 0.05, 0.9), radius=pill_h / 2)
    # pulse dot
    c.saveState()
    set_fill(c, AMBER)
    c.circle(pill_x + 12, pill_y + pill_h / 2, 3, fill=1, stroke=0)
    c.restoreState()
    draw_text(c, "TRANSLATING", pill_x + 22, pill_y + 7, "Mono-Bold", 7.5,
              TEXT_HI, tracking=1.0)
    # time HUD top-left
    draw_text(c, "14:32 / 23:18", vid_x + 16, vid_y + vid_h - 20,
              "Mono-Bold", 9, TEXT_HI, tracking=0.4)

    # Subtitle overlay — bottom of video, centered, no background card.
    # Just text with subtle drop-readability via slight scrim block behind it.
    sub_top = vid_y + 100
    # very thin scrim — just a shadow-strip rectangle for readability
    scrim_h = 80
    c.saveState()
    set_fill(c, (0, 0, 0, 0.25))  # tiny dark wash
    c.rect(vid_x, vid_y, vid_w, scrim_h, fill=1, stroke=0)
    c.restoreState()
    # translated (primary)
    draw_text(c, "Today I want to interview a special person.",
              vid_x + vid_w / 2, sub_top - 28, "Display", 18, TEXT_HI, align="center")
    # original (smaller, dim)
    draw_text(c, "今日は特別な人にインタビューしたいと思います",
              vid_x + vid_w / 2, sub_top - 52, JP_FONT, 12, TEXT_LO, align="center")

    # Bottom-edge controls overlay (very minimal, pill style)
    ctrl_w, ctrl_h = 240, 36
    ctrl_x = vid_x + (vid_w - ctrl_w) / 2
    ctrl_y = vid_y + 22
    filled_rect(c, ctrl_x, ctrl_y, ctrl_w, ctrl_h, (0.04, 0.04, 0.05, 0.9),
                radius=ctrl_h / 2)
    # play
    pcx = ctrl_x + 22
    pcy = ctrl_y + ctrl_h / 2
    c.saveState()
    set_fill(c, AMBER)
    c.circle(pcx, pcy, 11, fill=1, stroke=0)
    set_fill(c, PHONE_BG)
    p = c.beginPath()
    p.moveTo(pcx - 3, pcy - 5); p.lineTo(pcx - 3, pcy + 5); p.lineTo(pcx + 5, pcy); p.close()
    c.drawPath(p, fill=1, stroke=0)
    c.restoreState()
    # progress bar inside pill
    pb_x = ctrl_x + 44
    pb_w = ctrl_w - 80
    pb_y = pcy
    hairline(c, pb_x, pb_y, pb_x + pb_w, pb_y, color=DIVIDER, width=2)
    set_stroke(c, AMBER); c.setLineWidth(2)
    c.line(pb_x, pb_y, pb_x + pb_w * 0.62, pb_y)
    c.saveState()
    set_fill(c, AMBER)
    c.circle(pb_x + pb_w * 0.62, pb_y, 3, fill=1, stroke=0)
    c.restoreState()
    # exit fullscreen icon at right
    fs_x = ctrl_x + ctrl_w - 22
    fs_y = pcy
    c.saveState()
    set_stroke(c, TEXT_MD)
    c.setLineWidth(1.2)
    sz = 5
    for dx, dy, sx, sy in [(-1, 1, 1, -1), (1, 1, -1, -1), (-1, -1, 1, 1), (1, -1, -1, 1)]:
        x0 = fs_x + dx * 7
        y0 = fs_y + dy * 7
        c.line(x0, y0, x0 + sx * sz, y0)
        c.line(x0, y0, x0, y0 + sy * sz)
    c.restoreState()

    # screen label
    draw_text(c, "02L — TRANSLATING / LANDSCAPE", lx + 24, ly - 22,
              "Mono", 8, TEXT_LO, tracking=2)


# ─────────────────────────────────────────────────────────────────────────────
# SCREEN 3 — Transcript / history
# ─────────────────────────────────────────────────────────────────────────────
def draw_screen_3(c, px, py):
    phone_frame(c, px, py)
    app_bar(c, px, py, "PLAYING", AMBER)

    pad = 24
    list_top = py + PHONE_H - 140

    # entries — list of subtitle records
    entries = [
        ("00:01:12", "ja",
         "皆さんこんにちは、元気ですか",
         "Hello everyone, how are you?", False),
        ("00:01:18", "ja",
         "今日は特別な人にインタビューしたいと思います",
         "Today I want to interview a special person.", False),
        ("00:01:24", "ja",
         "私の先輩で、メキシコで日本語を教えていた時の先輩です",
         "He was my senior when I taught Japanese in Mexico.", True),  # active
        ("00:01:32", "ja",
         "だから今日は私はタメ語を使いません",
         "So today I won't use casual speech.", False),
        ("00:01:38", "ja",
         "敬語で話します",
         "I'll speak in formal Japanese.", False),
    ]

    y = list_top
    for ts, lang, orig, trans, active in entries:
        entry_h = 96
        ex = px + pad
        ew = PHONE_W - 2 * pad
        if active:
            # subtle surface lift + amber left border
            filled_rect(c, ex, y - entry_h, ew, entry_h, SURFACE, radius=12)
            filled_rect(c, ex, y - entry_h + 12, 2, entry_h - 24, AMBER, radius=1)
        # timestamp + lang tag
        draw_text(c, ts, ex + (16 if active else 4), y - 14,
                  "Mono", 8.5, AMBER if active else TEXT_LO, tracking=1.2)
        draw_text(c, "·  " + lang.upper(), ex + (16 if active else 4) + 56, y - 14,
                  "Mono", 8.5, TEXT_LL, tracking=1.2)
        # original
        draw_text(c, orig, ex + (16 if active else 4), y - 36,
                  JP_FONT, 11, TEXT_MD if active else TEXT_LO)
        # translated
        draw_text(c, trans, ex + (16 if active else 4), y - 60,
                  "Display", 14 if active else 13,
                  TEXT_HI if active else TEXT_MD)
        # divider after entry
        if not active:
            hairline(c, ex + 4, y - entry_h + 4, ex + ew - 4, y - entry_h + 4)
        y -= entry_h + 4

    # Floating "now playing" pill at bottom
    pill_w, pill_h = 168, 40
    pcx = px + (PHONE_W - pill_w) / 2
    pcy = py + 100
    filled_rect(c, pcx, pcy, pill_w, pill_h, SURFACE_HI, radius=pill_h / 2)
    stroked_rect(c, pcx, pcy, pill_w, pill_h, AMBER, radius=pill_h / 2, width=0.6)
    # mini play triangle
    c.saveState()
    set_fill(c, AMBER)
    p = c.beginPath()
    p.moveTo(pcx + 16, pcy + 12); p.lineTo(pcx + 16, pcy + 28); p.lineTo(pcx + 28, pcy + 20); p.close()
    c.drawPath(p, fill=1, stroke=0)
    c.restoreState()
    draw_text(c, "JUMP TO NOW", pcx + 38, pcy + 16, "Mono-Bold", 9, TEXT_HI, tracking=1.4)

    # screen label
    draw_text(c, "03 — TRANSCRIPT", px + 24, py - 22, "Mono", 8, TEXT_LO, tracking=2)


# ─────────────────────────────────────────────────────────────────────────────
# Compose the page
# ─────────────────────────────────────────────────────────────────────────────
def main():
    out = "/Users/shinelin/Desktop/WorkSpace/Mobile Live Tranlation/design/whisperflow_mockup.pdf"
    c = canvas.Canvas(out, pagesize=(PAGE_W, PAGE_H))

    # Outer canvas
    filled_rect(c, 0, 0, PAGE_W, PAGE_H, BG)

    # Title block (top, very subtle)
    title_y = PAGE_H - 44
    draw_text(c, "WHISPERFLOW", PAGE_W / 2, title_y, "Display", 18, TEXT_HI,
              align="center", tracking=4)
    draw_text(c, "design study  ·  three states  ·  quiet instrument",
              PAGE_W / 2, title_y - 18, "Mono", 8.5, TEXT_LO, align="center", tracking=2)

    # Three phones — top row
    phone_y = PAGE_H - 160 - PHONE_H
    total_w = 3 * PHONE_W + 2 * GUTTER
    start_x = (PAGE_W - total_w) / 2

    draw_screen_1(c, start_x + 0 * (PHONE_W + GUTTER), phone_y)
    draw_screen_2(c, start_x + 1 * (PHONE_W + GUTTER), phone_y)
    draw_screen_3(c, start_x + 2 * (PHONE_W + GUTTER), phone_y)

    # Landscape variant — centered below the triptych, above footer
    land_y = phone_y - 90 - LANDSCAPE_H
    land_x = (PAGE_W - LANDSCAPE_W) / 2
    draw_screen_2_landscape(c, land_x, land_y)

    # Footer — palette swatches + meta
    foot_y = 56
    sw_y = foot_y - 4
    sw_size = 10
    sw_gap = 2
    swatches = [
        ("#0E0E10", BG),
        ("#10101A", PHONE_BG),
        ("#18181B", SURFACE),
        ("#2A2A2F", DIVIDER),
        ("#646470", TEXT_LO),
        ("#AAAAB4", TEXT_MD),
        ("#F2F2F4", TEXT_HI),
        ("#D4A24C", AMBER),
    ]
    sw_x_start = start_x
    for i, (label, color) in enumerate(swatches):
        x = sw_x_start + i * (sw_size + sw_gap)
        filled_rect(c, x, sw_y, sw_size, sw_size, color, radius=1)
    draw_text(c, "PALETTE", sw_x_start + len(swatches) * (sw_size + sw_gap) + 12,
              foot_y - 1, "Mono", 8, TEXT_LO, tracking=2)

    draw_text(c, "INSTRUMENT SANS  ·  GEIST MONO  ·  BRICOLAGE GROTESQUE",
              PAGE_W / 2, foot_y - 1, "Mono", 8, TEXT_LO, align="center", tracking=2)

    draw_text(c, "v0.1  ·  2026.04", start_x + total_w, foot_y - 1,
              "Mono", 8, TEXT_LO, align="right", tracking=2)

    c.save()
    print(f"Wrote {out}")


if __name__ == "__main__":
    main()
