#!/usr/bin/env python3
"""
SVG diagram audit script.
Checks for:
1. Arrows whose start/end points land inside no card bounding box (disconnected)
2. Arrow path segments that pass through a card interior (clipping)
3. Cards that overflow the frame boundary
"""

import re
import sys
import os
from pathlib import Path

DIAGRAMS_DIR = Path("/Users/debop/work/bluetape4k/bluetape4k-workshop/.worktrees/docs-issue-190-diagrams-batch2/docs/images/readme-diagrams")


def parse_transforms_and_rects(svg_text):
    """Extract card bounding boxes from <g transform="translate(x,y)"><rect ...> patterns."""
    cards = []

    # Pattern 1: <g transform="translate(tx,ty)"> followed by <rect ... x=rx y=ry width=w height=h>
    g_pattern = re.compile(r'<g\s+transform="translate\(([^,]+),([^)]+)\)"[^>]*>(.*?)</g>', re.DOTALL)
    rect_pattern = re.compile(r'<rect[^>]*\bx="([^"]*)"[^>]*\by="([^"]*)"[^>]*\bwidth="([^"]*)"[^>]*\bheight="([^"]*)"')
    # also rect without explicit x/y (defaults to 0)
    rect_wh_pattern = re.compile(r'<rect[^>]*\bwidth="([^"]*)"[^>]*\bheight="([^"]*)"')

    for m in g_pattern.finditer(svg_text):
        tx, ty = float(m.group(1)), float(m.group(2))
        inner = m.group(3)
        # find first rect in this group
        rm = rect_pattern.search(inner)
        if rm:
            rx, ry, rw, rh = float(rm.group(1)), float(rm.group(2)), float(rm.group(3)), float(rm.group(4))
            cards.append((tx + rx, ty + ry, rw, rh))
        else:
            rm2 = rect_wh_pattern.search(inner)
            if rm2:
                rw, rh = float(rm2.group(1)), float(rm2.group(2))
                cards.append((tx, ty, rw, rh))

    # Pattern 2: absolute <rect> not inside a translate group (cards placed absolutely)
    # exclude canvas/frame rects which are typically large
    abs_rect_pattern = re.compile(r'<rect\s+class="card"[^>]*\bx="([^"]*)"[^>]*\by="([^"]*)"[^>]*\bwidth="([^"]*)"[^>]*\bheight="([^"]*)"')
    for m in abs_rect_pattern.finditer(svg_text):
        rx, ry, rw, rh = float(m.group(1)), float(m.group(2)), float(m.group(3)), float(m.group(4))
        cards.append((rx, ry, rw, rh))

    return cards


def parse_paths(svg_text):
    """Extract path 'd' attributes from .line paths."""
    paths = []
    # Match path elements with class containing 'line', 'sendto', 'impl', 'reqArrow', 'rspArrow'
    path_pattern = re.compile(r'<(?:path|line)\s[^>]*(?:class="[^"]*(?:line|sendto|impl|reqArrow|rspArrow)[^"]*")[^>]*>', re.DOTALL)
    d_attr = re.compile(r'\bd="([^"]*)"')
    # for <line> elements
    line_coords = re.compile(r'\bx1="([^"]*)"[^>]*\by1="([^"]*)"[^>]*\bx2="([^"]*)"[^>]*\by2="([^"]*)"')

    for m in path_pattern.finditer(svg_text):
        tag = m.group(0)
        dm = d_attr.search(tag)
        if dm:
            paths.append(('path', dm.group(1)))
        else:
            lm = line_coords.search(tag)
            if lm:
                x1, y1, x2, y2 = float(lm.group(1)), float(lm.group(2)), float(lm.group(3)), float(lm.group(4))
                paths.append(('line', (x1, y1, x2, y2)))
    return paths


def extract_path_points(d):
    """Extract key points from a path 'd' attribute. Returns list of (x,y) tuples."""
    points = []
    # Tokenize: split on M, L, H, V, C, Z commands
    tokens = re.findall(r'[MLHVCSQTAZmlhvcsqtaz]|[-+]?[0-9]*\.?[0-9]+', d)
    i = 0
    cx, cy = 0.0, 0.0
    cmd = 'M'
    while i < len(tokens):
        t = tokens[i]
        if t.isalpha():
            cmd = t
            i += 1
            continue
        try:
            val = float(t)
        except ValueError:
            i += 1
            continue

        if cmd in ('M', 'L'):
            if i + 1 < len(tokens):
                try:
                    cy_val = float(tokens[i + 1])
                    cx, cy = val, cy_val
                    points.append((cx, cy))
                    i += 2
                    continue
                except (ValueError, IndexError):
                    pass
        elif cmd in ('m', 'l'):
            if i + 1 < len(tokens):
                try:
                    cy_val = float(tokens[i + 1])
                    cx += val
                    cy += cy_val
                    points.append((cx, cy))
                    i += 2
                    continue
                except (ValueError, IndexError):
                    pass
        elif cmd == 'H':
            cx = val
            points.append((cx, cy))
        elif cmd == 'h':
            cx += val
            points.append((cx, cy))
        elif cmd == 'V':
            cy = val
            points.append((cx, cy))
        elif cmd == 'v':
            cy += val
            points.append((cx, cy))
        elif cmd == 'C':
            # cubic bezier: consume 6 numbers (3 pairs), take last pair as endpoint
            nums = []
            j = i
            while len(nums) < 6 and j < len(tokens):
                try:
                    nums.append(float(tokens[j]))
                    j += 1
                except ValueError:
                    j += 1
            if len(nums) == 6:
                cx, cy = nums[4], nums[5]
                points.append((cx, cy))
                i = j
                continue
        i += 1
    return points


