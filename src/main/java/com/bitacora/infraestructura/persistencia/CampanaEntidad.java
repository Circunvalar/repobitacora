package com.bitacora.infraestructura.persistencia;

import com.bitacora.dominio.modelo.Campana;
import com.bitacora.dominio.modelo.TipoMarcacion;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Campaña de Wolkvox guardada con su conexión (servidor + token) y su último estado conocido. */
@Entity
@Table(name = "campana")
public class CampanaEntidad {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "operacion_id") public Long operacionId;
    @Column(name = "campana_id") public String campanaId;
    public String nombre;
    @Enumerated(EnumType.STRING) public TipoMarcacion tipo;
    public Boolean activa;
    public Integer registros;
    /** Desde cuándo está en el estado actual (null si ya estaba así cuando se registró). */
    public LocalDateTime desde;
    @Column(name = "ultima_revision") public LocalDateTime ultimaRevision;
    @Column(name = "ultimo_error", length = 1000) public String ultimoError;
    public LocalDateTime creado;

    public Campana aCampana() {
        return new Campana(campanaId, nombre, tipo != null ? tipo : TipoMarcacion.PREDICTIVO, activa, registros);
    }
}
