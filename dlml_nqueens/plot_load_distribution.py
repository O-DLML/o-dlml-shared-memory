#!/usr/bin/env python3
"""
Plots the load distribution (states processed per thread) of the N-queens solver.

Usage:
    python3 plot_load_distribution.py [load_distribution.csv]

Reads load_distribution.csv with columns: strategy,tam,np,rep,thread,items
Produces one PNG per (strategy, tam, np) combination.
"""

import sys
import csv
import os
from collections import defaultdict

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import numpy as np
except ImportError:
    print("ERROR: matplotlib and numpy are required (pip install matplotlib numpy)")
    sys.exit(1)

CSV_FILE = sys.argv[1] if len(sys.argv) > 1 else "load_distribution.csv"

if not os.path.exists(CSV_FILE):
    print(f"ERROR: {CSV_FILE} not found")
    sys.exit(1)

# Structure: (strategy, tam, np) -> thread -> [items rep1, items rep2, ...]
data = defaultdict(lambda: defaultdict(list))

with open(CSV_FILE, newline="") as f:
    reader = csv.DictReader(f)
    for row in reader:
        key = (row["strategy"], int(row["tam"]), int(row["np"]))
        hilo = int(row["thread"])
        datos = int(row["items"])
        data[key][hilo].append(datos)

if not data:
    print("The CSV is empty or has no distribution data.")
    sys.exit(0)

os.makedirs("plots", exist_ok=True)

for (strategy, tam, np_val), hilos_data in sorted(data.items()):
    hilos = sorted(hilos_data.keys())
    medias = [np.mean(hilos_data[h]) for h in hilos]
    stds   = [np.std(hilos_data[h])  for h in hilos]
    total  = sum(medias)
    porcentajes = [100 * m / total if total > 0 else 0 for m in medias]

    fig, axes = plt.subplots(1, 2, figsize=(12, 5))
    fig.suptitle(f"Load distribution — strategy={strategy}  TAM={tam}  np={np_val}",
                 fontsize=13)

    # Left: absolute states per thread
    ax = axes[0]
    bars = ax.bar(hilos, medias, yerr=stds, capsize=4,
                  color="steelblue", edgecolor="black", alpha=0.85)
    ax.axhline(total / np_val, color="red", linestyle="--", linewidth=1.2,
               label=f"ideal average ({total/np_val:.0f})")
    ax.set_xlabel("Thread")
    ax.set_ylabel("States processed (average)")
    ax.set_title("States per thread")
    ax.set_xticks(hilos)
    ax.legend()
    for bar, m in zip(bars, medias):
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + max(stds or [0]) * 0.05,
                f"{m:.0f}", ha="center", va="bottom", fontsize=9)

    # Right: percentage of the total
    ax2 = axes[1]
    bars2 = ax2.bar(hilos, porcentajes, color="darkorange", edgecolor="black", alpha=0.85)
    ax2.axhline(100 / np_val, color="red", linestyle="--", linewidth=1.2,
                label=f"ideal split ({100/np_val:.1f}%)")
    ax2.set_xlabel("Thread")
    ax2.set_ylabel("% of total states")
    ax2.set_title("Load percentage per thread")
    ax2.set_xticks(hilos)
    ax2.set_ylim(0, max(porcentajes) * 1.2 if porcentajes else 100)
    ax2.legend()
    for bar, p in zip(bars2, porcentajes):
        ax2.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.5,
                 f"{p:.1f}%", ha="center", va="bottom", fontsize=9)

    plt.tight_layout()
    fname = f"plots/load_{strategy}_tam{tam}_np{np_val}.png"
    plt.savefig(fname, dpi=150)
    plt.close()
    print(f"  Saved: {fname}")

print(f"\nTotal plots generated: {len(data)}")
