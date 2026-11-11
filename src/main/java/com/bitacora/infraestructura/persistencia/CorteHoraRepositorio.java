package com.bitacora.infraestructura.persistencia;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class CorteHoraRepositorio implements PanacheRepository<CorteHoraEntidad> {

    public Optional<CorteHoraEntidad> buscar(String campanaId, LocalDateTime hora) {
        return find("campanaId = ?1 and hora = ?2", campanaId, hora).firstResultOptional();
    }

    public List<CorteHoraEntidad> delDia(LocalDate fecha) {
        return list("hora >= ?1 and hora < ?2", Sort.by("hora").and("campanaId"),
                fecha.atStartOfDay(), fecha.plusDays(1).atStartOfDay());
    }
}
