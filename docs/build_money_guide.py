"""Builds docs/HueVista_Money_Guide.pdf — every price and rule, in plain words.

Regenerate after ANY pricing change:  python3 docs/build_money_guide.py

The numbers here are typed by hand, not read from application.properties, so they can
drift. When you change a price, change it in three places: application.properties (or
Plan.java), the pricing page, and this file. The guide is handed to shopkeepers — a
stale number in it is a promise you did not mean to make.
"""

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont as RLTTFont
from reportlab.platypus import (
    BaseDocTemplate, Frame, KeepTogether, NextPageTemplate, PageBreak,
    PageTemplate, Paragraph, Spacer, Table, TableStyle,
)

OUT = "/home/user/HueVista/docs/HueVista_Money_Guide.pdf"

# Helvetica has no rupee sign — every price would render as a black box. This font does,
# and it is embedded in the file so the guide travels.
# FreeSans over DejaVu: it carries the rupee sign too, and unlike the packaged DejaVu
# it ships all four faces, so <i> renders as italic instead of silently falling back.
_F = "/usr/share/fonts/truetype/freefont"
pdfmetrics.registerFont(RLTTFont("Body", f"{_F}/FreeSans.ttf"))
pdfmetrics.registerFont(RLTTFont("Body-Bold", f"{_F}/FreeSansBold.ttf"))
pdfmetrics.registerFont(RLTTFont("Body-Italic", f"{_F}/FreeSansOblique.ttf"))
pdfmetrics.registerFont(RLTTFont("Body-BoldItalic", f"{_F}/FreeSansBoldOblique.ttf"))
pdfmetrics.registerFontFamily(
    "Body", normal="Body", bold="Body-Bold",
    italic="Body-Italic", boldItalic="Body-BoldItalic")

INK = colors.HexColor("#1c1a24")
SOFT = colors.HexColor("#4a4658")
MUTE = colors.HexColor("#7a7590")
ACCENT = colors.HexColor("#6b4ce6")
ACCENT_BG = colors.HexColor("#f2efff")
RULE = colors.HexColor("#ded9ee")
GREEN_BG = colors.HexColor("#eaf7ef")
AMBER_BG = colors.HexColor("#fff6e6")

styles = getSampleStyleSheet()


def S(name, **kw):
    kw.setdefault("parent", styles["Normal"])
    return ParagraphStyle(name, **kw)


TITLE = S("t", fontName="Body-Bold", fontSize=27, leading=32, textColor=INK, spaceAfter=6)
SUBTITLE = S("st", fontName="Body", fontSize=12, leading=18, textColor=MUTE, spaceAfter=22)
H1 = S("h1", fontName="Body-Bold", fontSize=17.5, leading=22, textColor=INK,
       spaceBefore=20, spaceAfter=4)
H1_NUM = S("h1n", fontName="Body-Bold", fontSize=10, leading=12, textColor=ACCENT,
           spaceBefore=22, spaceAfter=3)
H2 = S("h2", fontName="Body-Bold", fontSize=11.5, leading=15, textColor=INK,
       spaceBefore=14, spaceAfter=4)
BODY = S("b", fontName="Body", fontSize=9.4, leading=15, textColor=SOFT, spaceAfter=8)
BULLET = S("bl", parent=BODY, leftIndent=13, bulletIndent=3, spaceAfter=4)
CELL = S("c", fontName="Body", fontSize=8.4, leading=12, textColor=SOFT)
CELL_B = S("cb", fontName="Body-Bold", fontSize=8.4, leading=12, textColor=INK)
CELL_H = S("ch", fontName="Body-Bold", fontSize=7.6, leading=10.5, textColor=colors.white)
NOTE = S("n", fontName="Body", fontSize=9, leading=14.5, textColor=SOFT)
NOTE_B = S("nb", fontName="Body-Bold", fontSize=9, leading=14.5, textColor=INK)
COVER_KICKER = S("ck", fontName="Body-Bold", fontSize=9, leading=12, textColor=ACCENT,
                 alignment=TA_CENTER, spaceAfter=10)
COVER_TITLE = S("ct", fontName="Body-Bold", fontSize=30, leading=37, textColor=INK,
                alignment=TA_CENTER, spaceAfter=12)
COVER_SUB = S("cs", fontName="Body", fontSize=12.5, leading=20, textColor=MUTE,
              alignment=TA_CENTER, spaceAfter=8)
FOOTNOTE = S("fn", fontName="Body", fontSize=8, leading=12, textColor=MUTE)


