package ar.jornadas.data

import com.google.gson.JsonParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Cómo terminó una descarga. Es un resultado, no un texto: el mensaje lo arma el ViewModel. */
sealed interface ResultadoDescarga {
    data object Exito : ResultadoDescarga
    data object SinConexion : ResultadoDescarga        // no se llegó al servidor
    data object RespuestaInvalida : ResultadoDescarga  // HTTP de error o JSON que no coincide con el DTO
}

class AgendaRepository(private val api: ProgramaApi, private val dao: CarteleraDao) {
    // El repositorio es único por proceso (JornadasApp), así que este flag también.
    private val cargaAutomaticaPedida = AtomicBoolean(false)

    /** Devuelve true solo la primera vez que se pide en este proceso. */
    fun tomarCargaAutomatica(): Boolean = cargaAutomaticaPedida.compareAndSet(false, true)

    fun programa(dia: Int, tema: String?) = dao.observarFiltrado(dia, tema)
    fun charla(id: String) = dao.observarCharla(id)
    val hayCharlas = dao.hayCharlas()
    val agenda = dao.observarAgenda()
    val hayEdicion = dao.hayEdicion()

    // Los tres tipos de fallo de Retrofit se convierten en un resultado; la app no se cae.
    suspend fun sincronizar(): ResultadoDescarga = withContext(Dispatchers.IO) {
        try {
            val fichas = api.consultar().map { it.toCharla() }
            dao.aplicarPrograma(fichas)
            ResultadoDescarga.Exito
        } catch (e: IOException) {
            ResultadoDescarga.SinConexion
        } catch (e: HttpException) {
            ResultadoDescarga.RespuestaInvalida
        } catch (e: JsonParseException) {
            ResultadoDescarga.RespuestaInvalida
        }
    }

    suspend fun cambiarFavorita(id: String) = dao.alternar(id)
}

// Mapper DTO -> modelo: la forma del JSON no sale de la capa de datos.
// La marca no viene del servidor: las nuevas entran sin marcar y el merge no la pisa.
private fun FichaRemota.toCharla() =
    Charla(id, title, track, day, time, room, speaker, summary)
