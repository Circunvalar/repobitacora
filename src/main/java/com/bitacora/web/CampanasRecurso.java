package com.bitacora.web;

import com.bitacora.aplicacion.AvisosCampanas;
import com.bitacora.aplicacion.CampanaServicio;
import com.bitacora.dominio.modelo.Campana;
import com.bitacora.infraestructura.persistencia.CampanaEstadoEntidad;
import com.bitacora.infraestructura.persistencia.EventoCampanaEntidad;
import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/campanas")
@Produces(MediaType.APPLICATION_JSON)
public class CampanasRecurso {

    private final CampanaServicio campanas;
    private final AvisosCampanas avisos;

    public CampanasRecurso(CampanaServicio campanas, AvisosCampanas avisos) {
        this.campanas = campanas;
        this.avisos = avisos;
    }

    /** Campañas que existen en el servidor de Wolkvox, con su estado (activa/detenida). */
    @GET
    public List<Campana> listar(@QueryParam("refrescar") boolean refrescar) {
        return campanas.listar(refrescar);
    }

    /** Último estado conocido de cada campaña y la última revisión. */
    @GET
    @Path("/estado")
    public Map<String, Object> estado() {
        Map<String, Object> r = new HashMap<>();
        List<CampanaEstadoEntidad> e = campanas.estados();
        r.put("campanas", e);
        r.put("ultimoEscaneo", campanas.ultimoEscaneo());
        return r;
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
    @Path("/{id}/iniciar")
    public Map<String, LocalDateTime> iniciar(@PathParam("id") String id) {
        return Map.of("inicio", campanas.iniciar(id, "APP"));
    }

    @POST
    @Path("/{id}/detener")
    public Map<String, LocalDateTime> detener(@PathParam("id") String id) {
        return Map.of("fin", campanas.detener(id, "APP"));
    }
}
