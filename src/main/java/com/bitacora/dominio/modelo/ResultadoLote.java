package com.bitacora.dominio.modelo;

/** Respuesta de Wolkvox a un lote de registros enviados. */
public record ResultadoLote(int aceptados, int rechazados, String mensaje) {}