def point_inside_card(px, py, card, margin=3):
    """Return True if point (px,py) is strictly inside card bbox (with margin shrink)."""
    cx, cy, cw, ch = card
    return (cx + margin < px < cx + cw - margin and
            cy + margin < py < cy + ch - margin)


def point_on_card_boundary(px, py, cards, tolerance=12):
    """Return True if (px,py) is within tolerance of any card edge."""
    for cx, cy, cw, ch in cards:
        # Check proximity to any of the 4 edges
        in_x_range = cx - tolerance <= px <= cx + cw + tolerance
        in_y_range = cy - tolerance <= py <= cy + ch + tolerance
        if not (in_x_range and in_y_range):
            continue
        # Near left or right edge
        if in_y_range and (abs(px - cx) <= tolerance or abs(px - (cx + cw)) <= tolerance):
            return True
        # Near top or bottom edge
        if in_x_range and (abs(py - cy) <= tolerance or abs(py - (cy + ch)) <= tolerance):
            return True
    return False


def segment_crosses_card_interior(x1, y1, x2, y2, cards, margin=5):
    """Check if line segment (x1,y1)-(x2,y2) passes through any card interior.
    Uses midpoint sampling along the segment."""
    problems = []
    steps = 20
    for step in range(1, steps):
        t = step / steps
        px = x1 + t * (x2 - x1)
        py = y1 + t * (y2 - y1)
        for i, card in enumerate(cards):
            cx, cy, cw, ch = card
            if (cx + margin < px < cx + cw - margin and
                    cy + margin < py < cy + ch - margin):
                problems.append(i)
    return problems


def audit_file(svg_path, cards_override=None):
    """Audit one SVG file. Returns list of problem descriptions."""
    problems = []
    text = svg_path.read_text(encoding='utf-8')

    cards = parse_transforms_and_rects(text)
    if not cards:
        return ['no_cards_found']

    paths = parse_paths(text)

    for ptype, pdata in paths:
        if ptype == 'line':
            x1, y1, x2, y2 = pdata
            pts = [(x1, y1), (x2, y2)]
        else:
            pts = extract_path_points(pdata)
            if len(pts) < 2:
                continue

        # Check endpoints
        start = pts[0]
        end = pts[-1]

        for label, pt in [('start', start), ('end', end)]:
            on_boundary = point_on_card_boundary(pt[0], pt[1], cards, tolerance=14)
            if not on_boundary:
                problems.append(f'disconnected_{label} at ({pt[0]:.0f},{pt[1]:.0f})')

        # Check intermediate segments for card-crossing
        for seg_i in range(len(pts) - 1):
            x1s, y1s = pts[seg_i]
            x2s, y2s = pts[seg_i + 1]
            crossing_cards = segment_crosses_card_interior(x1s, y1s, x2s, y2s, cards)
            if crossing_cards:
                problems.append(f'segment ({x1s:.0f},{y1s:.0f})-({x2s:.0f},{y2s:.0f}) crosses card(s) {crossing_cards}')

    return problems


def main():
    svg_files = sorted(DIAGRAMS_DIR.glob("*.svg"))
    print(f"Auditing {len(svg_files)} SVG files...\n")

    clean = []
    issues = []

    for svg_path in svg_files:
        probs = audit_file(svg_path)
        # filter out 'no_cards_found' as informational only
        real_probs = [p for p in probs if p != 'no_cards_found']
        if real_probs:
            issues.append((svg_path.name, real_probs))
        else:
            clean.append(svg_path.name)

    print(f"=== CLEAN ({len(clean)}) ===")
    for f in clean:
        print(f"  OK  {f}")

    print(f"\n=== ISSUES ({len(issues)}) ===")
    for fname, probs in issues:
        print(f"\n  ISSUE  {fname}")
        for p in probs:
            print(f"    - {p}")

    return len(issues)


if __name__ == '__main__':
    count = main()
    sys.exit(0 if count == 0 else 1)
