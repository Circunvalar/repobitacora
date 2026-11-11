package com.bitacora.dominio.modelo;

import java.util.List;
import java.util.Map;

public record ResultadoValidacion(List<String> encabezado,
                                  List<Map<String, String>> validos,
                                  List<RegistroInvalido> invalidos,
                                  List<String> erroresEstructura) {
    public boolean estructuraValida() { return erroresEstructura.isEmpty(); }
}
