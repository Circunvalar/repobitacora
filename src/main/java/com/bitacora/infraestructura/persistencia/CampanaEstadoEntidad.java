package com.bitacora.infraestructura.persistencia;

import com.bitacora.dominio.modelo.TipoMarcacion;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Último estado conocido de una campaña en Wolkvox. */
@Entity
@Table(name = "campana_estado")
public class CampanaEstadoEntidad {
    @Id
    @Column(name = "campana_id") public String campanaId;
    public String nombre;
    @Enumerated(EnumType.STRING) public TipoMarcacion tipo;
    public Boolean activa;
    public Integer registros;
    /** Desde cuándo está en el estado actual (null si ya estaba así en la primera revisión). */
    public LocalDateTime desde;
    @Column(name = "ultima_revision") public LocalDateTime ultimaRevision;
}
