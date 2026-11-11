package com.bitacora.web;

import com.bitacora.infraestructura.persistencia.CargueEntidad;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Fila de la bitácora tal como la ve la pantalla. */
public record CargueDto(Long id, LocalDate fecha, LocalTime horaInicioPlan, LocalTime horaFinPlan,
                        LocalDateTime inicioCampana, LocalDateTime finCampana, String campanaId, String campanaNombre,
                        String tipoMarcacion, String nombreLista, String estrategia, int remarcacion, String jefe,
                        String nombreEstrategia, String observacion, int registrosArchivo, int registrosNoValidos,
                        int registrosCargados, int rechazadosWolkvox, Integer totalGestionado, Integer porcentajeGestion,
                        String descargaInforme, String estado, String error, String archivoRechazados) {

    public static CargueDto de(CargueEntidad c) {
        return new CargueDto(c.id, c.fecha, c.horaInicioPlan, c.horaFinPlan, c.inicioCampana, c.finCampana,
                c.campanaId, c.campanaNombre, c.tipoMarcacion != null ? c.tipoMarcacion.name() : null, c.nombreLista,
                c.estrategia.name(), c.remarcacion, c.jefe, c.nombreEstrategia, c.observacion, c.registrosArchivo,
                c.registrosNoValidos, c.registrosCargados, c.rechazadosWolkvox, c.totalGestionado,
                c.porcentajeGestion(), c.descargaInforme.name(), c.estado.name(), c.error, c.archivoRechazados);
    }
}
