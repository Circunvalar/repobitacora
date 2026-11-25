package com.bitacora.web;

import com.bitacora.aplicacion.ProgramacionServicio;
import com.bitacora.aplicacion.ProgramacionServicio.Vista;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Encendidos programados (solo encendido, solo para hoy). */
@Path("/api/programaciones")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProgramacionesRecurso {

    public record Nueva(Long campanaGuardadaId, String campanaId, LocalTime hora) {}

    public record CambioHora(LocalTime hora) {}

    private final ProgramacionServicio servicio;
    private final Clock reloj;

    public ProgramacionesRecurso(ProgramacionServicio servicio, Clock reloj) {
        this.servicio = servicio;
        this.reloj = reloj;
    }

    @GET
    public List<Vista> delDia(@QueryParam("fecha") LocalDate fecha) {
        return servicio.delDia(fecha != null ? fecha : LocalDate.now(reloj));
    }

    @POST
    public Vista programar(Nueva n) {
        if (n == null || n.campanaGuardadaId() == null) {
            throw new IllegalArgumentException("Elija la campaña que se va a encender.");
        }
        return servicio.programar(n.campanaGuardadaId(), n.campanaId(), n.hora(), null);
    }

    @PUT
    @Path("/{id}")
    public Vista cambiarHora(@PathParam("id") long id, CambioHora c) {
        return servicio.cambiarHora(id, c == null ? null : c.hora());
    }

    @DELETE
    @Path("/{id}")
    public Vista cancelar(@PathParam("id") long id) {
        return servicio.cancelar(id);
    }
}
