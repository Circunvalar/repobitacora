# Bitácora Digital de Cargues Wolkvox (prototipo)

Aplicación web en Quarkus que toma los CSV de una carpeta, los valida con la plantilla de Wolkvox,
los carga a cualquier campaña (predictiva o preview), deja cada cargue en la bitácora y en un Excel,
registra la hora exacta de activación/detención de la campaña y guarda métricas por hora.

Nada está atado a una campaña: las campañas se leen de Wolkvox y todo lo demás (carpetas, columnas,
jefes, rutas de la API) está en `src/main/resources/application.properties`.

## Documentos
- `docs/diagrama-sistema.html`: diagrama de sistema, módulos y respuestas sobre la API de Wolkvox.
- `docs/capturas/`: pantallas del prototipo.

## Requisitos
- Java 21
- Maven 3.9+

## Ejecutar
```bash
mvn quarkus:dev                      # modo desarrollo, recarga en caliente
# o
mvn package && java -jar target/quarkus-app/quarkus-run.jar
```
Abrir http://localhost:8080 . La documentación de la API queda en http://localhost:8080/q/swagger-ui.

Por defecto usa **Wolkvox simulado** (no envía nada) y una base H2 en `./datos`.
Los CSV se dejan en `./datos/entrada` (o en la carpeta que indique `BITACORA_ENTRADA`).

### Variables útiles
| Variable | Para qué |
|---|---|
| `BITACORA_ENTRADA` | Carpeta de CSV, p. ej. la carpeta de SharePoint sincronizada |
| `BITACORA_EXCEL` | Ruta del Excel de la bitácora |
| `WOLKVOX_MODO` | `simulado` o `http` |
| `WOLKVOX_SERVIDOR` | Número después de "wv" en la URL de Manager (wv**0003**.wolkvox.com → `0003`) |
| `WOLKVOX_TOKEN` | Token de Manager → Configuración → Integraciones → Tokens |

Para PostgreSQL: `mvn package -Dquarkus.profile=pg` y definir `DB_URL`, `DB_USER`, `DB_PASSWORD`.

## Flujo
1. **Cargar listas**: escanear la carpeta, revisar cuántos registros son válidos, elegir campaña,
   estrategia (NUEVA / REMARCACIÓN), jefe, nombre de estrategia y horario planeado, y cargar.
   Los no válidos quedan en `datos/rechazados/<lista>_RECHAZADOS_<fecha>.csv` con el motivo por línea.
   El archivo cargado pasa a `datos/procesados`. Un mismo archivo no se carga dos veces a la misma campaña.
2. **Bitácora**: filas del día; iniciar/detener la campaña (guarda la hora exacta), marcar el informe PDT → OK,
   actualizar o descargar el Excel.
3. **Agenda del día**: horario planeado frente a lo que estuvo activo en Wolkvox.
4. **Métricas por hora**: agentes conectados, registros barridos y gestionados por corte de hora.
   Se recolectan solas al minuto 2 de cada hora.

## Excel
Hoja `Bitacora` con el encabezado de siempre más REMARCACIONES:
FECHA, HORA INICIO, HORA FINAL, ID_CAMPAÑA, NOMBRE DE LISTA, CAMPAÑA, ESTRATEGIA, REMARCACIONES, JEFE,
REGISTROS CARGADOS, TOTAL GESTIONADO, ESTRATEGIA MARCACION, % GESTIÓN, DESCARGA INFORME, NOMBRE ESTRATEGIA, OBSERVACION.
Hoja `Detalle` con horario planeado, registros no válidos y huella del archivo.
La base de datos es la fuente de verdad: el Excel se reescribe completo en cada cambio. Si está abierto,
la app avisa y se actualiza con el botón "Actualizar Excel" cuando se cierre.

## Arquitectura (hexagonal)
```
dominio/        modelo y puertos (WolkvoxPuerto, ArchivosPuerto, BitacoraExcelPuerto) — sin frameworks
aplicacion/     casos de uso: CargueServicio, CampanaServicio, BitacoraServicio, MetricasServicio, ValidadorCsv
infraestructura/
  wolkvox/      WolkvoxSimuladoAdaptador, WolkvoxHttpAdaptador (reintentos, timeout, máx. 2 llamadas simultáneas)
  archivos/     CarpetaLocalAdaptador (CSV con coma o punto y coma, UTF-8 o ANSI)
  excel/        ExcelBitacoraAdaptador (Apache POI)
  persistencia/ entidades y repositorios Panache, migraciones Flyway
web/            recursos REST + index.html
```

## Pendiente
- Confirmar contra la colección de Postman de Wolkvox las rutas y campos marcados `PENDIENTE` en
  `WolkvoxHttpAdaptador` y en `application.properties` (cargue de registros, detener campaña,
  reporte de agentes por hora, detalle de interacciones).
- Login y roles (OIDC) antes de ponerlo en un servidor compartido.
