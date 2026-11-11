package com.bitacora.dominio.modelo;

import java.text.Normalizer;
import java.util.Locale;

/** Reglas sobre el nombre de la lista, independientes de la campaña. */
public final class NombreLista {
    private NombreLista() {}

    /** Nombre de lista a partir del archivo: sin extensión. */
    public static String desdeArchivo(String archivo) {
        int punto = archivo.lastIndexOf('.');
        return punto > 0 ? archivo.substring(0, punto) : archivo;
    }

    /**
     * Base de la lista sin la fecha final, para reconocer remarcaciones.
     * MASIVO_REINTEGRA_INCUMPLIDOS_WOLKVOX_20261110 → MASIVO_REINTEGRA_INCUMPLIDOS_WOLKVOX
     */
    public static String base(String nombreLista) {
        String sinFecha = desdeArchivo(nombreLista).replaceAll("[_\\- ]?\\d{8}$", "");
        return Normalizer.normalize(sinFecha, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .trim();
    }
}
