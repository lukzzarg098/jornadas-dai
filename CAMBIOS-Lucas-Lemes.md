# Jornadas del Sur · Cambios realizados

**Repositorio:** https://github.com/lukzzarg098/jornadas-dai

**Autor:** Lucas Lemes

Cada pedido del enunciado se trabajó en su propia branch. Para cada cambio se indica el problema encontrado, el riesgo que implicaba, qué se modificó y cómo se verificó.

| Punto | Branch | Estado |
|---|---|---|
| 0 · Compilación | `main` | Hecho |
| 42 · Agenda personal | `pedido-42-agenda-personal` | Hecho |
| 41 · Programa sin señal | `pedido-41-sin-senal` | Hecho (sale de la 42) |
| 43 · Volver al punto de lectura | `pedido-43-punto-de-lectura` | Pendiente |
| 44 · Control de descargas | `pedido-44-control-descargas` | Pendiente |

---

## 0 · El proyecto no compilaba

- **Problema:** `lab/ServicioLocal.kt` usaba `toMediaType()` y `toResponseBody()`, que son extensiones de Kotlin que recién existen en OkHttp 4. Retrofit 2.11.0 trae OkHttp 3.14, así que esas funciones no existen y el proyecto no compilaba (`Unresolved reference: Companion`).
- **Riesgo:** sin compilar no se puede entregar ni probar ningún pedido.
- **Cambio:** se reemplazó por la API equivalente de OkHttp 3: `ResponseBody.create(MediaType.get("application/json"), json)`. Se quitaron los dos imports de `Companion`. Se descartó agregar OkHttp 4 en Gradle porque sumaba una dependencia y tocaba la configuración del build.
- **Verificación:** el proyecto compila y la app arranca en el emulador.

---

## 42 · Agenda personal

### Qué pide
Cada charla tiene datos que son **del servidor** (título, sala, hora, ponente…) y un dato que es **del usuario** (la marca "En mi agenda"). Al llegar una edición nueva del programa, el servidor puede cambiar sus datos, pero la marca del usuario no se toca. De esa regla salen todos los casos:

- Si una charla cambia (v1 → v2), se actualizan título y sala y conserva la marca.
- Si una charla se retira (v3), desaparece del catálogo y de Mi agenda.
- Si una charla retirada vuelve, vuelve sin marcar.
- Si el usuario marca o desmarca durante una petición, su acción se respeta al llegar la respuesta.

### Cambio: merge por id en lugar de reemplazar la fila

- **Problema:** `AgendaRepository.sincronizar()` construía cada `Charla` con `favorita = false` (valor por defecto) y la guardaba con `@Insert(onConflict = REPLACE)` en `CarteleraDao.guardar()`. `REPLACE` borra la fila existente y escribe una nueva, así que cada descarga borraba todas las marcas del usuario. Además nunca se eliminaba nada: una charla retirada del programa (la 11 en v3) seguía apareciendo.
- **Riesgo:** pérdida de datos del usuario. La organización publica **instantáneas completas**, así que la base local debe reflejar exactamente la última instantánea en lo que es del servidor, sin pisar lo que es del usuario. Con `REPLACE` se pisaba la única información que el servidor no puede reponer.
- **Cambio** (`data/Programa.kt` y `data/AgendaRepository.kt`): se reemplazó `guardar()` por `aplicarPrograma()`, un merge por id en tres pasos dentro de una `@Transaction`:
  1. `borrarAusentes(ids)`: `DELETE ... WHERE id NOT IN (:ids)`. Retira lo que no vino en la instantánea, junto con su marca, así que si la charla vuelve entra sin marcar.
  2. `insertarNuevas(fichas)`: `@Insert(onConflict = IGNORE)`. Agrega solo las charlas nuevas (sin marca); las existentes no se tocan en este paso.
  3. `actualizarDatos(...)`: un `UPDATE` que cambia título, tema, día, hora, sala, ponente y resumen. **La columna `favorita` no aparece en el `SET`**, así que el merge no puede sobrescribir una marca.

  La `@Transaction` garantiza atomicidad: los tres pasos se aplican juntos o ninguno, y la pantalla, que observa la tabla con un `Flow`, recibe un solo cambio. Las identidades se resuelven por `id`, que el enunciado indica que es estable aunque cambie el título.

  El caso "marcar durante una petición" queda resuelto por diseño: la marca se escribe en la base en el momento del toque (`alternar`) y el merge nunca escribe `favorita`, así que no hay forma de que la respuesta la pise.

  Para poder tener una función con cuerpo anotada con `@Transaction`, `CarteleraDao` pasó de `interface` a `abstract class`, que es el patrón seguro en Room para funciones `suspend` transaccionales.
