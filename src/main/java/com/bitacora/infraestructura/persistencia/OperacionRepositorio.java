package com.bitacora.infraestructura.persistencia;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class OperacionRepositorio implements PanacheRepository<OperacionEntidad> {
    public List<OperacionEntidad> vigentes() {
        return list("archivada = false", Sort.by("nombre"));
    }

    public boolean existeNombre(String nombre, Long excepto) {
        return count("lower(nombre) = ?1 and id <> ?2", nombre.trim().toLowerCase(), excepto == null ? -1L : excepto) > 0;
    }
}
