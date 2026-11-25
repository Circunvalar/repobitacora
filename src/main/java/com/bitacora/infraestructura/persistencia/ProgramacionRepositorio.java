package com.bitacora.infraestructura.persistencia;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@ApplicationScoped
public class ProgramacionRepositorio implements PanacheRepository<ProgramacionEntidad> {

    public List<ProgramacionEntidad> delDia(LocalDate fecha) {
        return list("fecha", Sort.by("hora").and("id"), fecha);
    }

    /** Pendientes de hoy cuya hora ya llegó. */
    public List<ProgramacionEntidad> porEjecutar(LocalDate hoy, LocalTime ahora) {
        return list("estado = ?1 and fecha = ?2 and hora <= ?3", Sort.by("hora").and("id"),
                ProgramacionEntidad.Estado.PENDIENTE, hoy, ahora);
    }

    /** Las de días anteriores que nunca llegaron a ejecutarse (el programa estaba cerrado). */
    public int vencer(LocalDate hoy) {
        return update("estado = ?1 where estado = ?2 and fecha < ?3", ProgramacionEntidad.Estado.VENCIDA,
                ProgramacionEntidad.Estado.PENDIENTE, hoy);
    }
}