- **Alternativas descartadas:** `@Upsert` y `@Update` usan la entidad completa, así que también escribirían `favorita = false`. `db.withTransaction { }` en el repositorio obligaba a pasarle la base entera cuando hoy solo recibe el DAO.
- **Compatibilidad:** el esquema de la tabla no cambió (mismas columnas), por lo que no hace falta migración y los usuarios que actualizan conservan sus charlas guardadas.
- **Verificación** (desde la pestaña Mesa, eligiendo la edición y tocando *Actualizar*):
  - [x] En v1 se marca «Compose en producción». Al pasar a v2, la misma charla muestra «Compose en producción avanzada» y «Sala Puerto» y conserva «En mi agenda».
  - [x] Con «Navegación entre pantallas» marcada, al pasar a v3: «Compose…» desaparece del Programa y de Mi agenda, aparece «Clínica de trazas» y «Navegación…» sigue marcada.
  - [x] Volviendo a v1, «Compose en producción» reaparece sin marca.

### Cambio: Mi agenda se resuelve en la base, no en la pantalla

- **Problema:** en `Portada.kt`, la pestaña Mi agenda calculaba `charlas.filter { it.favorita }` dentro del composable. La regla de qué charlas forman la agenda del usuario vivía en la vista.
- **Riesgo:** MVVM y fuente de verdad: la vista debe solo dibujar el estado que publica el ViewModel. Con la regla en el composable, el filtro se recalcula en cada recomposición, no se puede probar sin abrir la pantalla y cualquier otra vista que muestre la agenda tendría que repetir la misma regla. Además se traía toda la tabla para filtrarla en Kotlin, cuando el filtro corresponde a SQL.
- **Cambio:**
  - `CarteleraDao.observarAgenda()`: `SELECT * FROM charlas WHERE favorita = 1 ORDER BY dia, hora, sala`, observado con `Flow`.
  - `AgendaRepository.agenda` lo expone.
  - `ProgramaModel.agenda` lo publica como `StateFlow`.
  - `Portada` solo lo recolecta con `collectAsStateWithLifecycle()` y lo dibuja.

  Como es un `Flow` de Room, la agenda se actualiza sola cuando el merge retira una charla o el usuario cambia una marca, sin que nadie tenga que pedir "recargar".
- **Mapper DTO → modelo:** la conversión de `FichaRemota` (forma del JSON) a `Charla` quedó en una función `toCharla()` dentro del repositorio, para que el DTO no salga de la capa de datos.
- **Verificación:** con «Compose en producción» y «Navegación entre pantallas» marcadas, Mi agenda muestra ambas en v1; en v2 muestra «Compose en producción avanzada · Sala Puerto» con su marca; en v3 muestra solo «Navegación entre pantallas».
  - [x] Con demora larga (8 s), marcar y desmarcar charlas durante la petición: al llegar la respuesta, las marcas quedan como las dejó el usuario.

---

## 41 · Programa sin señal

### Qué pide
Si alguna vez se descargó el programa, la app trabaja con lo que guardó, haya señal o no. Hay cuatro casos:

| Caso | Comportamiento esperado |
|---|---|
| A · Hay una edición guardada y la descarga falla | Lista, ficha y Mi agenda siguen; aviso de que se usan datos guardados |
| B · Nunca se descargó y la descarga falla | Aviso y botón Reintentar |
| C · Llega la v4 (lista vacía) | «Sin charlas programadas» |
| D · Después de la v4, se reabre sin señal | Sigue siendo una edición válida: «Sin charlas programadas» y no Reintentar |

### Cambio 1: registrar que hubo una edición descargada (base de datos)

- **Problema:** la app no tenía forma de distinguir "descargué y vino vacío" (v4) de "nunca descargué". Las dos situaciones dejan la tabla `charlas` vacía, así que el caso D se veía como el caso B.
- **Riesgo:** mostrar un estado falso (2.7 de la guía: lista vacía y error comunican cosas distintas). El usuario que ya tenía una edición vería "Reintentar" como si nunca hubiera descargado nada.
- **Cambio** (`data/Programa.kt`, `Servicios.kt`):
  - Entidad nueva `Edicion` (tabla `edicion`, una sola fila). Que exista la fila significa que se guardó al menos una edición completa.
  - `aplicarPrograma` registra la edición **dentro de la misma `@Transaction`** que el merge del pedido 42: si se guardó el programa, se guardó la marca, y nunca queda una sin la otra.
  - `CarteleraDao.hayEdicion()` lo expone como `Flow<Boolean>`.
  - La base pasa a `version = 2` con `MIGRACION_1_2`, registrada con `addMigrations(...)`. La migración solo crea la tabla (no toca `charlas` ni las marcas) y, si el usuario ya tenía charlas guardadas, inserta la fila: quien actualiza desde la versión instalada ya tenía una edición.
