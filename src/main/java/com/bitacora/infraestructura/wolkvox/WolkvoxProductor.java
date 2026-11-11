package com.bitacora.infraestructura.wolkvox;

import com.bitacora.aplicacion.BitacoraConfig;
import com.bitacora.dominio.puerto.WolkvoxPuerto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;

/** Elige el adaptador según bitacora.wolkvox.modo (simulado | http). */
@ApplicationScoped
public class WolkvoxProductor {

    @Produces
    @Named("wolkvoxActivo")
    WolkvoxPuerto wolkvox(BitacoraConfig config, WolkvoxSimuladoAdaptador simulado, WolkvoxHttpAdaptador http) {
        return "http".equalsIgnoreCase(config.wolkvox().modo()) ? http : simulado;
    }
}
