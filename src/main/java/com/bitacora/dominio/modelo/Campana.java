package com.bitacora.dominio.modelo;

/** Campaña tal como la expone Wolkvox. Es genérica: sirve para cualquier campaña de la operación. */
public record Campana(String id, String nombre, TipoMarcacion tipo) {}
