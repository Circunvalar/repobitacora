package com.bitacora.web;

import com.bitacora.aplicacion.OperacionServicio;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/** Operaciones (nombre, servidor y token) y las campañas (nombre e ID) que se cargan en cada una. */
@Path("/api/operaciones")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OperacionesRecurso {

    public record NuevaCampana(String campanaId, String nombre) {}

    private final OperacionServicio operaciones;

    public OperacionesRecurso(OperacionServicio operaciones) {
        this.operaciones = operaciones;
    }

    @GET
    public List<OperacionServicio.Vista> listar() {
        return operaciones.listar();
    }

    /** Crea la operación; antes valida el token contra el servidor. */
    @POST
    public OperacionServicio.Vista crear(OperacionServicio.Datos d) {
        return operaciones.crear(d);
    }

    @PUT
    @Path("/{id}")
    public OperacionServicio.Vista editar(@PathParam("id") long id, OperacionServicio.Datos d) {
        return operaciones.editar(id, d);
    }

    @DELETE
    @Path("/{id}")
    public Map<String, String> eliminar(@PathParam("id") long id) {
        return Map.of("resultado", operaciones.eliminar(id));
    }

    /** Prueba el token ahora mismo; el resultado queda en tokenValido / ultimoError. */
    @POST
    @Path("/{id}/validar")
    public OperacionServicio.Vista validar(@PathParam("id") long id) {
        return operaciones.validar(id);
    }

    /** Agrega una campaña por nombre e ID (se comprueba en Wolkvox que el ID exista en ese servidor). */
    @POST
    @Path("/{id}/campanas")
    public OperacionServicio.CampanaVista agregarCampana(@PathParam("id") long id, NuevaCampana n) {
        return operaciones.agregarCampana(id, n == null ? null : n.campanaId(), n == null ? null : n.nombre());
    }

    @DELETE
    @Path("/{id}/campanas/{campana}")
    public Map<String, String> quitarCampana(@PathParam("id") long id, @PathParam("campana") String campana) {
        operaciones.quitarCampana(id, campana);
        return Map.of("resultado", "quitada");
    }
}
