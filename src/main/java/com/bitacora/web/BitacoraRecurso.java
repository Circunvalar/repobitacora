package com.bitacora.web;

import com.bitacora.aplicacion.BitacoraConfig;
import com.bitacora.aplicacion.BitacoraServicio;
import com.bitacora.aplicacion.MetricasServicio;
import com.bitacora.infraestructura.persistencia.CorteHoraEntidad;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class BitacoraRecurso {

    private final BitacoraServicio bitacora;
    private final MetricasServicio metricas;
    private final BitacoraConfig config;

    private final com.bitacora.aplicacion.AjustesServicio ajustes;

    public BitacoraRecurso(BitacoraServicio bitacora, MetricasServicio metricas, BitacoraConfig config,
                           com.bitacora.aplicacion.AjustesServicio ajustes) {
        this.ajustes = ajustes;
        this.bitacora = bitacora;
        this.metricas = metricas;
        this.config = config;
    }

    /** Descarga la bitácora completa en Excel. */
    @GET
    @Path("/bitacora/excel")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public Response excel() {
        return Response.ok(bitacora.excelEnMemoria())
                .header("Content-Disposition", "attachment; filename=\"Bitacora.xlsx\"").build();
    }

    /** Reescribe el Excel configurado en bitacora.excel.ruta. */
    @POST
    @Path("/bitacora/excel/sincronizar")
    public Map<String, String> sincronizar() {
        return Map.of("ruta", bitacora.sincronizarExcel().toString());
    }

    @GET
    @Path("/metricas")
    public List<CorteHoraEntidad> metricas(@QueryParam("fecha") LocalDate fecha) {
        return metricas.delDia(fecha != null ? fecha : LocalDate.now());
    }

    /** Fuerza la recolección de métricas del día (normalmente corre sola cada hora). */
    @POST
    @Path("/metricas/recolectar")
    public Map<String, Integer> recolectar(@QueryParam("fecha") LocalDate fecha) {
        return Map.of("cortes", metricas.recolectar(fecha != null ? fecha : LocalDate.now()));
    }

    /** Hora de gestión seleccionada: listas activas en esa hora, cortes por campaña y activaciones/detenciones. */
    @GET
    @Path("/bitacora/hora")
    public Map<String, Object> hora(@QueryParam("fecha") LocalDate fecha, @QueryParam("hora") int hora) {
        if (hora < 0 || hora > 23) throw new IllegalArgumentException("La hora debe estar entre 0 y 23");
        MetricasServicio.VistaHora v = metricas.hora(fecha != null ? fecha : LocalDate.now(), hora);
        return Map.of("hora", v.hora(), "cargues", v.cargues().stream().map(CargueDto::de).toList(),
                "cortes", v.cortes(), "eventos", v.eventos());
    }

    @GET
    @Path("/catalogos")
    public Map<String, Object> catalogos() {
        return Map.of("jefes", config.catalogos().jefes(),
                "estrategias", List.of("NUEVA", "REMARCACION"),
                "carpetaEntrada", ajustes.carpetaBases(),
                "escaneo", config.campanas().escaneo());
    }
}
