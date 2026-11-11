package com.bitacora.infraestructura.excel;

import com.bitacora.aplicacion.BitacoraConfig;
import com.bitacora.dominio.puerto.BitacoraExcelPuerto;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.List;
import java.util.function.Function;

/**
 * Genera la bitácora en Excel con el encabezado de siempre más REMARCACIONES.
 * Una segunda hoja "Detalle" lleva los datos de apoyo (no válidos, huella, horario planeado).
 */
@ApplicationScoped
public class ExcelBitacoraAdaptador implements BitacoraExcelPuerto {

    private static final Logger LOG = Logger.getLogger(ExcelBitacoraAdaptador.class);

    private record Col(String titulo, Function<FilaBitacora, Object> valor) {}

    static final List<Col> PRINCIPAL = List.of(
            new Col("FECHA", FilaBitacora::fecha),
            new Col("HORA INICIO", FilaBitacora::horaInicio),
            new Col("HORA FINAL", FilaBitacora::horaFinal),
            new Col("ID_CAMPAÑA", FilaBitacora::idCampana),
            new Col("NOMBRE DE LISTA", FilaBitacora::nombreLista),
            new Col("CAMPAÑA", FilaBitacora::campana),
            new Col("ESTRATEGIA", FilaBitacora::estrategia),
            new Col("REMARCACIONES", FilaBitacora::remarcaciones),
            new Col("JEFE", FilaBitacora::jefe),
            new Col("REGISTROS CARGADOS", FilaBitacora::registrosCargados),
            new Col("TOTAL GESTIONADO", FilaBitacora::totalGestionado),
            new Col("ESTRATEGIA MARCACION", FilaBitacora::estrategiaMarcacion),
            new Col("% GESTIÓN", FilaBitacora::porcentajeGestion),
            new Col("DESCARGA INFORME", FilaBitacora::descargaInforme),
            new Col("NOMBRE ESTRATEGIA", FilaBitacora::nombreEstrategia),
            new Col("OBSERVACION", FilaBitacora::observacion));

    static final List<Col> DETALLE = List.of(
            new Col("FECHA", FilaBitacora::fecha),
            new Col("ID_CAMPAÑA", FilaBitacora::idCampana),
            new Col("NOMBRE DE LISTA", FilaBitacora::nombreLista),
            new Col("HORARIO PLANEADO", FilaBitacora::horarioPlaneado),
            new Col("REGISTROS NO VÁLIDOS", FilaBitacora::registrosNoValidos),
            new Col("HUELLA ARCHIVO", FilaBitacora::huella));

    private final BitacoraConfig config;

    public ExcelBitacoraAdaptador(BitacoraConfig config) {
        this.config = config;
    }

    @Override
    public synchronized Path escribir(List<FilaBitacora> filas) {
        Path destino = Path.of(config.excel().ruta());
        try {
            if (destino.getParent() != null) Files.createDirectories(destino.getParent());
            Path tmp = destino.resolveSibling(destino.getFileName() + ".tmp");
            Files.write(tmp, generar(filas));
            try {
                Files.move(tmp, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, destino, StandardCopyOption.REPLACE_EXISTING);
            }
            return destino;
        } catch (FileSystemException e) {
            // Típico en Windows cuando alguien tiene el Excel abierto: los datos siguen en la base y se reintenta luego.
            LOG.warnf("No se pudo escribir %s (¿está abierto?): %s", destino, e.getMessage());
            throw new IllegalStateException("El Excel está abierto o bloqueado: ciérralo y vuelve a sincronizar.", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] generar(List<FilaBitacora> filas) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            hoja(wb, config.excel().hoja(), PRINCIPAL, filas);
            hoja(wb, "Detalle", DETALLE, filas);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void hoja(Workbook wb, String nombre, List<Col> cols, List<FilaBitacora> filas) {
        Sheet s = wb.createSheet(nombre);
        CellStyle titulo = wb.createCellStyle();
        Font negrita = wb.createFont();
        negrita.setBold(true);
        titulo.setFont(negrita);
        titulo.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        titulo.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        Row h = s.createRow(0);
        for (int i = 0; i < cols.size(); i++) {
            Cell c = h.createCell(i);
            c.setCellValue(cols.get(i).titulo());
            c.setCellStyle(titulo);
        }
        int r = 1;
        for (FilaBitacora f : filas) {
            Row row = s.createRow(r++);
            for (int i = 0; i < cols.size(); i++) {
                Object v = cols.get(i).valor().apply(f);
                Cell c = row.createCell(i);
                if (v instanceof Number n) c.setCellValue(n.doubleValue());
                else if (v != null) c.setCellValue(v.toString());
            }
        }
        s.createFreezePane(0, 1);
        if (!filas.isEmpty()) s.setAutoFilter(new CellRangeAddress(0, filas.size(), 0, cols.size() - 1));
        for (int i = 0; i < cols.size(); i++) s.setColumnWidth(i, Math.min(60, Math.max(12, cols.get(i).titulo().length() + 4)) * 256);
    }
}