- **Alternativas descartadas:** `fallbackToDestructiveMigration()` borraría la base de los usuarios que actualizan, y el enunciado pide conservar sus datos. Guardar la marca en `SharedPreferences` es más corto pero se escribe fuera de la transacción de Room y puede quedar desfasada del programa guardado.

### Cambio 2: el repositorio devuelve un resultado en vez de lanzar excepciones

- **Problema:** `sincronizar()` dejaba escapar las excepciones y el ViewModel solo atrapaba `IOException`. `ProgramaApi.consultar()` devuelve la lista directa (no `Response<...>`), así que un error HTTP llega como `HttpException` y un JSON que no coincide con el DTO como `JsonParseException`: ninguna de las dos es `IOException`, y la app se cerraba.
- **Riesgo:** los tres tipos de fallo de Retrofit (4.5 de la guía): conexión, HTTP y conversión. Atrapar uno solo deja a los otros dos como un crash.
- **Cambio** (`data/AgendaRepository.kt`): `sincronizar()` devuelve `ResultadoDescarga` (`Exito`, `SinConexion`, `RespuestaInvalida`), una `sealed interface`. Atrapa cada error esperado por su tipo, sin un `catch (e: Exception)` genérico que también se tragaría la cancelación de la corrutina. El trabajo corre en `withContext(Dispatchers.IO)`. El repositorio devuelve un resultado, no un texto: el mensaje para el usuario lo arma el ViewModel.

### Cambio 3: el estado de la pantalla es un solo objeto armado en el ViewModel

- **Problema:** `ProgramaModel` exponía `aviso` y `cargando` como `MutableStateFlow` públicos, y `Catalogo` decidía qué mostrar combinándolos. Si había aviso, mostraba el aviso **en lugar de** la lista (`Catalogo.kt`), así que una falla escondía los datos guardados (caso A).
- **Riesgo:** banderas sueltas que permiten combinaciones imposibles (cargando y con el error viejo a la vez); estado con dos dueños, porque cualquier pantalla podía modificar un `MutableStateFlow` público (2.5 y 2.9 de la guía); y la regla de qué mostrar escrita en la vista.
- **Cambio** (`ui/ProgramaModel.kt`, `ui/Catalogo.kt`):
  - `Descarga` (`Ninguna`, `EnCurso`, `Fallida`) es una `sealed interface` **privada** del ViewModel: la petición está en un solo estado a la vez y nadie de afuera lo puede modificar.
  - `CatalogoUiState` reúne todo lo que el Programa necesita: `contenido`, `descargando` y `aviso`.
  - `Contenido` es una `sealed interface` con las variantes que se excluyen: `Cargando`, `SinDescarga`, `SinCharlas`, `ConCharlas(charlas)`. El aviso es una propiedad aparte, porque convive con cualquiera de ellas (por ejemplo, la lista y "se muestran los datos guardados").
  - `catalogo` se arma con `combine` del programa guardado, `hayEdicion` y `descarga`, en una sola función (`armarCatalogo`). Es el único lugar donde está la regla.
  - `Catalogo` solo recolecta el estado con `collectAsStateWithLifecycle()` y hace un `when` sobre `Contenido`, sin `else`: si mañana se agrega una variante, no compila hasta contemplarla.
- **Verificación:** compila (`./gradlew assembleDebug`). Pruebas manuales pendientes:
  - [ ] **A:** con la v1 descargada, apagar el servicio en Mesa y tocar Actualizar: la lista sigue y aparece «Sin conexión. Se muestran los datos guardados.». La ficha y Mi agenda funcionan.
  - [ ] **B:** `adb shell pm clear ar.jornadas` y `adb shell am start -n ar.jornadas/.MainActivity --ez servicio_disponible false`: aparece el aviso y Reintentar.
  - [ ] **C:** con servicio, elegir v4 y Actualizar: «Sin charlas programadas.».
  - [ ] **D:** después de C, apagar el servicio, cerrar la app desde Recientes y reabrir: «Sin charlas programadas.» con el aviso, sin Reintentar.
  - [ ] **Migración:** instalar sobre la versión anterior sin desinstalar: la app abre y las marcas siguen.

---

## 43 · Volver al punto de lectura

_Pendiente._

---

## 44 · Control de descargas

_Pendiente._
