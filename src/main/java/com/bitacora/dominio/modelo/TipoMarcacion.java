package com.bitacora.dominio.modelo;

/** Tipos de campaña de Wolkvox que maneja la operación. */
public enum TipoMarcacion {
    PREDICTIVO, PREVIEW;

    /** Valor que espera la API de Wolkvox en el parámetro type_campaign. */
    public String valorApi() {
        return this == PREDICTIVO ? "predictive" : "preview";
    }
}
