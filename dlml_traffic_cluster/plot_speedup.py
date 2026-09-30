import pandas as pd
import matplotlib.pyplot as plt

CSV = "speedup_results.csv"
PDF = "speedup_plot.pdf"
PNG = "speedup_plot.png"

df = pd.read_csv(CSV)

# Use only O-DLML rows for parallel speedup
df_odlml = df[df["version"] == "odlml"].copy()

# Average per number of processes
summary = (
    df_odlml
    .groupby("np", as_index=False)
    .agg(
        time_s=("time_s", "mean"),
        time_std=("time_s", "std"),
        speedup=("speedup", "mean"),
        speedup_std=("speedup", "std"),
        efficiency=("efficiency", "mean")
    )
)

print(summary)

# Ideal line
summary["ideal"] = summary["np"]

plt.figure(figsize=(7, 5))

plt.errorbar(
    summary["np"],
    summary["speedup"],
    yerr=summary["speedup_std"],
    marker="o",
    capsize=4,
    label="O-DLML"
)

plt.plot(
    summary["np"],
    summary["ideal"],
    linestyle="--",
    label="Ideal speedup"
)

plt.xlabel("Number of processes")
plt.ylabel("Speedup")
plt.title("Speedup of O-DLML")
plt.grid(True)
plt.legend()
plt.tight_layout()

plt.savefig(PDF)
plt.savefig(PNG, dpi=300)

plt.show()

print(f"PDF generated: {PDF}")
print(f"PNG generated: {PNG}")
