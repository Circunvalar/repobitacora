package com.bitacora.web;

import com.bitacora.aplicacion.CargueServicio;
import com.bitacora.dominio.modelo.ArchivoCsv;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/archivos")
@Produces(MediaType.APPLICATION_JSON)
public class ArchivosRecurso {

    private final CargueServicio cargues;

    public ArchivosRecurso(CargueServicio cargues) {
        this.cargues = cargues;
    }

    /** Escanea la carpeta de entrada. */
    @GET
    public List<ArchivoCsv> escanear() {
        return cargues.escanear();
    }

    /** Valida un archivo sin cargarlo. */
    @GET
    @Path("/{nombre}/revision")
    public CargueServicio.Vista revisar(@PathParam("nombre") String nombre) {
        return cargues.revisar(nombre);
    }
}
