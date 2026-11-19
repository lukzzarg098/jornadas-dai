package ar.jornadas.ui

import android.app.Application
import androidx.lifecycle.*
import ar.jornadas.Servicios
import ar.jornadas.data.Charla
import ar.jornadas.data.ResultadoDescarga
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Qué se muestra en el cuerpo del Programa: un solo caso a la vez. */
sealed interface Contenido {
    data object Cargando : Contenido                           // sin edición, esperando la primera respuesta
    data object SinDescarga : Contenido                        // sin edición y la descarga falló: Reintentar
    data object SinCharlas : Contenido                         // edición válida pero vacía (v4)
    data object SinCoincidencias : Contenido                   // hay charlas, pero ninguna cumple los filtros
    data class ConCharlas(val charlas: List<Charla>) : Contenido
}

/** Todo lo que el Programa necesita para dibujarse. Los filtros conviven con cualquier contenido. */
data class CatalogoUiState(
    val dia: Int = 0,              // 0 = ambos días
    val tema: String? = null,      // null = todos los temas
    val contenido: Contenido = Contenido.Cargando,
    val descargando: Boolean = false,
    val aviso: String? = null,
)

/** La ficha abierta. charla es null mientras carga o si la charla se retiró del programa. */
data class DetalleUiState(val id: String, val charla: Charla?)

@OptIn(ExperimentalCoroutinesApi::class)
class ProgramaModel(app: Application, private val guardado: SavedStateHandle) : AndroidViewModel(app) {
    /** Estado de la última petición. Privado: la pantalla solo ve CatalogoUiState. */
    private sealed interface Descarga {
        data object Ninguna : Descarga
        data object EnCurso : Descarga
        data class Fallida(val sinConexion: Boolean) : Descarga
    }

    private data class Filtros(val dia: Int, val tema: String?)

    private val servicios = Servicios(app)
    val mesa = servicios.mesa
    private val repo = servicios.agenda
    private val descarga = MutableStateFlow<Descarga>(Descarga.Ninguna)

    // Punto de lectura: filtros y ficha abierta viven en el SavedStateHandle, así sobreviven
    // a la rotación y también a que Android mate el proceso. Una tarea nueva arranca vacía.
    private val dia = guardado.getStateFlow(CLAVE_DIA, 0)
    private val tema = guardado.getStateFlow<String?>(CLAVE_TEMA, null)
    private val detalleId = guardado.getStateFlow<String?>(CLAVE_DETALLE, null)
    private val filtros = combine(dia, tema) { d, t -> Filtros(d, t) }

    val agenda = repo.agenda.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val catalogo: StateFlow<CatalogoUiState> =
        combine(
            filtros.flatMapLatest { repo.programa(it.dia, it.tema) },   // al cambiar un filtro, cambia la consulta
            repo.hayCharlas, repo.hayEdicion, descarga, filtros,
        ) { lista, hayCharlas, hayEdicion, d, f ->
            armarCatalogo(lista, hayCharlas, hayEdicion, d, f)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, CatalogoUiState(dia = dia.value, tema = tema.value))

    val detalle: StateFlow<DetalleUiState?> =
        detalleId.flatMapLatest { id ->
            if (id == null) flowOf(null) else repo.charla(id).map { DetalleUiState(id, it) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, detalleId.value?.let { DetalleUiState(it, null) })

    fun elegirDia(valor: Int) { guardado[CLAVE_DIA] = valor }
    fun elegirTema(valor: String?) { guardado[CLAVE_TEMA] = valor }
    fun abrir(id: String) { guardado[CLAVE_DETALLE] = id }
    fun cerrarDetalle() { guardado[CLAVE_DETALLE] = null }

    fun recargar() {
        descarga.value = Descarga.EnCurso
        viewModelScope.launch {
            descarga.value = when (repo.sincronizar()) {
                ResultadoDescarga.Exito -> Descarga.Ninguna
                ResultadoDescarga.SinConexion -> Descarga.Fallida(sinConexion = true)
                ResultadoDescarga.RespuestaInvalida -> Descarga.Fallida(sinConexion = false)
            }
        }
    }

    fun marcar(id: String) { viewModelScope.launch { repo.cambiarFavorita(id) } }

    // La única regla que decide qué muestra el Programa. Con una edición guardada, la lista
    // se muestra siempre: un fallo solo suma un aviso, no esconde los datos.
    private fun armarCatalogo(
        lista: List<Charla>, hayCharlas: Boolean, hayEdicion: Boolean, d: Descarga, f: Filtros,
    ) = CatalogoUiState(
        dia = f.dia,
        tema = f.tema,
        contenido = when {
            hayEdicion && !hayCharlas -> Contenido.SinCharlas
            hayEdicion && lista.isEmpty() -> Contenido.SinCoincidencias
            hayEdicion -> Contenido.ConCharlas(lista)
            d is Descarga.Fallida -> Contenido.SinDescarga
            else -> Contenido.Cargando
        },
        descargando = d == Descarga.EnCurso,
        aviso = if (d is Descarga.Fallida) avisoDeFalla(d, hayEdicion) else null,
    )

    private fun avisoDeFalla(falla: Descarga.Fallida, hayEdicion: Boolean): String {
        val causa = if (falla.sinConexion) "Sin conexión." else "No se pudo actualizar el programa."
        return if (hayEdicion) "$causa Se muestran los datos guardados."
        else "$causa Todavía no hay un programa guardado."
    }

    private companion object {
        const val CLAVE_DIA = "dia"
        const val CLAVE_TEMA = "tema"
        const val CLAVE_DETALLE = "detalle"
    }
}
