package com.bitacora.infraestructura.persistencia;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class CampanaRepositorio implements PanacheRepository<CampanaEntidad> {
    public List<CampanaEntidad> deOperacion(long operacionId) {
        return list("operacionId", Sort.by("nombre").and("campanaId"), operacionId);
    }

    public Optional<CampanaEntidad> buscar(long operacionId, String campanaId) {
        return find("operacionId = ?1 and campanaId = ?2", operacionId, campanaId).firstResultOptional();
    }
}
