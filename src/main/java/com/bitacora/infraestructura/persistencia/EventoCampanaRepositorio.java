package com.bitacora.infraestructura.persistencia;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
public class EventoCampanaRepositorio implements PanacheRepository<EventoCampanaEntidad> {
    public List<EventoCampanaEntidad> entre(LocalDateTime desde, LocalDateTime hasta) {
        return list("momento >= ?1 and momento < ?2", Sort.by("momento", Sort.Direction.Descending).and("id", Sort.Direction.Descending), desde, hasta);
    }
}
