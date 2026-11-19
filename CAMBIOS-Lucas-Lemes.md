# Jornadas del Sur · Cambios realizados

**Repositorio:** https://github.com/lukzzarg098/jornadas-dai

**Autor:** Lucas Lemes

Cada pedido del enunciado se trabajó en su propia branch. Para cada cambio se indica el problema encontrado, el riesgo que implicaba, qué se modificó y cómo se verificó.

| Punto | Branch | Estado |
|---|---|---|
| 0 · Compilación | `main` | Hecho |
| 42 · Agenda personal | `pedido-42-agenda-personal` | Hecho |
| 41 · Programa sin señal | `pedido-41-sin-senal` | Pendiente |
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
  - [x] Con demora larga (8 s), marcar y desmarcar charlas durante la petición: al llegar la respuesta, las marcas quedan como las dejó el usuario.

---

## 41 · Programa sin señal

_Pendiente._

---

## 43 · Volver al punto de lectura

_Pendiente._

---

## 44 · Control de descargas

_Pendiente._
