#!/usr/bin/env python3
"""
compute_cer.py — augment bench_results/results.csv with CER per row.

Reference: the transcript produced by REFERENCE_MODEL (default
'large-v3-q5_0') on the same clip. CER = Levenshtein distance over
characters / len(reference). NFKC-normalized, whitespace-stripped.

Usage:
    python3 compute_cer.py [path/to/results.csv]
"""
from __future__ import annotations

import csv
import sys
import unicodedata
from pathlib import Path

REFERENCE_MODEL = "large-v3-q5_0"


def normalize(text: str) -> str:
    """NFKC + drop all whitespace. Keeps punctuation and letters."""
    t = unicodedata.normalize("NFKC", text)
    return "".join(t.split())


try:
    from rapidfuzz.distance import Levenshtein as _Lev
    def edit_distance(a: str, b: str) -> int:
        return _Lev.distance(a, b)
except ImportError:
    def edit_distance(a: str, b: str) -> int:
        """Pure-Python Levenshtein fallback (slow on long strings)."""
        if len(a) < len(b):
            a, b = b, a
        if not b:
            return len(a)
        prev = list(range(len(b) + 1))
        for i, ca in enumerate(a, 1):
            curr = [i]
            for j, cb in enumerate(b, 1):
                curr.append(min(curr[j - 1] + 1, prev[j] + 1,
                                prev[j - 1] + (ca != cb)))
            prev = curr
        return prev[-1]


def cer(ref: str, hyp: str) -> float:
    if not ref:
        return 0.0 if not hyp else 1.0
    return edit_distance(ref, hyp) / len(ref)


def read_text(path: str) -> str:
    p = Path(path)
    if not p.exists():
        return ""
    try:
        return p.read_text(encoding="utf-8")
    except Exception:
        return p.read_text(encoding="utf-8", errors="replace")


def main() -> None:
    default = Path(__file__).parent / "bench_results" / "results.csv"
    csv_path = Path(sys.argv[1]) if len(sys.argv) > 1 else default
    if not csv_path.exists():
        sys.exit(f"results.csv not found: {csv_path}")

    rows = list(csv.DictReader(csv_path.open(encoding="utf-8")))
    if not rows:
        sys.exit("results.csv is empty")

    # Build reference text per clip
    ref_by_clip: dict[str, str] = {}
    for r in rows:
        if r["model"] == REFERENCE_MODEL and r["transcript"] not in ("FAIL", "SKIP", ""):
            ref_by_clip[r["clip"]] = normalize(read_text(r["transcript"]))

    if not ref_by_clip:
        sys.exit(f"No usable rows for reference model '{REFERENCE_MODEL}'. "
                 f"Did the bench run produce a transcript for it?")

    out_rows = []
    for r in rows:
        ref_text = ref_by_clip.get(r["clip"], "")
        hyp_text = ""
        if r["transcript"] not in ("FAIL", "SKIP", ""):
            hyp_text = normalize(read_text(r["transcript"]))

        if r["model"] == REFERENCE_MODEL:
            r["cer"] = "0.000"
        elif not ref_text:
            r["cer"] = "NA-no-ref"
        elif not hyp_text:
            r["cer"] = "NA-no-hyp"
        else:
            r["cer"] = f"{cer(ref_text, hyp_text):.3f}"
        r["ref_chars"] = str(len(ref_text))
        r["hyp_chars"] = str(len(hyp_text))
        out_rows.append(r)

    fieldnames = list(rows[0].keys())
    for extra in ("cer", "ref_chars", "hyp_chars"):
        if extra not in fieldnames:
            fieldnames.append(extra)

    out_path = csv_path.with_name(csv_path.stem + "_with_cer.csv")
    with out_path.open("w", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        w.writerows(out_rows)

    # ── Pretty summary ────────────────────────────────────────────────────
    print(f"\nWrote {out_path}\n")
    header = (f"{'model':<16} {'clip':<22} "
              f"{'audio':>7} {'wall':>7} {'RTF':>6} {'CER':>8}")
    print(header)
    print("-" * len(header))

    def to_f(s, default=float("nan")):
        try:
            return float(s)
        except (TypeError, ValueError):
            return default

    for r in out_rows:
        rtf = to_f(r.get("rtf", ""))
        wall = to_f(r.get("wall_sec", ""))
        audio = to_f(r.get("audio_sec", ""))
        rtf_s = f"{rtf:6.2f}" if rtf == rtf else "    NA"   # NaN check
        wall_s = f"{wall:7.1f}" if wall == wall else "     NA"
        audio_s = f"{audio:7.1f}" if audio == audio else "     NA"
        print(f"{r['model']:<16} {r['clip']:<22} "
              f"{audio_s} {wall_s} {rtf_s} {r['cer']:>8}")

    # Per-model averages
    print("\n── per-model averages ──")
    by_model: dict[str, list[tuple[float, float]]] = {}
    for r in out_rows:
        rtf = to_f(r.get("rtf", ""))
        cer_val = to_f(r.get("cer", ""))
        if rtf == rtf and cer_val == cer_val:
            by_model.setdefault(r["model"], []).append((rtf, cer_val))
    print(f"{'model':<16} {'avg_RTF':>8} {'avg_CER':>8} {'n':>3}")
    for m, vals in by_model.items():
        avg_rtf = sum(v[0] for v in vals) / len(vals)
        avg_cer = sum(v[1] for v in vals) / len(vals)
        print(f"{m:<16} {avg_rtf:>8.2f} {avg_cer:>8.3f} {len(vals):>3}")


if __name__ == "__main__":
    main()
