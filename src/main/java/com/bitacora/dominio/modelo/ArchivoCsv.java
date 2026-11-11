package com.bitacora.dominio.modelo;

import java.time.LocalDateTime;

/** Un CSV encontrado en la carpeta de entrada. */
public record ArchivoCsv(String nombre, long tamanoBytes, int filas, String huella,
                         LocalDateTime modificado, boolean yaCargado) {}
