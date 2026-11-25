package com.bitacora.dominio.modelo;

import java.util.List;
import java.util.Optional;

/**
 * La plantilla de cargue de Wolkvox dice qué tipo de lista es: la predictiva trae 36 columnas
 * (termina en tel_extra, email, recall) y la preview 35.
 */
public final class PlantillaWolkvox {

    public static final int COLUMNAS_PREDICTIVA = 36;
    public static final int COLUMNAS_PREVIEW = 35;

    private PlantillaWolkvox() {}

    public static int columnas(List<String> encabezado) {
        int n = encabezado.size();
        while (n > 0 && (encabezado.get(n - 1) == null || encabezado.get(n - 1).isBlank())) n--; // columnas vacías al final
        return n;
    }

    public static Optional<TipoMarcacion> detectar(List<String> encabezado) {
        return switch (columnas(encabezado)) {
            case COLUMNAS_PREDICTIVA -> Optional.of(TipoMarcacion.PREDICTIVO);
            case COLUMNAS_PREVIEW -> Optional.of(TipoMarcacion.PREVIEW);
            default -> Optional.empty();
        };
    }

    public static String nombre(TipoMarcacion t) {
        return t == TipoMarcacion.PREVIEW ? "Preview" : "Predictiva";
    }
}
