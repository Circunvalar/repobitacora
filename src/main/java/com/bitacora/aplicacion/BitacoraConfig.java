package com.bitacora.aplicacion;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Optional;

/** Toda la configuración de la operación vive aquí; nada está atado a una campaña específica. */
@ConfigMapping(prefix = "bitacora")
public interface BitacoraConfig {

    Carpetas carpetas();
    Excel excel();
    Validacion validacion();
    Cargue cargue();
    Wolkvox wolkvox();
    Campanas campanas();
    Catalogos catalogos();
    Metricas metricas();

    @WithDefault("America/Bogota")
    String zonaHoraria();

    interface Metricas {
        /** Cron de recolección; "off" la desactiva. */
        @WithDefault("0 2 * * * ?")
        String cron();
    }

    interface Carpetas {
        /** Carpeta donde se dejan los CSV para cargue. */
        String entrada();
        String procesados();
        String rechazados();
    }

    interface Excel {
        String ruta();
        @WithDefault("Bitacora")
        String hoja();
    }

    interface Validacion {
        /** Nombres aceptados para la columna de identificación (sin tildes, minúsculas). */
        List<String> columnasId();
        List<String> columnasNombre();
        /** Regex sobre el nombre normalizado de columna para reconocer teléfonos (tel1..tel10, otro tel). */
        String patronColumnaTelefono();
        @WithDefault("7")
        int telefonoMinDigitos();
        @WithDefault("15")
        int telefonoMaxDigitos();
        @WithDefault("100")
        int largoMaximoCampo();
        /** Columnas con un largo máximo distinto, formato columna=largo. */
        List<String> largosEspeciales();
    }

    interface Cargue {
        @WithDefault("10000")
        int tamanoLote();
        /** Si es false, un archivo con la misma huella no se puede volver a cargar a la misma campaña. */
        @WithDefault("false")
        boolean permitirDuplicados();
    }

    interface Wolkvox {
        /** simulado | http */
        @WithDefault("simulado")
        String modo();
        Optional<String> servidor();
        Optional<String> token();
        @WithDefault("2")
        int maxConcurrencia();
        Endpoints endpoints();

        /** Rutas relativas a https://wv{servidor}.wolkvox.com/api/v2/. Se confirman con la colección de Postman. */
        interface Endpoints {
            String campanas();
            String cargarRegistros();
            String iniciar();
            String detener();
            String agentesPorHora();
            String interacciones();
        }
    }

    interface Campanas {
        /** Activa o detiene automáticamente según el horario planeado de cada cargue. */
        @WithDefault("false")
        boolean autoActivar();
    }

    interface Catalogos {
        List<String> jefes();
    }
}