def box(flowables, bg=ACCENT_BG, border=None, pad=9):
    """A tinted callout box."""
    t = Table([[flowables]], colWidths=[165 * mm])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), bg),
        ("BOX", (0, 0), (-1, -1), 0.6, border or bg),
        ("LEFTPADDING", (0, 0), (-1, -1), pad + 2),
        ("RIGHTPADDING", (0, 0), (-1, -1), pad + 2),
        ("TOPPADDING", (0, 0), (-1, -1), pad),
        ("BOTTOMPADDING", (0, 0), (-1, -1), pad),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
    ]))
    return t


def table(rows, widths, align_right=()):
    """Header row + body, styled consistently."""
    data = [[Paragraph(c, CELL_H) for c in rows[0]]]
    for r in rows[1:]:
        data.append([Paragraph(c, CELL_B if i == 0 else CELL) for i, c in enumerate(r)])
    t = Table(data, colWidths=widths, repeatRows=1)
    style = [
        ("BACKGROUND", (0, 0), (-1, 0), ACCENT),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("LEFTPADDING", (0, 0), (-1, -1), 7),
        ("RIGHTPADDING", (0, 0), (-1, -1), 7),
        ("TOPPADDING", (0, 0), (-1, -1), 4.5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4.5),
        ("LINEBELOW", (0, 1), (-1, -2), 0.4, RULE),
        ("BOX", (0, 0), (-1, -1), 0.5, RULE),
    ]
    for i in range(2, len(data), 2):
        style.append(("BACKGROUND", (0, i), (-1, i), colors.HexColor("#faf9ff")))
    for col in align_right:
        style.append(("ALIGN", (col, 0), (col, -1), "RIGHT"))
    t.setStyle(TableStyle(style))
    return t


def bullets(items, style=BULLET):
    return [Paragraph(f"•&nbsp;&nbsp;{i}", style) for i in items]


# ── Page furniture ──────────────────────────────────────────────────────────

def cover_bg(canvas, doc):
    canvas.saveState()
    canvas.setFillColor(ACCENT_BG)
    canvas.rect(0, A4[1] - 118 * mm, A4[0], 118 * mm, stroke=0, fill=1)
    canvas.setFillColor(ACCENT)
    canvas.rect(0, A4[1] - 121 * mm, A4[0], 3 * mm, stroke=0, fill=1)
    canvas.restoreState()


def page_furniture(canvas, doc):
    canvas.saveState()
    canvas.setStrokeColor(RULE)
    canvas.setLineWidth(0.5)
    canvas.line(22 * mm, A4[1] - 17 * mm, A4[0] - 22 * mm, A4[1] - 17 * mm)
    canvas.setFont("Body-Bold", 7.2)
    canvas.setFillColor(ACCENT)
    canvas.drawString(22 * mm, A4[1] - 14.5 * mm, "HUEVISTA")
    canvas.setFont("Body", 7.2)
    canvas.setFillColor(MUTE)
    canvas.drawRightString(A4[0] - 22 * mm, A4[1] - 14.5 * mm, "How the money works")
    canvas.drawCentredString(A4[0] / 2, 13 * mm, str(canvas.getPageNumber()))
    canvas.restoreState()


doc = BaseDocTemplate(
    OUT, pagesize=A4,
    leftMargin=22 * mm, rightMargin=22 * mm, topMargin=24 * mm, bottomMargin=20 * mm,
    title="HueVista — How the money works",
    author="HueVista", subject="Plans, projects, extra images and reward points explained simply",
)
frame = Frame(doc.leftMargin, doc.bottomMargin, doc.width, doc.height, id="f")
doc.addPageTemplates([
    PageTemplate(id="cover", frames=[frame], onPage=cover_bg),
    PageTemplate(id="body", frames=[frame], onPage=page_furniture),
])

W = doc.width
s = []

# ── Cover ───────────────────────────────────────────────────────────────────
s += [
    Spacer(1, 26 * mm),
    Paragraph("A PLAIN-WORDS GUIDE", COVER_KICKER),
    Paragraph("How the money<br/>works in HueVista", COVER_TITLE),
    Paragraph(
        "Every plan, every price and every button that costs something &mdash;<br/>"
        "explained the simple way, with no jargon.", COVER_SUB),
    Spacer(1, 42 * mm),
]

