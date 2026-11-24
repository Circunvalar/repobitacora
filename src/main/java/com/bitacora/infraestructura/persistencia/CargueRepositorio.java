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

    public long deOperacion(long operacionId) {
        return count("operacionId", operacionId);
    }

    public List<CargueEntidad> todos() {
        return listAll(Sort.by("fecha").and("id"));
    }

    public List<CargueEntidad> activosDeCampanaEnFecha(Long operacionId, String campanaId, LocalDate fecha) {
        return list("operacionId = ?1 and campanaId = ?2 and fecha = ?3 and estado in ?4", operacionId, campanaId, fecha,
                List.of(EstadoCargue.CARGADO, EstadoCargue.CARGADO_CON_RECHAZOS));
    }

    public long previosDeLista(Long operacionId, String campanaId, String listaBase) {
        return count("operacionId = ?1 and campanaId = ?2 and listaBase = ?3 and estado in ?4", operacionId, campanaId, listaBase,
                List.of(EstadoCargue.CARGADO, EstadoCargue.CARGADO_CON_RECHAZOS));
    }

    public boolean huellaYaCargada(String huella, Long operacionId, String campanaId) {
        return count("huella = ?1 and operacionId = ?2 and campanaId = ?3 and estado in ?4", huella, operacionId, campanaId,
                List.of(EstadoCargue.CARGADO, EstadoCargue.CARGADO_CON_RECHAZOS)) > 0;
    }

    public boolean huellaYaCargada(String huella) {
        return count("huella = ?1 and estado in ?2", huella,
                List.of(EstadoCargue.CARGADO, EstadoCargue.CARGADO_CON_RECHAZOS)) > 0;
    }
}
