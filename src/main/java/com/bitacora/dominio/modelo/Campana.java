package com.bitacora.dominio.modelo;

/**
 * Campaña tal como la expone Wolkvox. Es genérica: sirve para cualquier campaña de la operación.
 * activa y registros vienen del listado de Wolkvox (null si no se conocen).
 */
public record Campana(String id, String nombre, TipoMarcacion tipo, Boolean activa, Integer registros) {

    public Campana(String id, String nombre, TipoMarcacion tipo) {
        this(id, nombre, tipo, null, null);
    }

    public boolean estaActiva() {
        return Boolean.TRUE.equals(activa);
    }
}
