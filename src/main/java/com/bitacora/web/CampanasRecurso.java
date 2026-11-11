package com.bitacora.web;

import com.bitacora.aplicacion.CampanaServicio;
import com.bitacora.dominio.modelo.Campana;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Path("/api/campanas")
@Produces(MediaType.APPLICATION_JSON)
public class CampanasRecurso {

    private final CampanaServicio campanas;

    public CampanasRecurso(CampanaServicio campanas) {
        this.campanas = campanas;
    }

    @GET
    public List<Campana> listar(@QueryParam("refrescar") boolean refrescar) {
        return campanas.listar(refrescar);
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
