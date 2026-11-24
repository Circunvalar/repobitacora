package com.bitacora.web;

import com.bitacora.aplicacion.ErrorNegocio;
import com.bitacora.infraestructura.wolkvox.WolkvoxException;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * Convierte los errores en respuestas que la pantalla muestra como tarjeta:
 * {titulo, error (detalle), sugerencia, tipo}.
 */
public class ManejadorErrores {

    private static final Logger LOG = Logger.getLogger(ManejadorErrores.class);

    static Response respuesta(int estado, String tipo, String titulo, String detalle, String sugerencia) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo", tipo);
        m.put("titulo", titulo);
        m.put("error", detalle == null ? "" : detalle);
        m.put("sugerencia", sugerencia == null ? "" : sugerencia);
        return Response.status(estado).entity(m).build();
    }

    @ServerExceptionMapper
    public Response wolkvox(WolkvoxException e) {
        int estado = switch (e.tipo()) {
            case TOKEN -> 401;
            case NO_EXISTE -> 404;
            case DATOS -> 422;
            case LIMITE -> 429;
            case RED, SERVIDOR -> 503;
        };
        return respuesta(estado, "WOLKVOX_" + e.tipo().name(), e.titulo(), e.getMessage(), e.sugerencia());
    }

    @ServerExceptionMapper
    public Response negocio(ErrorNegocio e) {
        return respuesta(e.estado(), "NEGOCIO", e.titulo(), e.getMessage(), e.sugerencia());
    }

    @ServerExceptionMapper
    public Response validacion(ConstraintViolationException e) {
        String detalle = e.getConstraintViolations().stream().map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        return respuesta(400, "DATOS", "Faltan datos", detalle, "Complete los campos marcados.");
    }

    @ServerExceptionMapper
    public Response noEncontrado(NoSuchElementException e) {
        return respuesta(404, "NEGOCIO", "No encontrado", e.getMessage(), null);
    }

    @ServerExceptionMapper
    public Response invalido(IllegalArgumentException e) {
        return respuesta(400, "DATOS", "Dato no válido", e.getMessage(), null);
    }

    @ServerExceptionMapper
    public Response conflicto(IllegalStateException e) {
        return respuesta(409, "NEGOCIO", "No se pudo completar", e.getMessage(), null);
    }

    @ServerExceptionMapper
    public Response inesperado(RuntimeException e) {
        if (e instanceof jakarta.ws.rs.WebApplicationException w) return w.getResponse();
        LOG.error("Error inesperado", e);
        return respuesta(500, "INTERNO", "Error inesperado", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                "Revise el archivo datos\\logs\\bitacora.log o intente de nuevo.");
    }
}
