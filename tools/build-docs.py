#!/usr/bin/env python3
"""
Render the player guide to HTML for the web app (issue #118).

The Markdown in docs/guide/ is the source and has to stay completely readable as
plain text; this produces the UI's copy of it. Conversion goes through Richard's
mdview so the app renders Markdown the same way his own viewer does — but mdview
wraps its output in a fixed light-mode page (#333 on #f5f5f5), which would be
unreadable in Empire's dark theme. So we take the body and leave the chrome, and
the app supplies the styling from the design tokens.

Output: empire-web/public/guide/<name>.html, one fragment per guide page, plus
guide/index.json listing them. The SPA route is /help; the fragments live under
/guide so the SPA route whitelist cannot swallow the files the page fetches. Vite copies public/ into dist/, which is packaged
into the server jar as static/, so the pages ship with the app.
"""
import importlib.machinery
import importlib.util
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GUIDE = ROOT / "docs" / "guide"
OUT = ROOT / "empire-web" / "public" / "guide"
MDVIEW = Path.home() / "bin" / "mdview"

# The order the guide is meant to be read in; anything not listed is appended.
ORDER = ["index", "first-turn", "the-update", "economy", "moving-goods", "ships", "levels", "commands", "deity"]


def load_mdview():
    if not MDVIEW.exists():
        sys.exit(f"mdview not found at {MDVIEW} — it is the converter this build uses")
    loader = importlib.machinery.SourceFileLoader("mdview", str(MDVIEW))
    spec = importlib.util.spec_from_loader("mdview", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


def fragment(mdview, text, title):
    """mdview's rendering, without its page chrome."""
    full = mdview.convert_markdown_string_to_html(text, title=title)
    if "<body>" not in full or "</body>" not in full:
        sys.exit("mdview's output no longer has a <body> to extract; build-docs.py needs updating")
    return full.split("<body>", 1)[1].rsplit("</body>", 1)[0].strip()


def title_of(text, fallback):
    m = re.search(r"^#\s+(.+)$", text, re.M)
    return m.group(1).strip() if m else fallback


def main():
    if not GUIDE.is_dir():
        sys.exit(f"no guide at {GUIDE}")
    mdview = load_mdview()
    OUT.mkdir(parents=True, exist_ok=True)
    for stale in OUT.glob("*.html"):
        stale.unlink()

    names = sorted(p.stem for p in GUIDE.glob("*.md"))
    ordered = [n for n in ORDER if n in names] + [n for n in names if n not in ORDER]

    pages = []
    for name in ordered:
        text = (GUIDE / f"{name}.md").read_text(encoding="utf-8")
        title = title_of(text, name)
        # in-guide links are written as .html so the Markdown reads naturally and the app resolves them
        (OUT / f"{name}.html").write_text(fragment(mdview, text, title), encoding="utf-8")
        pages.append({"slug": name, "title": title})
        print(f"  {name}.md -> guide/{name}.html  ({title})")

    (OUT / "index.json").write_text(json.dumps(pages, indent=2), encoding="utf-8")
    print(f"{len(pages)} guide pages -> {OUT}")


if __name__ == "__main__":
    main()
