import os
import json
import shutil
from pathlib import Path

import cv2
from ultralytics import YOLO


# =========================================================
# Configuration
# =========================================================
CARPETA_ENTRADA = "/home/local/D1_V2_B_2"
CARPETA_SALIDA = "/home/local/salida"
CARPETA_CON = os.path.join(CARPETA_SALIDA, "con_auto")
CARPETA_SIN = os.path.join(CARPETA_SALIDA, "sin_auto")
CARPETA_DEBUG = os.path.join(CARPETA_SALIDA, "debug")

MODELO_YOLO = "yolov8n.pt"
EXTENSIONES_VALIDAS = (".jpg", ".jpeg", ".png", ".bmp", ".webp")

CLASES_VEHICULO = {"car", "truck", "bus", "motorcycle"}

# Main thresholds
#CONFIANZA_MINIMA = 0.45

# "Highly visible" vehicle
#AREA_MINIMA_BBOX = 18000         # minimum bbox area
#ANCHO_MINIMO_BBOX = 120          # minimum bbox width
#ALTO_MINIMO_BBOX = 120           # minimum bbox height


CONFIANZA_MINIMA = 0.25

AREA_MINIMA_BBOX = 12000
ANCHO_MINIMO_BBOX = 90
ALTO_MINIMO_BBOX = 90

FRACCION_MINIMA_IMAGEN = 0.015

# A percentage of the image area can also be used
FRACCION_MINIMA_IMAGEN = 0.02    # 2% of the total image area

GUARDAR_DEBUG = True


def crear_carpetas():
    os.makedirs(CARPETA_CON, exist_ok=True)
    os.makedirs(CARPETA_SIN, exist_ok=True)
    if GUARDAR_DEBUG:
        os.makedirs(CARPETA_DEBUG, exist_ok=True)


def area_rect(x1, y1, x2, y2):
    return max(0, x2 - x1) * max(0, y2 - y1)


def dibujar_debug(imagen, detecciones_validas, ruta_salida, clase_final):
    img = imagen.copy()

    color = (0, 255, 0) if clase_final == "con_auto" else (0, 0, 255)

    for det in detecciones_validas:
        x1, y1, x2, y2 = det["bbox"]
        etiqueta = (
            f'{det["clase"]} '
            f'conf={det["confianza"]:.2f} '
            f'area={det["area_bbox"]}'
        )
        cv2.rectangle(img, (x1, y1), (x2, y2), color, 2)
        cv2.putText(
            img,
            etiqueta,
            (x1, max(20, y1 - 8)),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.55,
            color,
            2
        )

    cv2.putText(
        img,
        f"CLASE: {clase_final}",
        (20, 40),
        cv2.FONT_HERSHEY_SIMPLEX,
        1.0,
        color,
        3
    )

    cv2.imwrite(ruta_salida, img)


