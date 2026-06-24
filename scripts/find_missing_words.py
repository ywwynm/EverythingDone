#!/usr/bin/env python3
"""Find frequently used words NOT in WORD_MAP."""
from collections import Counter
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).parent))

from word_map_data import WORD_MAP

TSV_PATH = Path(r"E:\projects\EverythingDone\app\src\main\assets\color_names\meodai_color_names_14_38_0.tsv")

with open(TSV_PATH, 'r', encoding='utf-8') as f:
    lines = f.readlines()

word_counts = Counter()
for line in lines[1:]:
    line = line.strip()
    if not line:
        continue
    parts = line.split('\t')
    if len(parts) < 3:
        continue
    for w in parts[1].split(' '):
        w = w.strip("(),;:'\"!?.").lower()
        if w and w not in WORD_MAP and not w.isdigit():
            word_counts[w] += 1

output_path = Path(r"E:\projects\EverythingDone\scripts\missing_words.txt")
with open(output_path, 'w', encoding='utf-8') as f:
    for word, count in word_counts.most_common():
        if count >= 3:
            f.write(f"{word}\t{count}\n")

print(f"Missing words (freq >= 3): {sum(1 for _, c in word_counts.most_common() if c >= 3)}")
print(f"Total missing: {len(word_counts)}")
print(f"Written to {output_path}")
