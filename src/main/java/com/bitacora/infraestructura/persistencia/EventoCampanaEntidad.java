package com.bitacora.infraestructura.persistencia;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Historial de activaciones y detenciones hechas desde la aplicación. */
@Entity
@Table(name = "evento_campana")
public class EventoCampanaEntidad {
    public enum Tipo { INICIO, DETENCION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "campana_id") public String campanaId;
    @Enumerated(EnumType.STRING) public Tipo tipo;
    public LocalDateTime momento;
    public String origen;
}
