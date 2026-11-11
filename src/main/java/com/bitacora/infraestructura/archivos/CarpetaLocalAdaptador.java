package com.bitacora.infraestructura.archivos;

import com.bitacora.aplicacion.BitacoraConfig;
import com.bitacora.dominio.modelo.CsvLeido;
import com.bitacora.dominio.modelo.RegistroInvalido;
import com.bitacora.dominio.puerto.ArchivosPuerto;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * Lee los CSV de una carpeta del equipo. Sirve igual para una carpeta local que para la carpeta
 * de SharePoint sincronizada con OneDrive, porque ambas son una ruta normal.
 */
@ApplicationScoped
public class CarpetaLocalAdaptador implements ArchivosPuerto {

    private final BitacoraConfig config;

    public CarpetaLocalAdaptador(BitacoraConfig config) {
        this.config = config;
    }

    private Path entrada() { return crear(Path.of(config.carpetas().entrada())); }
    private Path procesados() { return crear(Path.of(config.carpetas().procesados())); }
    private Path rechazados() { return crear(Path.of(config.carpetas().rechazados())); }

    private static Path crear(Path p) {
        try { return Files.createDirectories(p); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** Evita que un nombre como ../../x salga de la carpeta de entrada. */
    private Path resolver(String nombre) {
        Path base = entrada().toAbsolutePath().normalize();
        Path ruta = base.resolve(nombre).normalize();
        if (!ruta.startsWith(base) || !Files.isRegularFile(ruta)) {
            throw new IllegalArgumentException("El archivo no existe en la carpeta de entrada: " + nombre);
        }
        return ruta;
    }

    @Override
    public List<InfoArchivo> listarCsv() {
        try (Stream<Path> s = Files.list(entrada())) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    .map(p -> {
                        try {
                            return new InfoArchivo(p.getFileName().toString(), Files.size(p),
                                    LocalDateTime.ofInstant(Files.getLastModifiedTime(p).toInstant(), ZoneId.systemDefault()));
                        } catch (IOException e) { throw new UncheckedIOException(e); }
                    })
                    .sorted(Comparator.comparing(InfoArchivo::nombre))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public CsvLeido leer(String nombre) {
        String texto = leerTexto(resolver(nombre));
        char sep = detectarSeparador(texto);
        CSVFormat formato = CSVFormat.DEFAULT.builder().setDelimiter(sep).setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true).setTrim(true).setAllowMissingColumnNames(true).get();
        try (CSVParser parser = CSVParser.parse(texto, formato)) {
            List<String> encabezado = parser.getHeaderNames().stream().map(h -> h.replace("﻿", "").trim()).toList();
            List<Map<String, String>> filas = new ArrayList<>();
            for (CSVRecord r : parser) {
                Map<String, String> fila = new LinkedHashMap<>();
                for (int i = 0; i < encabezado.size(); i++) fila.put(encabezado.get(i), i < r.size() ? r.get(i) : "");
                filas.add(fila);
            }
            return new CsvLeido(encabezado, filas, sep);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public int contarFilas(String nombre) {
        try (Stream<String> lineas = Files.lines(resolver(nombre), StandardCharsets.ISO_8859_1)) {
            return (int) Math.max(0, lineas.filter(l -> !l.isBlank()).count() - 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String huella(String nombre) {
        try (InputStream in = Files.newInputStream(resolver(nombre))) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo calcular la huella de " + nombre, e);
        }
    }

    @Override
    public String escribirRechazados(String nombre, List<String> encabezado, List<RegistroInvalido> invalidos, char separador) {
        String marca = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path destino = rechazados().resolve(nombre.replaceAll("(?i)\\.csv$", "") + "_RECHAZADOS_" + marca + ".csv");
        List<String> cols = new ArrayList<>(List.of("LINEA", "MOTIVO"));
        cols.addAll(encabezado);
        CSVFormat f = CSVFormat.DEFAULT.builder().setDelimiter(separador).setHeader(cols.toArray(String[]::new)).get();
        try (Writer w = Files.newBufferedWriter(destino, StandardCharsets.UTF_8); CSVPrinter p = new CSVPrinter(w, f)) {
            for (RegistroInvalido r : invalidos) {
                List<String> fila = new ArrayList<>(List.of(String.valueOf(r.linea()), r.motivo()));
                encabezado.forEach(c -> fila.add(r.fila().getOrDefault(c, "")));
                p.printRecord(fila);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return destino.toString();
    }

    @Override
    public void moverAProcesados(String nombre) {
        try {
            Files.move(resolver(nombre), procesados().resolve(nombre), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Los CSV pueden venir en UTF-8 o en ANSI (Windows-1252) si se guardaron desde Excel. */
    static String leerTexto(Path ruta) {
        try {
            byte[] bytes = Files.readAllBytes(ruta);
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                return new String(bytes, Charset.forName("windows-1252"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Wolkvox pide comas, pero Excel en español guarda con punto y coma: se acepta cualquiera. */
    static char detectarSeparador(String texto) {
        int fin = texto.indexOf('\n');
        String primera = fin < 0 ? texto : texto.substring(0, fin);
        long comas = primera.chars().filter(c -> c == ',').count();
        long pyc = primera.chars().filter(c -> c == ';').count();
        return pyc > comas ? ';' : ',';
    }
}
