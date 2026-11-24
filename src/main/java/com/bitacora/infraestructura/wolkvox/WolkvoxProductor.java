package com.bitacora.infraestructura.wolkvox;

import com.bitacora.aplicacion.BitacoraConfig;
import com.bitacora.dominio.puerto.WolkvoxPuerto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import org.jboss.logging.Logger;

/** Elige el adaptador según bitacora.wolkvox.modo (auto | http | simulado). */
@ApplicationScoped
public class WolkvoxProductor {

    private static final Logger LOG = Logger.getLogger(WolkvoxProductor.class);

    @Produces
    @Named("wolkvoxActivo")
    WolkvoxPuerto wolkvox(BitacoraConfig config, WolkvoxSimuladoAdaptador simulado, WolkvoxHttpAdaptador http) {
        if (config.wolkvox().usarHttp()) {
            LOG.infof("Wolkvox: API real en wv%s.wolkvox.com", config.wolkvox().servidor().orElse("?"));
            return http;
        }
        LOG.warn("Wolkvox en modo SIMULADO: no se envía nada. Configure bitacora.wolkvox.servidor y bitacora.wolkvox.token.");
        return simulado;
    }
}
