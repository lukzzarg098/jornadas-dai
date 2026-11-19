package ar.jornadas.ui

import android.app.Application
import androidx.lifecycle.*
import ar.jornadas.Servicios
import ar.jornadas.data.Charla
import ar.jornadas.data.ResultadoDescarga
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Qué se muestra en el cuerpo del Programa: un solo caso a la vez. */
sealed interface Contenido {
    data object Cargando : Contenido                           // sin edición, esperando la primera respuesta
    data object SinDescarga : Contenido                        // sin edición y la descarga falló: Reintentar
    data object SinCharlas : Contenido                         // edición válida pero vacía (v4)
    data class ConCharlas(val charlas: List<Charla>) : Contenido
}

/** Todo lo que el Programa necesita para dibujarse. */
data class CatalogoUiState(
    val contenido: Contenido = Contenido.Cargando,
    val descargando: Boolean = false,
    val aviso: String? = null,
)

class ProgramaModel(app: Application) : AndroidViewModel(app) {
    /** Estado de la última petición. Privado: la pantalla solo ve CatalogoUiState. */
    private sealed interface Descarga {
        data object Ninguna : Descarga
        data object EnCurso : Descarga
        data class Fallida(val sinConexion: Boolean) : Descarga
    }

    private val servicios = Servicios(app)
    val mesa = servicios.mesa
    private val repo = servicios.agenda
    val charlas = repo.programa.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val agenda = repo.agenda.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val dia = MutableStateFlow(0)
    private val descarga = MutableStateFlow<Descarga>(Descarga.Ninguna)

    val catalogo: StateFlow<CatalogoUiState> =
        combine(repo.programa, repo.hayEdicion, descarga) { lista, hayEdicion, d ->
            armarCatalogo(lista, hayEdicion, d)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, CatalogoUiState())

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
    private fun armarCatalogo(lista: List<Charla>, hayEdicion: Boolean, d: Descarga) = CatalogoUiState(
        contenido = when {
            hayEdicion && lista.isEmpty() -> Contenido.SinCharlas
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
}
