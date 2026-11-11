package com.bitacora.web;

import com.bitacora.aplicacion.BitacoraServicio;
import com.bitacora.aplicacion.CargueServicio;
import com.bitacora.infraestructura.persistencia.CargueEntidad;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.time.LocalDate;
import java.util.List;

/** La bitácora: cargues del día, crear cargues y editar campos manuales. */
@Path("/api/cargues")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CarguesRecurso {

    public record SolicitudLote(@NotEmpty List<@Valid ItemCargue> cargues) {}

    public record ItemCargue(@NotBlank String archivo, @NotBlank String campanaId,
                             com.bitacora.dominio.modelo.TipoEstrategia estrategia, String jefe,
                             String nombreEstrategia, java.time.LocalTime horaInicioPlan,
                             java.time.LocalTime horaFinPlan, String observacion, LocalDate fecha) {}

    private final CargueServicio cargues;
    private final BitacoraServicio bitacora;

    public CarguesRecurso(CargueServicio cargues, BitacoraServicio bitacora) {
        this.cargues = cargues;
        this.bitacora = bitacora;
    }

    @GET
    public List<CargueDto> delDia(@QueryParam("fecha") LocalDate fecha) {
        return bitacora.delDia(fecha != null ? fecha : LocalDate.now()).stream().map(CargueDto::de).toList();
    }

    /** Carga uno o varios archivos, cada uno a su campaña. Se procesan en orden. */
    @POST
    public List<CargueDto> cargar(@Valid SolicitudLote s) {
        return s.cargues().stream().map(i -> cargues.cargar(new CargueServicio.Solicitud(i.archivo(), i.campanaId(),
                i.estrategia(), i.jefe(), i.nombreEstrategia(), i.horaInicioPlan(), i.horaFinPlan(), i.observacion(),
                i.fecha()))).map(CargueDto::de).toList();
    }

    @PATCH
    @Path("/{id}")
    public CargueDto editar(@PathParam("id") long id, BitacoraServicio.Edicion e) {
        CargueEntidad c = bitacora.editar(id, e);
        bitacora.sincronizarExcelSinFallar();
        return CargueDto.de(c);
    }
}
