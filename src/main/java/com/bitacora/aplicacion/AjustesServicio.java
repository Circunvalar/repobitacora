package com.bitacora.aplicacion;

import com.bitacora.infraestructura.persistencia.AjusteEntidad;
import com.bitacora.infraestructura.persistencia.AjusteRepositorio;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Ajustes que el usuario cambia desde la app: por ahora, la carpeta donde se buscan las bases. */
@ApplicationScoped
public class AjustesServicio {

    static final String CARPETA_BASES = "carpeta.bases";
    /** Extensiones de bases que se pueden cargar. */
    public static final List<String> EXTENSIONES = List.of(".csv", ".txt", ".xlsx");

    public record Carpeta(String nombre, String ruta) {}

    public record Explorador(String ruta, String padre, List<Carpeta> carpetas, int bases, boolean esRaiz) {}

    private final AjusteRepositorio repo;
    private final BitacoraConfig config;
    private volatile String carpetaBases;

    public AjustesServicio(AjusteRepositorio repo, BitacoraConfig config) {
        this.repo = repo;
        this.config = config;
    }

    /** Carpeta de bases elegida en la app; si nunca se eligió, la de la configuración. */
    public String carpetaBases() {
        String c = carpetaBases;
        if (c == null) {
            c = QuarkusTransaction.requiringNew().call(() -> {
                AjusteEntidad a = repo.findById(CARPETA_BASES);
                return a != null && a.valor != null && !a.valor.isBlank() ? a.valor : config.carpetas().entrada();
            });
            carpetaBases = c;
        }
        return c;
    }

    public String cambiarCarpetaBases(String ruta) {
        if (ruta == null || ruta.isBlank()) {
            throw ErrorNegocio.datos("Falta la carpeta", "Elija la carpeta donde están las bases.", "Navegue hasta la carpeta y pulse \"Usar esta carpeta\".");
        }
        Path p = Path.of(ruta.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) {
            throw ErrorNegocio.datos("La carpeta no existe", p + " no es una carpeta de este equipo.", "Revise la ruta o elíjala navegando.");
        }
        if (!Files.isReadable(p)) {
            throw ErrorNegocio.datos("Sin permiso para leer la carpeta", "El programa no puede leer " + p + ".", "Elija otra carpeta o revise los permisos.");
        }
        String valor = p.toString();
        QuarkusTransaction.requiringNew().run(() -> {
            AjusteEntidad a = repo.findById(CARPETA_BASES);
            if (a == null) {
                a = new AjusteEntidad();
                a.clave = CARPETA_BASES;
                a.valor = valor;
                repo.persist(a);
            } else {
                a.valor = valor;
            }
        });
        carpetaBases = valor;
        return valor;
    }

    /** Lista las subcarpetas de una ruta para elegir la carpeta de bases desde la pantalla. Sin ruta: las unidades del equipo. */
    public Explorador explorar(String ruta) {
        if (ruta == null || ruta.isBlank()) {
            List<Carpeta> unidades = new ArrayList<>();
            for (File r : File.listRoots()) unidades.add(new Carpeta(r.getPath(), r.getPath()));
            String inicio = System.getProperty("user.home");
            if (inicio != null) unidades.add(0, new Carpeta("Carpeta personal (" + inicio + ")", inicio));
            return new Explorador("", null, unidades, 0, true);
        }
        Path p = Path.of(ruta).toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) {
            throw ErrorNegocio.noEncontrado("La carpeta no existe", p + " no es una carpeta de este equipo.", "Vuelva atrás y elija otra.");
        }
        List<Carpeta> hijas = new ArrayList<>();
        int bases = 0;
        try (Stream<Path> s = Files.list(p)) {
            for (Path x : (Iterable<Path>) s::iterator) {
                String n = x.getFileName().toString();
                if (n.startsWith(".") || n.startsWith("$")) continue;
                if (Files.isDirectory(x)) hijas.add(new Carpeta(n, x.toString()));
                else if (esBase(n)) bases++;
            }
        } catch (IOException | UncheckedIOException e) {
            throw ErrorNegocio.datos("No se pudo abrir la carpeta", p + ": " + e.getMessage(), "Elija otra carpeta.");
        }
        hijas.sort(Comparator.comparing(c -> c.nombre().toLowerCase(Locale.ROOT)));
        Path padre = p.getParent();
        return new Explorador(p.toString(), padre != null ? padre.toString() : "", hijas, bases, false);
    }

    public static boolean esBase(String nombre) {
        String n = nombre.toLowerCase(Locale.ROOT);
        return !n.startsWith("~$") && EXTENSIONES.stream().anyMatch(n::endsWith);
    }
}
