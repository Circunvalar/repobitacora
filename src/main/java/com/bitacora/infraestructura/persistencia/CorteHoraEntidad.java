package com.bitacora.infraestructura.persistencia;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Métricas de una campaña en una hora (corte hora). */
@Entity
@Table(name = "corte_hora", uniqueConstraints = @UniqueConstraint(columnNames = {"campana_id", "hora"}))
public class CorteHoraEntidad {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "campana_id") public String campanaId;
    public LocalDateTime hora;
    @Column(name = "agentes_conectados") public int agentesConectados;
    @Column(name = "registros_barridos") public int registrosBarridos;
    public int gestionados;
}