s.append(box([
    Paragraph("The whole thing in four sentences", NOTE_B),
    Spacer(1, 6),
    Paragraph(
        "A <b>paint shop</b> pays HueVista a small amount every month, like a phone recharge. "
        "That monthly pack gives the shop a set number of <b>photo makeovers</b> to use up. "
        "If the shop runs out before the month ends, it can buy a few more, one at a time. "
        "And when a walk-in customer pays at the shop's counter link, the shop collects "
        "<b>reward points</b> it can spend later instead of money.", NOTE),
], bg=colors.white, border=RULE, pad=12))

s += [
    Spacer(1, 10 * mm),
    Paragraph(
        "HueVista &middot; Proprietor: Vikram Mali &middot; Abu Road, Rajasthan, India<br/>"
        "All prices in Indian rupees. GST is currently 0%, so the price you see is the price you pay.",
        S("cf", parent=FOOTNOTE, alignment=TA_CENTER)),
    NextPageTemplate("body"),
    PageBreak(),
]

# ── 1. Cast ─────────────────────────────────────────────────────────────────
s += [
    Paragraph("PART ONE", H1_NUM),
    Paragraph("Who is who", H1),
    Paragraph(
        "Only two kinds of people use HueVista, and only one of them ever pays us monthly. "
        "Keeping them straight makes everything else easy.", BODY),
    Spacer(1, 4),
    table([
        ["", "The paint shop", "The walk-in customer"],
        ["Who they are", "A shop that sells paint. They have an account and sign in.",
         "Someone who walks into that shop wanting to see a colour on their wall."],
        ["What they pay", "A monthly plan &mdash; and extras when they want more.",
         "Nothing, usually. The shop covers it. Or a one-off &#8377;99 at the shop's counter link."],
        ["What they get", "Photo makeovers, colour boards, a counter link, reward points.",
         "A picture of their own room in the colour they picked."],
    ], [26 * mm, W / 2 - 13 * mm, W / 2 - 13 * mm]),
    Spacer(1, 10),
]

s.append(box([
    Paragraph("Think of it like a photo studio", NOTE_B),
    Spacer(1, 5),
    Paragraph(
        "The <b>shop</b> is the studio owner. They buy a pack of film every month. "
        "The <b>customer</b> is the person getting their photo taken &mdash; they don't buy film, "
        "they just walk out with the picture.", NOTE),
]))

# ── 2. Photo makeover ───────────────────────────────────────────────────────
s += [
    Paragraph("PART TWO", H1_NUM),
    Paragraph("What one “photo makeover” means", H1),
    Paragraph(
        "Almost every price in HueVista is counted in <b>images</b>. So it is worth knowing "
        "exactly what one image is. It happens in two steps:", BODY),
    Spacer(1, 2),
]

s += bullets([
    "<b>Step 1 &mdash; the clean-up.</b> Someone takes a photo of a wall. It is dark, or blurry, "
    "or crooked. HueVista tidies it up. <b>This always happens, and it always uses one image "
    "from your monthly pack.</b>",
    "<b>Step 2 &mdash; drawing the edges.</b> Before we can paint the wall a new colour, we need "
    "to know exactly where the wall stops and the window begins. There are two ways to do this.",
])

s += [
    Spacer(1, 6),
    table([
        ["Way of drawing the edges", "What it means", "What it costs"],
        ["By hand", "You tap or trace the wall yourself, like colouring inside the lines.",
         "<b>Free. Unlimited. On every plan.</b>"],
        ["Auto-mask (the robot way)", "The computer finds the wall for you in a second or two.",
         "Uses one <b>auto-mask</b> from your monthly pack."],
    ], [42 * mm, W - 42 * mm - 34 * mm, 34 * mm]),
    Spacer(1, 8),
]

s.append(box([
    Paragraph(
        "<b>This is why there are two numbers on every plan.</b> One counts photos "
        "(<i>images</i>), one counts robot-drawn edges (<i>auto-masks</i>). You can always run "
        "out of robot help and keep working by hand &mdash; doing it yourself never costs "
        "anything and never runs out.", NOTE),
], bg=GREEN_BG, border=colors.HexColor("#c6e6d2")))

s.append(PageBreak())

