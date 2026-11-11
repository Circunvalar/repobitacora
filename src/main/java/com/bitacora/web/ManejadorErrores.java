package com.bitacora.web;

import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.Map;
import java.util.NoSuchElementException;

/** Convierte los errores del negocio en respuestas claras para la pantalla. */
public class ManejadorErrores {

    @ServerExceptionMapper
    public Response noEncontrado(NoSuchElementException e) {
        return Response.status(404).entity(Map.of("error", e.getMessage())).build();
    }

    @ServerExceptionMapper
    public Response invalido(IllegalArgumentException e) {
        return Response.status(400).entity(Map.of("error", e.getMessage())).build();
    }

    @ServerExceptionMapper
    public Response conflicto(IllegalStateException e) {
        return Response.status(409).entity(Map.of("error", e.getMessage())).build();
    }
}
