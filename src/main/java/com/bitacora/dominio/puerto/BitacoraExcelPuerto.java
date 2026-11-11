package com.bitacora.dominio.puerto;

import java.nio.file.Path;
import java.util.List;

/** Escribe la bitácora en Excel con el encabezado de siempre. */
public interface BitacoraExcelPuerto {

    record FilaBitacora(String fecha, String horaInicio, String horaFinal, String idCampana, String nombreLista,
                        String campana, String estrategia, Integer remarcaciones, String jefe,
                        Integer registrosCargados, Integer totalGestionado, String estrategiaMarcacion,
                        String porcentajeGestion, String descargaInforme, String nombreEstrategia,
                        String observacion, Integer registrosNoValidos, String huella, String horarioPlaneado) {}

    Path escribir(List<FilaBitacora> filas);

    byte[] generar(List<FilaBitacora> filas);
}
