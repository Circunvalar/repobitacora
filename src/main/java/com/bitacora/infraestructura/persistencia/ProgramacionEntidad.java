package com.bitacora.infraestructura.persistencia;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Encendido programado de una campaña para un día y una hora (solo encendido, solo ese día). */
@Entity
@Table(name = "programacion")
public class ProgramacionEntidad {

    public enum Estado { PENDIENTE, EJECUTADA, FALLIDA, CANCELADA, VENCIDA }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "operacion_id") public Long operacionId;
    @Column(name = "campana_id") public String campanaId;
    public LocalDate fecha;
    public LocalTime hora;
    /** Cargue que pidió este encendido (vacío si se programó a mano). */
    @Column(name = "cargue_id") public Long cargueId;
    @Enumerated(EnumType.STRING) public Estado estado;
    public LocalDateTime ejecutada;
    @Column(length = 1000) public String error;
    public LocalDateTime creado;
}