def clasificar_imagen(modelo, ruta_imagen):
    imagen = cv2.imread(ruta_imagen)
    if imagen is None:
        return {
            "archivo": os.path.basename(ruta_imagen),
            "clase": "error",
            "motivo": "Could not read the image",
            "detecciones_validas": []
        }

    alto, ancho = imagen.shape[:2]
    area_imagen = ancho * alto

    resultados = modelo(ruta_imagen, verbose=False)

    detecciones_validas = []
    con_auto = False

    for resultado in resultados:
        if resultado.boxes is None:
            continue

        for box in resultado.boxes:
            cls_id = int(box.cls[0].item())
            confianza = float(box.conf[0].item())
            clase = resultado.names[cls_id]

            if clase not in CLASES_VEHICULO:
                continue

            if confianza < CONFIANZA_MINIMA:
                continue

            x1, y1, x2, y2 = box.xyxy[0].tolist()
            x1, y1, x2, y2 = int(x1), int(y1), int(x2), int(y2)
            
            print(f"\nFile: {os.path.basename(ruta_imagen)}")
            print(f"Detected class: {clase}, confidence: {confianza:.3f}")
            print(f"BBox: ({x1}, {y1}, {x2}, {y2})")

            ancho_bbox = max(0, x2 - x1)
            alto_bbox = max(0, y2 - y1)
            area_bbox = ancho_bbox * alto_bbox
            fraccion_imagen = area_bbox / area_imagen if area_imagen > 0 else 0

            TOCA_BORDE = (
                x1 <= 15 or
                y1 <= 15 or
                x2 >= ancho - 15 or
                y2 >= alto - 15
            )

            VISIBLE_RECORTADO = TOCA_BORDE and (
                area_bbox >= 10000 or
                fraccion_imagen >= 0.015 or
                ancho_bbox >= 90 or
                alto_bbox >= 90
            )
		# ================================
		# Rule 1: size
		# ================================
            visible_por_tamano = (
                area_bbox >= AREA_MINIMA_BBOX or
                fraccion_imagen >= FRACCION_MINIMA_IMAGEN or
                (ancho_bbox >= ANCHO_MINIMO_BBOX and alto_bbox >= ALTO_MINIMO_BBOX)
            )

		# ================================
		# Rule 2: closeness to the center
		# ================================
            centro_x = (x1 + x2) / 2
            centro_y = (y1 + y2) / 2

            cx_img = ancho / 2
            cy_img = alto / 2

            dist_centro = ((centro_x - cx_img)**2 + (centro_y - cy_img)**2)**0.5

            CERCA_DEL_CENTRO = dist_centro < 180  # this value can be tuned

		# ================================
		# Combined final rule
		# ================================
            visible_final = visible_por_tamano or CERCA_DEL_CENTRO or VISIBLE_RECORTADO

            if visible_final:
                detecciones_validas.append({
                    "clase": clase,
                    "confianza": confianza,
                    "bbox": [x1, y1, x2, y2],
                    "ancho_bbox": ancho_bbox,
                    "area_bbox": area_bbox,
        		  "dist_centro": dist_centro
                })
                con_auto = True

    clase_final = "con_auto" if con_auto else "sin_auto"

    return {
        "archivo": os.path.basename(ruta_imagen),
        "clase": clase_final,
        "detecciones_validas": detecciones_validas
    }


def procesar_lote():
    crear_carpetas()
    modelo = YOLO(MODELO_YOLO)

    resultados_json = []

    archivos = sorted(os.listdir(CARPETA_ENTRADA))
    for archivo in archivos:
        ruta = os.path.join(CARPETA_ENTRADA, archivo)

        if not os.path.isfile(ruta):
            continue

        if not archivo.lower().endswith(EXTENSIONES_VALIDAS):
            continue

        resultado = clasificar_imagen(modelo, ruta)
        resultados_json.append(resultado)

        if resultado["clase"] == "con_auto":
            destino = os.path.join(CARPETA_CON, archivo)
        elif resultado["clase"] == "sin_auto":
            destino = os.path.join(CARPETA_SIN, archivo)
        else:
            print(f"[ERROR] {archivo}: {resultado.get('motivo', 'unknown')}")
            continue

        shutil.copy2(ruta, destino)
        print(f'{archivo} -> {resultado["clase"]}')

        if GUARDAR_DEBUG:
            imagen = cv2.imread(ruta)
            debug_path = os.path.join(CARPETA_DEBUG, archivo)
            dibujar_debug(
                imagen,
                resultado["detecciones_validas"],
                debug_path,
                resultado["clase"]
            )

    ruta_json = os.path.join(CARPETA_SALIDA, "resultado.json")
    with open(ruta_json, "w", encoding="utf-8") as f:
        json.dump(resultados_json, f, ensure_ascii=False, indent=2)

    print("\nProcessing finished.")
    print(f"JSON results: {ruta_json}")


if __name__ == "__main__":
    procesar_lote()
