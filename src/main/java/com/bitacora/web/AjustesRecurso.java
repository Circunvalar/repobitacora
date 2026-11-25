package com.bitacora.web;

import com.bitacora.aplicacion.AjustesServicio;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/** Carpeta de bases elegida desde la app y explorador de carpetas del equipo. */
@Path("/api/ajustes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AjustesRecurso {

    public record CambioCarpeta(String ruta) {}

    private final AjustesServicio ajustes;

    public AjustesRecurso(AjustesServicio ajustes) {
        this.ajustes = ajustes;
    }

    @GET
    @Path("/carpeta-bases")
    public Map<String, String> carpetaBases() {
        return Map.of("ruta", ajustes.carpetaBases());
    }

    @PUT
    @Path("/carpeta-bases")
    public Map<String, String> cambiarCarpetaBases(CambioCarpeta c) {
        return Map.of("ruta", ajustes.cambiarCarpetaBases(c == null ? null : c.ruta()));
    }

    /** Subcarpetas de una ruta (sin ruta: unidades del equipo y la carpeta personal). */
    @GET
    @Path("/explorar")
    public AjustesServicio.Explorador explorar(@QueryParam("ruta") String ruta) {
        return ajustes.explorar(ruta);
    }
}
