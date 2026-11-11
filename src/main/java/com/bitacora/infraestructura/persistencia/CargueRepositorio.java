package com.bitacora.infraestructura.persistencia;

import com.bitacora.dominio.modelo.EstadoCargue;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.util.List;

@ApplicationScoped
public class CargueRepositorio implements PanacheRepository<CargueEntidad> {

    public List<CargueEntidad> delDia(LocalDate fecha) {
        return list("fecha", Sort.by("horaInicioPlan").and("id"), fecha);
    }

    public List<CargueEntidad> todos() {
        return listAll(Sort.by("fecha").and("id"));
    }

    public List<CargueEntidad> activosDeCampanaEnFecha(String campanaId, LocalDate fecha) {
        return list("campanaId = ?1 and fecha = ?2 and estado in ?3", campanaId, fecha,
                List.of(EstadoCargue.CARGADO, EstadoCargue.CARGADO_CON_RECHAZOS));
    }

    public long previosDeLista(String campanaId, String listaBase) {
        return count("campanaId = ?1 and listaBase = ?2 and estado in ?3", campanaId, listaBase,
                List.of(EstadoCargue.CARGADO, EstadoCargue.CARGADO_CON_RECHAZOS));
    }

    public boolean huellaYaCargada(String huella, String campanaId) {
        return count("huella = ?1 and campanaId = ?2 and estado in ?3", huella, campanaId,
                List.of(EstadoCargue.CARGADO, EstadoCargue.CARGADO_CON_RECHAZOS)) > 0;
    }

    public boolean huellaYaCargada(String huella) {
        return count("huella = ?1 and estado in ?2", huella,
                List.of(EstadoCargue.CARGADO, EstadoCargue.CARGADO_CON_RECHAZOS)) > 0;
    }
}