# ── 3. Plans ────────────────────────────────────────────────────────────────
s += [
    Paragraph("PART THREE", H1_NUM),
    Paragraph("The monthly plans", H1),
    Paragraph(
        "A plan is a monthly recharge pack. You pay on the same date each month, and the "
        "counters go back to full. Anything you didn't use <b>does not carry over</b> &mdash; "
        "next month starts fresh, same as a phone pack.", BODY),
    Spacer(1, 4),
    table([
        ["Plan", "Per month", "Photos", "Auto-masks", "Colour boards", "Photos per board"],
        ["Free trial", "&#8377;0", "3", "2", "5", "4"],
        ["Starter", "&#8377;999", "20", "5", "25", "4"],
        ["Professional", "&#8377;2,499", "60", "40", "100", "8"],
        ["Business", "&#8377;4,999", "120", "90", "300", "12"],
        ["Enterprise", "Ask us", "Unlimited", "Unlimited", "Unlimited", "16"],
    ], [26 * mm, 21 * mm, 21 * mm, 25 * mm, 26 * mm, 27 * mm],
        align_right=(1, 2, 3, 4, 5)),
    Spacer(1, 6),
    Paragraph(
        "A <b>colour board</b> is the PDF you hand the customer &mdash; their room in a few "
        "different shades, side by side, ready to print or send on WhatsApp. The last column is "
        "how many pictures fit inside one board.", FOOTNOTE),
]

s += [
    Paragraph("The free trial", H2),
    Paragraph(
        "Every new shop starts with <b>7 days free</b>. No card, no payment, nothing charged "
        "when it ends. You get <b>3 projects</b> in that week &mdash; two you can let the robot "
        "draw, one you draw by hand. It is meant to be enough to try the whole thing properly "
        "on a real customer, not to run a week of business on.", BODY),
]

s += [
    Paragraph("Moving between plans", H2),
]
s += bullets([
    "<b>Going up</b> (Starter &rarr; Professional) happens straight away. Your counters reset to "
    "the bigger numbers immediately, and the old plan is cancelled for you so you are never "
    "charged twice.",
    "<b>Going down</b> waits. Cancel the plan you are on &mdash; it keeps working until the end "
    "of the month you already paid for &mdash; then pick the smaller one.",
    "<b>Cancelling</b> never cuts you off on the spot. You keep everything until the last day "
    "you paid for.",
])

s.append(Spacer(1, 6))
s.append(box([
    Paragraph("One thing worth knowing about projects", NOTE_B),
    Spacer(1, 5),
    Paragraph(
        "Once you are on a <b>paid</b> plan, you can make as many projects as you like. What "
        "limits you is the number of photos in your pack, not the number of projects. "
        "Projects are only ever bought one-by-one by a shop on the free trial, or a shop with "
        "no plan at all.", NOTE),
], bg=AMBER_BG, border=colors.HexColor("#f0dcb4")))

s.append(PageBreak())

# ── 4. Running out ──────────────────────────────────────────────────────────
s += [
    Paragraph("PART FOUR", H1_NUM),
    Paragraph("What happens when you run out", H1),
    Paragraph(
        "It is the 22nd, your 20 photos are gone, and a customer is standing at the counter. "
        "Nothing breaks. You have three ways to get one more, and they all end in the same "
        "place &mdash; one extra photo added to your account, straight away.", BODY),
    Spacer(1, 4),
    table([
        ["Way", "How it works", "One extra photo", "One extra auto-mask"],
        ["Pay right now", "A card or UPI payment, there and then.", "&#8377;50", "&#8377;25"],
        ["From your wallet", "Money you topped up earlier. One tap, no payment screen.",
         "&#8377;50", "&#8377;25"],
        ["With reward points", "Points your counter link earned you. No money at all.",
         "40 points", "20 points"],
    ], [28 * mm, W - 28 * mm - 62 * mm, 31 * mm, 31 * mm], align_right=(2, 3)),
    Spacer(1, 8),
]

s.append(box([
    Paragraph(
        "<b>Extras never expire.</b> A photo you bought in June is still sitting there in "
        "September. Only your <i>monthly</i> allowance resets &mdash; things you paid extra "
        "for stay until you use them.", NOTE),
], bg=GREEN_BG, border=colors.HexColor("#c6e6d2")))

# ── 5. Wallet ───────────────────────────────────────────────────────────────
s += [
    Paragraph("The wallet — your piggy bank", H2),
    Paragraph(
        "Instead of tapping through a payment screen every single time, you can put money in "
        "once and spend it whenever. That is the wallet.", BODY),
]
s += bullets([
    "Add anything from <b>&#8377;100</b> to <b>&#8377;1,00,000</b> at a time.",
    "It <b>never expires</b>.",
    "It buys extra photos (&#8377;50), extra auto-masks (&#8377;25), whole projects and reopens.",
    "You need a live plan to <i>add</i> money to it &mdash; the wallet tops up a plan.",
    "It cannot be turned back into cash or sent to your bank. It is credit for using HueVista.",
])

