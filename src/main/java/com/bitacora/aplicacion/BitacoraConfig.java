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
        /** auto | http | simulado. "auto" usa http cuando hay servidor y token. */
        @WithDefault("auto")
        String modo();
        Optional<String> servidor();
        Optional<String> token();
        /** Solo para pruebas: reemplaza https://wv{servidor}.wolkvox.com/api/v2/. */
        Optional<String> urlBase();
        @WithDefault("2")
        int maxConcurrencia();
        Endpoints endpoints();
        /** Columna normalizada del CSV = campo de la API add_record. */
        List<String> columnas();

        /** true cuando la app debe hablar con la API real. */
        default boolean usarHttp() {
            String m = modo().trim().toLowerCase(java.util.Locale.ROOT);
            if (m.equals("http")) return true;
            if (m.equals("simulado")) return false;
            if (urlBase().isPresent() && token().filter(x -> !x.isBlank()).isPresent()) return true;
            return servidor().filter(x -> !x.isBlank()).isPresent() && token().filter(x -> !x.isBlank()).isPresent();
        }

        /** Rutas relativas a https://wv{servidor}.wolkvox.com/api/v2/. Se confirman con la colección de Postman. */
        interface Endpoints {
            String campanas();
            String cargarRegistros();
            String iniciar();
            String detener();
            String agentesPorCampana();
            String intentosCampana();
        }
    }

    interface Campanas {
        /** Activa o detiene automáticamente según el horario planeado de cada cargue. */
        @WithDefault("false")
        boolean autoActivar();
        /** Intervalo del escaneo de campañas activas/inactivas (p. ej. 5m); "off" lo desactiva. */
        @WithDefault("5m")
        String escaneo();
    }

    interface Catalogos {
        List<String> jefes();
    }
}
