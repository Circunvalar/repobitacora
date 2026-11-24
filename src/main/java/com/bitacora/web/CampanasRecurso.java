package com.bitacora.web;

import com.bitacora.aplicacion.AvisosCampanas;
import com.bitacora.aplicacion.CampanaServicio;
import com.bitacora.infraestructura.persistencia.EventoCampanaEntidad;
import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Estado en vivo de las campañas registradas: escaneo, historial, avisos en tiempo real e iniciar/detener. */
@Path("/api/campanas")
@Produces(MediaType.APPLICATION_JSON)
public class CampanasRecurso {

    private final CampanaServicio campanas;
    private final AvisosCampanas avisos;

    public CampanasRecurso(CampanaServicio campanas, AvisosCampanas avisos) {
        this.campanas = campanas;
        this.avisos = avisos;
    }

    @GET
    @Path("/ultimo-escaneo")
    public CampanaServicio.ResultadoEscaneo ultimoEscaneo() {
        return campanas.ultimoEscaneo();
    }

    /** Fuerza el escaneo de campañas activas/inactivas (normalmente corre solo cada 5 minutos). */
    @POST
    @Path("/escanear")
    public CampanaServicio.ResultadoEscaneo escanear() {
        return campanas.escanear();
    }

    /** Historial de activaciones y detenciones de un día (más reciente primero). */
    @GET
    @Path("/historial")
    public List<EventoCampanaEntidad> historial(@QueryParam("fecha") LocalDate fecha) {
        LocalDate f = fecha != null ? fecha : LocalDate.now();
        return campanas.historial(f.atStartOfDay(), f.plusDays(1).atStartOfDay());
    }

    /** Avisos en tiempo real (Server-Sent Events). */
    @GET
    @Path("/eventos")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<AvisosCampanas.Aviso> eventos() {
        return avisos.suscribir();
    }

    @POST
    @Path("/{operacion}/{id}/iniciar")
    public Map<String, LocalDateTime> iniciar(@PathParam("operacion") long operacion, @PathParam("id") String id) {
        return Map.of("inicio", campanas.iniciar(operacion, id, "APP"));
    }

    @POST
    @Path("/{operacion}/{id}/detener")
    public Map<String, LocalDateTime> detener(@PathParam("operacion") long operacion, @PathParam("id") String id) {
        return Map.of("fin", campanas.detener(operacion, id, "APP"));
    }
}
