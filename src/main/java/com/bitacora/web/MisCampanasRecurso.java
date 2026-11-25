package com.bitacora.web;

import com.bitacora.aplicacion.OperacionServicio;
import com.bitacora.aplicacion.OperacionServicio.CampanaGuardada;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/** Campañas guardadas: cada una con su número, su servidor y su token. */
@Path("/api/mis-campanas")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MisCampanasRecurso {

    public record CambioToken(String servidor, String token) {}

    private final OperacionServicio servicio;

    public MisCampanasRecurso(OperacionServicio servicio) {
        this.servicio = servicio;
    }

    @GET
    public List<CampanaGuardada> listar() {
        return servicio.campanasGuardadas();
    }

    /** Guarda la campaña después de probar el token y comprobar que existe en ese servidor. */
    @POST
    public CampanaGuardada guardar(OperacionServicio.NuevaCampana d) {
        return servicio.guardarCampana(d);
    }

    @PUT
    @Path("/{id}")
    public CampanaGuardada cambiarToken(@PathParam("id") long id, CambioToken c) {
        return servicio.cambiarToken(id, c == null ? null : c.servidor(), c == null ? null : c.token());
    }

    @POST
    @Path("/{id}/probar")
    public CampanaGuardada probar(@PathParam("id") long id) {
        return servicio.probarCampana(id);
    }

    /** Si la campaña ya tiene cargues se archiva y la bitácora la conserva; si no, se borra. */
    @DELETE
    @Path("/{id}")
    public Map<String, String> quitar(@PathParam("id") long id) {
        return Map.of("resultado", servicio.eliminar(id));
    }
}
