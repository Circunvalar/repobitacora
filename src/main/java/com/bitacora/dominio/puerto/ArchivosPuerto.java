package com.bitacora.dominio.puerto;

import com.bitacora.dominio.modelo.CsvLeido;
import com.bitacora.dominio.modelo.RegistroInvalido;

import java.util.List;

/** Acceso a la carpeta de CSV (local o la carpeta sincronizada de SharePoint). */
public interface ArchivosPuerto {

    record InfoArchivo(String nombre, long tamanoBytes, java.time.LocalDateTime modificado) {}

    List<InfoArchivo> listarCsv();

    CsvLeido leer(String nombre);

    int contarFilas(String nombre);

    String huella(String nombre);

    /** Escribe las filas rechazadas con su motivo en la carpeta de rechazados y devuelve la ruta. */
    String escribirRechazados(String nombre, List<String> encabezado, List<RegistroInvalido> invalidos, char separador);

    void moverAProcesados(String nombre);
}
