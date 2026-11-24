package com.bitacora.infraestructura.persistencia;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class CampanaEstadoRepositorio implements PanacheRepositoryBase<CampanaEstadoEntidad, String> {
    public List<CampanaEstadoEntidad> todas() {
        return listAll(Sort.by("activa", Sort.Direction.Descending).and("nombre"));
    }
}
