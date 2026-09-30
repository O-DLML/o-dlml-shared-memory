import sys
import json
import os
import cv2
import shutil
from pathlib import Path
from ultralytics import YOLO

MODELO_YOLO = "yolov8n.pt"
CLASES_VEHICULO = {"car", "truck", "bus", "motorcycle"}

CONFIANZA_MINIMA = 0.35
AREA_MINIMA_BBOX = 12000
ANCHO_MINIMO_BBOX = 90
ALTO_MINIMO_BBOX = 90
FRACCION_MINIMA_IMAGEN = 0.015
DISTANCIA_MAX_CENTRO = 180

CARPETA_SALIDA = "/home/local/salida"
CARPETA_CON = os.path.join(CARPETA_SALIDA, "con_auto")
CARPETA_SIN = os.path.join(CARPETA_SALIDA, "sin_auto")

def clasificar_imagen(modelo, ruta_imagen):
    imagen = cv2.imread(ruta_imagen)
    if imagen is None:
        return {
            "ruta": ruta_imagen,
            "clase": "error",
            "motivo": "Could not read the image"
        }

    alto, ancho = imagen.shape[:2]
    area_imagen = ancho * alto

    resultados = modelo(ruta_imagen, verbose=False)
    con_auto = False

    for resultado in resultados:
        if resultado.boxes is None:
            continue

        for box in resultado.boxes:
            cls_id = int(box.cls[0].item())
            confianza = float(box.conf[0].item())
            clase = resultado.names[cls_id]

            x1, y1, x2, y2 = box.xyxy[0].tolist()
            x1, y1, x2, y2 = int(x1), int(y1), int(x2), int(y2)

            if clase not in CLASES_VEHICULO:
                continue

            if confianza < CONFIANZA_MINIMA:
                continue

            ancho_bbox = max(0, x2 - x1)
            alto_bbox = max(0, y2 - y1)
            area_bbox = ancho_bbox * alto_bbox
            fraccion_imagen = area_bbox / area_imagen if area_imagen > 0 else 0

            visible_por_tamano = (
                area_bbox >= AREA_MINIMA_BBOX or
                fraccion_imagen >= FRACCION_MINIMA_IMAGEN or
                (ancho_bbox >= ANCHO_MINIMO_BBOX and alto_bbox >= ALTO_MINIMO_BBOX)
            )

            centro_x = (x1 + x2) / 2
            centro_y = (y1 + y2) / 2
            cx_img = ancho / 2
            cy_img = alto / 2

            dist_centro = ((centro_x - cx_img) ** 2 + (centro_y - cy_img) ** 2) ** 0.5
            cerca_del_centro = dist_centro < DISTANCIA_MAX_CENTRO

            toca_borde = (
                x1 <= 15 or
                y1 <= 15 or
                x2 >= ancho - 15 or
                y2 >= alto - 15
            )

            visible_recortado = toca_borde and (
                area_bbox >= 10000 or
                fraccion_imagen >= 0.015 or
                ancho_bbox >= 90 or
                alto_bbox >= 90
            )

            visible_final = visible_por_tamano or cerca_del_centro or visible_recortado

            if visible_final:
                con_auto = True
                break

        if con_auto:
            break

    return {
        "ruta": ruta_imagen,
        "clase": "con_auto" if con_auto else "sin_auto"
    }

def crear_carpetas_salida():
    os.makedirs(CARPETA_CON, exist_ok=True)
    os.makedirs(CARPETA_SIN, exist_ok=True)
    
def main():
    if len(sys.argv) != 3:
        print("Usage: python3 clasificar_lote.py input_batch.json output_result.json")
        sys.exit(1)

    archivo_entrada = sys.argv[1]
    archivo_salida = sys.argv[2]

    crear_carpetas_salida()

    with open(archivo_entrada, "r", encoding="utf-8") as f:
        lote = json.load(f)

    modelo = YOLO(MODELO_YOLO)

    resultados = []
    for item in lote:
        ruta = item["ruta"]
        resultado = clasificar_imagen(modelo, ruta)
        resultados.append(resultado)

        if resultado["clase"] == "con_auto":
            destino = os.path.join(CARPETA_CON, os.path.basename(ruta))
            shutil.copy2(ruta, destino)
        elif resultado["clase"] == "sin_auto":
            destino = os.path.join(CARPETA_SIN, os.path.basename(ruta))
            shutil.copy2(ruta, destino)

    with open(archivo_salida, "w", encoding="utf-8") as f:
        json.dump(resultados, f, ensure_ascii=False, indent=2)

    print(f"Batch processed: {archivo_entrada} -> {archivo_salida}")


if __name__ == "__main__":
    main()