s.append(PageBreak())

# ── 6. Projects ─────────────────────────────────────────────────────────────
s += [
    Paragraph("PART FIVE", H1_NUM),
    Paragraph("Projects", H1),
    Paragraph(
        "A <b>project</b> is one customer's job: their room, their photos, the colours they are "
        "choosing between. You open it, work on it, and hand them a colour board at the end.", BODY),
    Spacer(1, 4),
    table([
        ["Situation", "What a project costs", "How long it stays open"],
        ["On a paid plan", "Nothing extra &mdash; make as many as you want", "30 days"],
        ["On the free trial", "First 3 free, then &#8377;50 each", "30 days"],
        ["No plan at all", "&#8377;99 each", "30 days"],
        ["Reopening an old one", "&#8377;9", "another 30 days"],
    ], [40 * mm, W - 40 * mm - 38 * mm, 38 * mm], align_right=(1, 2)),
    Spacer(1, 8),
]

s.append(box([
    Paragraph("The clock pauses while you are subscribed", NOTE_B),
    Spacer(1, 5),
    Paragraph(
        "Those 30 days only tick down when you <b>don't</b> have a plan. While a plan is running, "
        "the window is frozen &mdash; a project you bought stays banked and starts counting again "
        "only if your plan ends. You never lose days you paid for just because you subscribed.", NOTE),
]))

s += [
    Paragraph("If a project runs out", H2),
    Paragraph(
        "It doesn't get deleted. It goes quiet &mdash; you can still see it, you just can't work "
        "on it. Pay <b>&#8377;9</b> (or 9 points) and it opens again for another 30 days, with "
        "everything exactly as you left it.", BODY),
]

# ── 7. Kiosk ────────────────────────────────────────────────────────────────
s += [
    Paragraph("PART SIX", H1_NUM),
    Paragraph("The counter link", H1),
    Paragraph(
        "Every shop gets its own web link, something like <b>huevista.com/store/mehta-x7k2p9</b>. "
        "Print it as a QR code, put it on a tablet at the counter, or send it on WhatsApp. "
        "A customer opens it and does the whole thing themselves.", BODY),
    Spacer(1, 2),
]

s += bullets([
    "The customer pays <b>&#8377;99</b>, once. Card, UPI or QR.",
    "They get a code and land straight in the app &mdash; no sign-up, no password.",
    "They upload one photo of their room and try colours on it.",
    "The code also appears in your <i>Active codes</i> list, so you can see what they chose.",
    "<b>You earn 30 reward points.</b>",
])

s.append(Spacer(1, 8))
s.append(box([
    Paragraph("Why the shop doesn't get a share of the &#8377;99", NOTE_B),
    Spacer(1, 5),
    Paragraph(
        "The &#8377;99 is a payment from the customer to HueVista, for a HueVista service. "
        "You don't set that price and you don't take a cut of it &mdash; you get points instead. "
        "This is deliberate. Collecting money on someone else's behalf and passing it on is a "
        "regulated activity in India with its own licences and rules. Rewarding you in points "
        "keeps the payment simple and keeps everybody on the right side of it.", NOTE),
], bg=AMBER_BG, border=colors.HexColor("#f0dcb4")))

s.append(PageBreak())

# ── 8. Points ───────────────────────────────────────────────────────────────
s += [
    Paragraph("PART SEVEN", H1_NUM),
    Paragraph("Reward points", H1),
    Paragraph(
        "Points are like the stamps on a coffee-shop loyalty card. You collect them for free by "
        "using your counter link, and you spend them on HueVista things. They are <b>not money</b>: "
        "you cannot buy them, sell them, give them away, or take them out as cash.", BODY),
    Spacer(1, 4),
    table([
        ["What you spend points on", "Points", "The same thing in cash"],
        ["One extra photo", "40", "&#8377;50"],
        ["One extra auto-mask", "20", "&#8377;25"],
        ["One whole project", "80", "&#8377;50 &ndash; &#8377;99"],
        ["Reopening a project", "9", "&#8377;9"],
    ], [W - 60 * mm, 25 * mm, 35 * mm], align_right=(1, 2)),
    Spacer(1, 8),
]

s.append(box([
    Paragraph("Points are worth a little more than the cash price", NOTE_B),
    Spacer(1, 5),
    Paragraph(
        "An extra photo costs &#8377;50 in money but only 40 points. That gap is the reward &mdash; "
        "it is why using your counter link is worth doing. Because of that, points have their own "
        "price list and are counted separately from your wallet money. <b>You will see two "
        "balances in the app, and that is on purpose.</b>", NOTE),
]))

