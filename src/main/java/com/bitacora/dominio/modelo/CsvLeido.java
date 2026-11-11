package com.bitacora.dominio.modelo;

import java.util.List;
import java.util.Map;

/** Contenido de un CSV: encabezado original y filas como mapa columna → valor (en orden). */
public record CsvLeido(List<String> encabezado, List<Map<String, String>> filas, char separador) {}
