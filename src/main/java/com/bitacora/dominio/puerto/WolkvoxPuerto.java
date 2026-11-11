package com.bitacora.dominio.puerto;

import com.bitacora.dominio.modelo.Campana;
import com.bitacora.dominio.modelo.MetricaHora;
import com.bitacora.dominio.modelo.ResultadoLote;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Lo que la aplicación necesita de Wolkvox. El dominio solo conoce este contrato;
 * el adaptador simulado y el HTTP lo implementan.
 */
public interface WolkvoxPuerto {

    List<Campana> campanas();

    /** Envía un lote (máx. 10.000 registros por consumo, según límites de Wolkvox). */
    ResultadoLote cargarRegistros(Campana campana, List<Map<String, String>> lote);

    void iniciarCampana(Campana campana);

    void detenerCampana(Campana campana);

    /** Cortes por hora entre desde (incluido) y hasta (excluido). */
    List<MetricaHora> metricasPorHora(Campana campana, LocalDateTime desde, LocalDateTime hasta);

    /** Registros de una lista marcados por el predictivo o el preview en el rango. */
    int gestionadosDeLista(Campana campana, String nombreLista, LocalDateTime desde, LocalDateTime hasta);
}
