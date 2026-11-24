package com.bitacora.dominio.puerto;

import com.bitacora.dominio.modelo.Campana;
import com.bitacora.dominio.modelo.Conexion;
import com.bitacora.dominio.modelo.MetricaHora;
import com.bitacora.dominio.modelo.ResultadoLote;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lo que la aplicación necesita de Wolkvox Manager. Cada llamada recibe la conexión (servidor + token)
 * de la campaña grande con la que se trabaja.
 */
public interface WolkvoxPuerto {

    /** Todas las campañas del servidor con su estado. Sirve también para validar el token. */
    List<Campana> campanas(Conexion c);

    /** Una campaña por su número, o vacío si no existe en ese servidor. */
    Optional<Campana> campana(Conexion c, String campanaId);

    /** Envía un lote (máx. 10.000 registros por consumo, según límites de Wolkvox). */
    ResultadoLote cargarRegistros(Conexion c, Campana campana, List<Map<String, String>> lote);

    void iniciarCampana(Conexion c, Campana campana);

    void detenerCampana(Conexion c, Campana campana);

    /** Cortes por hora entre desde (incluido) y hasta (excluido). */
    List<MetricaHora> metricasPorHora(Conexion c, Campana campana, LocalDateTime desde, LocalDateTime hasta);

    /** Llamadas salientes gestionadas por agentes en la campaña dentro del rango. */
    int gestionados(Conexion c, Campana campana, LocalDateTime desde, LocalDateTime hasta);
}
