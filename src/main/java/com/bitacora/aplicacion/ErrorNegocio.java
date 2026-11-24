package com.bitacora.aplicacion;

/** Error de uso explicado para la pantalla: qué pasó y qué hacer. */
public class ErrorNegocio extends RuntimeException {

    private final String titulo;
    private final String sugerencia;
    private final int estado;

    public ErrorNegocio(int estado, String titulo, String detalle, String sugerencia) {
        super(detalle);
        this.estado = estado;
        this.titulo = titulo;
        this.sugerencia = sugerencia;
    }

    public static ErrorNegocio datos(String titulo, String detalle, String sugerencia) {
        return new ErrorNegocio(400, titulo, detalle, sugerencia);
    }

    public static ErrorNegocio noEncontrado(String titulo, String detalle, String sugerencia) {
        return new ErrorNegocio(404, titulo, detalle, sugerencia);
    }

    public static ErrorNegocio conflicto(String titulo, String detalle, String sugerencia) {
        return new ErrorNegocio(409, titulo, detalle, sugerencia);
    }

    public String titulo() { return titulo; }
    public String sugerencia() { return sugerencia; }
    public int estado() { return estado; }
}
