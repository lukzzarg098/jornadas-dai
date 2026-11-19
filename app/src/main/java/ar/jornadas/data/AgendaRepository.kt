package ar.jornadas.data

class AgendaRepository(private val api: ProgramaApi, private val dao: CarteleraDao) {
    val programa = dao.observar()
    val agenda = dao.observarAgenda()

    suspend fun sincronizar() {
        val fichas = api.consultar().map { it.toCharla() }
        dao.aplicarPrograma(fichas)
    }

    suspend fun cambiarFavorita(id: String) = dao.alternar(id)
}

// Mapper DTO -> modelo: la forma del JSON no sale de la capa de datos.
// La marca no viene del servidor: las nuevas entran sin marcar y el merge no la pisa.
private fun FichaRemota.toCharla() =
    Charla(id, title, track, day, time, room, speaker, summary)
