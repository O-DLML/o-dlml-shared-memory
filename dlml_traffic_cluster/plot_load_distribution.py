import sys
import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

CSV = "load_distribution.csv"

df = pd.read_csv(CSV)

PHASE_LABELS = {
    1: "Phase 1\nEvent consolidation",
    2: "Phase 2\nPer-cell density",
    4: "Phase 4\nPer-cluster accumulation",
}

strategies = sorted(df["strategy"].unique())
nps        = sorted(df["np"].unique())
phases     = sorted(df["phase"].unique())

# Average over repetitions
avg = df.groupby(["strategy", "np", "phase", "thread"], as_index=False)["items"].mean()

for strategy in strategies:
    dfS = avg[avg["strategy"] == strategy]

    ncols = len(nps)
    nrows = len(phases)
    fig, axes = plt.subplots(nrows, ncols,
                             figsize=(max(4, 3 * ncols), 3.5 * nrows),
                             squeeze=False)
    fig.suptitle(f"Load distribution — strategy: {strategy}", fontsize=13)

    for ri, phase in enumerate(phases):
        for ci, np_val in enumerate(nps):
            ax = axes[ri][ci]
            subset = dfS[(dfS["phase"] == phase) & (dfS["np"] == np_val)]
            if subset.empty:
                ax.set_visible(False)
                continue

            threads = subset["thread"].values
            items   = subset["items"].values
            mean    = items.mean()
            cv      = items.std() / mean if mean > 0 else 0.0

            colors = ["steelblue" if v >= mean else "salmon" for v in items]
            ax.bar(threads, items, color=colors, edgecolor="black", linewidth=0.6)
            ax.axhline(mean, color="black", linestyle="--", linewidth=1,
                       label=f"mean={mean:.0f}")
            ax.set_title(
                f"{PHASE_LABELS.get(phase, f'Phase {phase}')}\nnp={np_val}  CV={cv:.3f}",
                fontsize=9
            )
            ax.set_xlabel("Thread", fontsize=8)
            ax.set_ylabel("Items processed", fontsize=8)
            ax.set_xticks(threads)
            ax.legend(fontsize=7)
            ax.tick_params(labelsize=7)

    plt.tight_layout()
    out_pdf = f"load_dist_{strategy}.pdf"
    out_png = f"load_dist_{strategy}.png"
    plt.savefig(out_pdf)
    plt.savefig(out_png, dpi=150)
    plt.close()
    print(f"Saved: {out_pdf}, {out_png}")

# ── Summary of coefficients of variation ──────────────────────────────────────
print()
print(f"{'strategy':<16} {'np':>4} {'phase':>5} {'CV':>8}  {'max/min':>8}")
for strategy in strategies:
    for np_val in nps:
        for phase in phases:
            subset = avg[(avg["strategy"] == strategy) &
                         (avg["np"]       == np_val)   &
                         (avg["phase"]    == phase)]
            if subset.empty:
                continue
            items = subset["items"].values
            mean  = items.mean()
            cv    = items.std() / mean if mean > 0 else 0.0
            ratio = items.max() / items.min() if items.min() > 0 else float("inf")
            print(f"{strategy:<16} {np_val:>4} {phase:>5} {cv:>8.4f}  {ratio:>8.4f}")
