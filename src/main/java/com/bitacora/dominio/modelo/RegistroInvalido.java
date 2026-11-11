package com.bitacora.dominio.modelo;

import java.util.Map;

/** Fila rechazada antes de enviarla a Wolkvox, con el motivo. La línea cuenta el encabezado como 1. */
public record RegistroInvalido(int linea, String motivo, Map<String, String> fila) {}
