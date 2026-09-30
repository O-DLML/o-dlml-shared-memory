import pandas as pd
import folium

df = pd.read_csv("clusters.csv")

m = folium.Map(
    location=[df["centro_lat"].mean(), df["centro_lon"].mean()],
    zoom_start=11
)

for _, r in df.iterrows():
    cluster_id = int(r["cluster_id"])
    eventos = int(r["eventos"])
    min_total = float(r["min_total"])

    popup = f"Cluster {cluster_id}<br>events={eventos}<br>min_total={min_total:.2f}"

    folium.CircleMarker(
        location=[r["centro_lat"], r["centro_lon"]],
        radius=max(4, min(20, eventos / 2)),
        popup=popup,
        fill=True
    ).add_to(m)

m.save("clusters_map.html")
print("Generated: clusters_map.html")