s += [
    Paragraph("Points expire after one year", H2),
    Paragraph(
        "Every batch of points has its own birthday. Points you earn today are good for "
        "<b>365 days</b> and then they are gone.", BODY),
]

s += bullets([
    "<b>The oldest points get used first</b>, automatically. You never have to think about it, "
    "and you never lose points that you could have spent.",
    "We email you <b>10 days before</b> a batch is due to expire.",
    "We email you again <b>on the day itself</b>, so there is no quiet surprise.",
    "The app shows the exact date the next batch runs out, and turns it red in the last 10 days.",
])

s += [
    Spacer(1, 6),
    Paragraph("Two more rules", H2),
]
s += bullets([
    "<b>Points are for shops only.</b> A customer account cannot earn or spend them &mdash; "
    "everything they buy belongs to a shop.",
    "<b>If a customer gets a refund, the points from that sale go back too.</b> If you already "
    "spent them, your balance goes below zero and the next points you earn quietly fill the hole. "
    "Nothing is taken from you twice.",
])

s.append(PageBreak())

# ── 9. Everything on one page ───────────────────────────────────────────────
s += [
    Paragraph("PART EIGHT", H1_NUM),
    Paragraph("Every price, on one page", H1),
    Paragraph("Pin this one up. Everything HueVista can charge for is here.", BODY),
    Spacer(1, 4),
]

s.append(KeepTogether([
    Paragraph("Monthly plans", H2),
    table([
        ["Plan", "Per month", "Photos", "Auto-masks", "Colour boards"],
        ["Free trial (7 days)", "&#8377;0", "3", "2", "5"],
        ["Starter", "&#8377;999", "20", "5", "25"],
        ["Professional", "&#8377;2,499", "60", "40", "100"],
        ["Business", "&#8377;4,999", "120", "90", "300"],
        ["Enterprise", "Ask us", "Unlimited", "Unlimited", "Unlimited"],
    ], [38 * mm, 24 * mm, 24 * mm, 27 * mm, 30 * mm], align_right=(1, 2, 3, 4)),
]))

s.append(Spacer(1, 4))
s.append(KeepTogether([
    Paragraph("One-off things", H2),
    table([
        ["What", "In cash", "In points"],
        ["One extra photo", "&#8377;50", "40"],
        ["One extra auto-mask", "&#8377;25", "20"],
        ["One project (on a plan or trial)", "&#8377;50", "80"],
        ["One project (no plan)", "&#8377;99", "80"],
        ["Reopen a project for 30 more days", "&#8377;9", "9"],
        ["Customer pays at your counter link", "&#8377;99", "&mdash; (you earn 30)"],
    ], [W - 66 * mm, 28 * mm, 38 * mm], align_right=(1, 2)),
]))

s.append(Spacer(1, 4))
s.append(KeepTogether([
    Paragraph("The two balances", H2),
    table([
        ["", "Wallet", "Reward points"],
        ["What it is", "Rupees you topped up", "Points you earned"],
        ["How you get it", "You pay for it", "Free, from counter-link sales"],
        ["Does it expire?", "No, never", "Yes &mdash; one year per batch"],
        ["Can it become cash?", "No", "No"],
        ["Who can hold it", "Any shop account", "Shops only"],
    ], [42 * mm, (W - 42 * mm) / 2, (W - 42 * mm) / 2]),
]))

closing = [
    Spacer(1, 6),
    box([
    Paragraph("Still not sure about something?", NOTE_B),
    Spacer(1, 5),
    Paragraph(
        "Use the support chat in the app &mdash; it knows who you are, so billing questions "
        "get answered faster. Or write to <b>payments@huevista.org</b>, or call "
        "<b>+91 63784 82381</b> (Mon&ndash;Sat, 10am&ndash;7pm). The full rules live at "
        "<b>huevista.com/legal/refunds</b> and <b>/legal/terms</b> &mdash; this guide explains "
        "them, those pages are the ones that count.", NOTE),
], bg=colors.white, border=RULE, pad=9),
    Spacer(1, 7),
    Paragraph(
        "All prices in Indian rupees, correct at the time of writing. GST is currently 0%, so "
        "the price shown is the price charged. Payments are handled by Razorpay &mdash; "
        "HueVista never sees or stores your card details.", FOOTNOTE),
]
s.append(KeepTogether(closing))

doc.build(s)
print("wrote", OUT)
