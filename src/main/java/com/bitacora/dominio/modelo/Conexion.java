package com.bitacora.dominio.modelo;

/** Datos para hablar con un servidor de Wolkvox Manager: el número del servidor (wvXXXX) y su token. */
public record Conexion(String servidor, String token) {

    public Conexion {
        servidor = servidor == null ? "" : servidor.trim().replaceFirst("(?i)^https?://", "").replaceFirst("(?i)^wv", "")
                .replaceFirst("(?i)\\.wolkvox\\.com.*$", "");
        token = token == null ? "" : token.trim();
    }

    @Override
    public String toString() {
        return "wv" + servidor; // nunca imprimir el token
    }
}
