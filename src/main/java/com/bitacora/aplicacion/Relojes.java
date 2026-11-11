package com.bitacora.aplicacion;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.time.Clock;
import java.time.ZoneId;

/** Reloj de la operación (hora de Bogotá por defecto); inyectable para pruebas. */
@ApplicationScoped
public class Relojes {
    @Produces
    @jakarta.inject.Singleton
    Clock reloj(BitacoraConfig config) {
        return Clock.system(ZoneId.of(config.zonaHoraria()));
    }
}
