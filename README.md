# Bitácora Digital de Cargues Wolkvox

Aplicación web en Quarkus que toma los CSV de una carpeta, los valida con la plantilla de Wolkvox,
los carga a cualquier campaña (predictiva o preview), deja cada cargue en la bitácora y en un Excel,
registra la hora exacta de activación/detención de la campaña y guarda métricas por hora.

Se organiza en **operaciones**: cada operación tiene su nombre, su servidor `wvXXXX` y su token, y dentro de ella se
agregan sus campañas con **nombre e ID**. El token se prueba al guardar la operación y el ID se comprueba en ese servidor.
La carpeta de bases se elige desde la app (`/api/ajustes`) y se leen .csv, .txt y .xlsx. Cada cargue se hace desde una
ventana que pide la campaña, la **hora de encendido** y los datos de la bitácora; si la campaña está apagada se programa su
encendido para ese día (solo encendido). El tipo de lista sale de la plantilla: 36 columnas = Predictiva, 35 = Preview.
La pestaña "Campañas activas" muestra lo que está encendido con sus listas del día y la observación de cada una, que va a la
columna OBSERVACION. El Excel respeta el encabezado:
FECHA · HORA INICIO · HORA FINAL · ID_CAMPAÑA · NOMBRE DE LISTA · CAMPAÑA · ESTRATEGIA · JEFE · REGISTROS CARGADOS ·
TOTAL GESTIONADO · ESTRATEGIA MARCACION · % GESTIÓN · DESCARGA INFORME · NOMBRE ESTRATEGIA · OBSERVACION.
No hay modo simulado: todo va contra Wolkvox. Los errores de la API se traducen a mensajes con qué hacer.

## Documentos
- `docs/diagrama-sistema.html`: diagrama de sistema, módulos y respuestas sobre la API de Wolkvox.
- `docs/capturas/`: pantallas del prototipo.

## Requisito## Usarlo en Windows (sin instalar nada)
El paquete `BitacoraDigital.zip` trae todo: `bitacora-digital.jar`, `Iniciar-Bitacora.bat`, `iniciar.ps1`,
`config/application.properties` y `LEEME.txt` (los archivos de `distribucion/` más el jar).

1. Doble clic en `Iniciar-Bitacora.bat` → abre http://localhost:4444.
2. "Operaciones": crear cada operación (nombre, servidor, token) y agregarle sus campañas (nombre e ID).
3. "Cargar bases": elegir la carpeta, pulsar "Cargar…" en una base y llenar la ventana.

- Base de datos **H2 embebida** (archivo), sin instalar motor. El lanzador la deja en
  `%LOCALAPPDATA%\BitacoraDigital\db` para que OneDrive no la sincronice. Migraciones con Flyway.
- Java: usa `jre\` si existe, si no Java 21+ del equipo; si no hay, descarga una vez Eclipse Temurin 21 portátil
  a `jre\` (sin permisos de administrador).
- Puerto **4444** (`quarkus.http.port`), solo `localhost` salvo que se cambie `quarkus.http.host`.

## Compilar
Requiere Java 21 y Maven 3.9+.
```bash
mvn package                      # pruebas + target/bitacora-digital-runner.jar (uber-jar)
java -jar target/bitacora-digital-runner.jar
```
Para armar el paquete de Windows: copiar `distribucion/*` y el jar renombrado a `bitacora-digital.jar` en una carpeta.

### Configuración
Todo está en `src/main/resources/application.properties`; en el equipo se sobreescribe con `config/application.properties`
(junto al jar) o con variables de entorno.

| Propiedad / variable | Para qué |
|---|---|
| `bitacora.wolkvox.url-base` | `https://wv{servidor}.wolkvox.com/api/v2/` (el servidor sale de cada campaña guardada) |
| `bitacora.campanas.escaneo` | Intervalo del escaneo de campañas activas (`5m`; `off` lo apaga) |
| `bitacora.carpetas.entrada` / `BITACORA_ENTRADA` | Carpeta de CSV, p. ej. la carpeta de SharePoint sincronizada |
| `bitacora.excel.ruta` / `BITACORA_EXCEL` | Ruta del Excel de la bitácora |
| `BITACORA_DB` | Ruta del archivo H2 (sin extensión) |
| `bitacora.wolkvox.columnas` | Columna del CSV → campo de la API `add_record` |

### API de Wolkvox usada (colección oficial de Postman)
| Uso | Llamada |
|---|---|
| Listar campañas y su estado | `GET information.php?api=campaigns` (`status` = Started / Stopped) |
| Cargar registros (máx. 10.000 por llamada) | `POST campaign.php?api=add_record&type_campaign=…&campaign_id=…[&campaign_status=start/stop]` |
| Iniciar / detener | `PUT campaign.php?api=start` / `api=stop` `&campaign_id=…` |
| Agentes y gestionados por hora | `GET reports_manager.php?api=agent_4` (por ventana de una hora) |
| Registros barridos por hora | `GET reports_manager.php?api=campaign_4&campaign_id=…` |

**Cargar listas**: escanear la carpeta, revisar cuántos registros son válidos, elegir campaña,
   estrategia (NUEVA / REMARCACIÓN), jefe, nombre de estrategia y horario planeado, y cargar.
   Los no válidos quedan en `datos/rechazados/<lista>_RECHAZADOS_<fecha>.csv` con el motivo por línea.
   El archivo cargado pasa a `datos/procesados`. Un mismo archivo no se carga dos veces a la misma campaña.
2. **Campañas en vivo**: estado actual de todas las campañas del servidor e histórico de activación del día.
   Cada 5 minutos se consulta Wolkvox; si una campaña se encendió o apagó por fuera de la app queda como
   "detectado en Wolkvox" y la hora pasa a la bitácora. Las pantallas se actualizan solas (Server-Sent Events,
   `GET /api/campanas/eventos`).
3. **Bitácora**: filas del día; elegir una **hora de gestión** muestra las listas activas en esa hora, agentes,
   barridos, gestionados y activaciones/detenciones (`GET /api/bitacora/hora?fecha=&hora=`); iniciar/detener la campaña (guarda la hora exacta), marcar el informe PDT → OK,
   actualizar o descargar el Excel.
4. **Agenda del día**: horario planeado frente a lo que estuvo activo en Wolkvox.
5. **Métricas por hora**: agentes conectados, registros barridos y gestionados por corte de hora.
   Se recolectan solas al minuto 2 de cada hora (la hora que cerró y la que está en curso).

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
  wolkvox/      WolkvoxHttpAdaptador (reintentos, timeout, máx. 2 llamadas simultáneas por token, traducción de errores)
  archivos/     CarpetaLocalAdaptador (CSV con coma o punto y coma, UTF-8 o ANSI)
  excel/        ExcelBitacoraAdaptador (Apache POI)
  persistencia/ entidades y repositorios Panache, migraciones Flyway
web/            recursos REST + index.html
```

## Pendiente
- Wolkvox no reporta gestión por lista: TOTAL GESTIONADO se calcula con las llamadas salientes de agentes en la
  campaña mientras la lista estuvo activa. Si dos listas comparten campaña al mismo tiempo, cada una recibe el total de la campaña (con tope en sus registros cargados).
- Login y roles antes de abrirlo a otros equipos de la red (`quarkus.http.host=0.0.0.0`).
