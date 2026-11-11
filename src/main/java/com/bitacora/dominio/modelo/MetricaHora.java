package com.bitacora.dominio.modelo;

import java.time.LocalDateTime;

/** Corte de una hora para una campaña. */
public record MetricaHora(String campanaId, LocalDateTime hora, int agentesConectados,
                          int registrosBarridos, int gestionados) {}
