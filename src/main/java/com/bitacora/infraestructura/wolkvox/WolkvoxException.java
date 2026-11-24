package com.bitacora.infraestructura.wolkvox;

/**
 * Error al hablar con Wolkvox, ya traducido para la persona que usa la app:
 * un título corto, el detalle que devolvió Wolkvox y qué hacer.
 */
public class WolkvoxException extends RuntimeException {

    public enum Tipo {
        /** Token vacío, inválido o vencido. */
        TOKEN,
        /** Límite diario de consumos, 2 simultáneas, 10.000 registros, licencias. */
        LIMITE,
        /** La campaña u otro dato no existe en ese servidor. */
        NO_EXISTE,
        /** Datos rechazados por Wolkvox. */
        DATOS,
        /** No hay conexión con el servidor (número de servidor errado, sin internet). */
        RED,
        /** Wolkvox respondió con un error propio o está ocupado. */
        SERVIDOR
    }

    private final Tipo tipo;
    private final String titulo;
    private final String sugerencia;
    private final int estadoHttp;
    private final boolean reintentable;

    public WolkvoxException(Tipo tipo, String titulo, String detalle, String sugerencia, int estadoHttp, boolean reintentable, Throwable causa) {
        super(detalle, causa);
        this.tipo = tipo;
        this.titulo = titulo;
        this.sugerencia = sugerencia;
        this.estadoHttp = estadoHttp;
        this.reintentable = reintentable;
    }

    public WolkvoxException(Tipo tipo, String titulo, String detalle, String sugerencia) {
        this(tipo, titulo, detalle, sugerencia, 0, false, null);
    }

    public Tipo tipo() { return tipo; }
    public String titulo() { return titulo; }
    public String sugerencia() { return sugerencia; }
    public int estadoHttp() { return estadoHttp; }
    public boolean reintentable() { return reintentable; }

    /** Texto de una línea para guardar en la bitácora o en el estado de una campaña. */
    public String resumen() {
        return titulo + ": " + getMessage();
    }
}
