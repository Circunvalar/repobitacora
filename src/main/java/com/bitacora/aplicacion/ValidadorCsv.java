package com.bitacora.aplicacion;

import com.bitacora.dominio.modelo.CsvLeido;
import com.bitacora.dominio.modelo.RegistroInvalido;
import com.bitacora.dominio.modelo.ResultadoValidacion;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Valida un CSV con la plantilla de Wolkvox antes de enviarlo. No depende de ninguna campaña:
 * las reglas (nombres de columnas, largos, teléfonos) vienen de la configuración.
 */
public class ValidadorCsv {

    public record Reglas(Set<String> columnasId, Set<String> columnasNombre, Pattern patronTelefono,
                         int telMin, int telMax, int largoMaximo, Map<String, Integer> largosEspeciales) {

        public static Reglas desde(BitacoraConfig.Validacion v) {
            Map<String, Integer> especiales = new HashMap<>();
            for (String par : v.largosEspeciales()) {
                String[] p = par.split("=");
                if (p.length == 2) especiales.put(normalizar(p[0]), Integer.parseInt(p[1].trim()));
            }
            return new Reglas(normalizarTodos(v.columnasId()), normalizarTodos(v.columnasNombre()),
                    Pattern.compile(v.patronColumnaTelefono()), v.telefonoMinDigitos(), v.telefonoMaxDigitos(),
                    v.largoMaximoCampo(), especiales);
        }
    }

    private final Reglas reglas;

    public ValidadorCsv(Reglas reglas) {
        this.reglas = reglas;
    }

    public ResultadoValidacion validar(CsvLeido csv) {
        List<String> encabezado = csv.encabezado();
        String colId = buscar(encabezado, reglas.columnasId());
        String colNombre = buscar(encabezado, reglas.columnasNombre());
        List<String> colsTel = encabezado.stream()
                .filter(c -> reglas.patronTelefono().matcher(normalizar(c)).matches()).toList();

        List<String> erroresEstructura = new ArrayList<>();
        if (colId == null) erroresEstructura.add("Falta la columna de identificación " + reglas.columnasId());
        if (colNombre == null) erroresEstructura.add("Falta la columna de nombre " + reglas.columnasNombre());
        if (colsTel.isEmpty()) erroresEstructura.add("No hay ninguna columna de teléfono (Tel1..Tel10)");
        if (!erroresEstructura.isEmpty()) {
            return new ResultadoValidacion(encabezado, List.of(), List.of(), erroresEstructura);
        }

        List<Map<String, String>> validos = new ArrayList<>();
        List<RegistroInvalido> invalidos = new ArrayList<>();
        Set<String> idsVistos = new HashSet<>();
        int linea = 1;
        for (Map<String, String> original : csv.filas()) {
            linea++;
            Map<String, String> fila = new LinkedHashMap<>(original);
            List<String> motivos = new ArrayList<>();

            String id = valor(fila, colId);
            if (id.isEmpty()) motivos.add("identificación vacía");
            else if (!idsVistos.add(id)) motivos.add("identificación duplicada en el archivo");
            if (valor(fila, colNombre).isEmpty()) motivos.add("nombre vacío");

            int telefonosValidos = 0;
            for (String col : colsTel) {
                String crudo = valor(fila, col);
                if (crudo.isEmpty()) continue;
                String limpio = crudo.replaceAll("[\\s\\-().+]", "");
                if (limpio.matches("\\d+") && limpio.length() >= reglas.telMin() && limpio.length() <= reglas.telMax()) {
                    fila.put(col, limpio);
                    telefonosValidos++;
                } else {
                    fila.put(col, ""); // se descarta solo ese teléfono
                }
            }
            if (telefonosValidos == 0) motivos.add("sin teléfono válido");

            for (Map.Entry<String, String> e : fila.entrySet()) {
                int max = reglas.largosEspeciales().getOrDefault(normalizar(e.getKey()), reglas.largoMaximo());
                if (e.getValue() != null && e.getValue().length() > max) {
                    motivos.add(e.getKey() + " supera " + max + " caracteres");
                }
            }

            if (motivos.isEmpty()) validos.add(fila);
            else invalidos.add(new RegistroInvalido(linea, String.join("; ", motivos), original));
        }
        return new ResultadoValidacion(encabezado, validos, invalidos, List.of());
    }

    private static String valor(Map<String, String> fila, String col) {
        String v = fila.get(col);
        return v == null ? "" : v.trim();
    }

    private static String buscar(List<String> encabezado, Set<String> aceptados) {
        return encabezado.stream().filter(c -> aceptados.contains(normalizar(c))).findFirst().orElse(null);
    }

    static Set<String> normalizarTodos(Collection<String> nombres) {
        Set<String> s = new HashSet<>();
        nombres.forEach(n -> s.add(normalizar(n)));
        return s;
    }

    /** "Teléfono 1" → "telefono1", "ID_Cliente" → "idcliente". */
    public static String normalizar(String columna) {
        return Normalizer.normalize(columna.replace("﻿", ""), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }
}
