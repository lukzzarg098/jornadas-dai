package ar.jornadas.data

import androidx.room.*
import retrofit2.http.GET
import kotlinx.coroutines.flow.Flow

data class FichaRemota(val id: String, val title: String, val track: String, val day: Int,
    val time: String, val room: String, val speaker: String, val summary: String)

interface ProgramaApi {
    @GET("programa") suspend fun consultar(): List<FichaRemota>
}

@Entity(tableName = "charlas")
data class Charla(@PrimaryKey val id: String, val titulo: String, val tema: String, val dia: Int,
    val hora: String, val sala: String, val ponente: String, val resumen: String,
    val favorita: Boolean = false)

@Dao
abstract class CarteleraDao {
    @Query("SELECT * FROM charlas ORDER BY dia, hora, sala")
    abstract fun observar(): Flow<List<Charla>>

    @Query("UPDATE charlas SET favorita = NOT favorita WHERE id = :id")
    abstract suspend fun alternar(id: String)

    // Paso 1: lo que no vino en la instantánea se retira (con su marca).
    @Query("DELETE FROM charlas WHERE id NOT IN (:ids)")
    abstract suspend fun borrarAusentes(ids: List<String>)

    // Paso 2: las charlas nuevas entran sin marca; las existentes se ignoran acá.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertarNuevas(fichas: List<Charla>)

    // Paso 3: se actualizan los datos del servidor; favorita NO aparece.
    @Query("UPDATE charlas SET titulo = :titulo, tema = :tema, dia = :dia, hora = :hora, " +
        "sala = :sala, ponente = :ponente, resumen = :resumen WHERE id = :id")
    abstract suspend fun actualizarDatos(id: String, titulo: String, tema: String, dia: Int,
        hora: String, sala: String, ponente: String, resumen: String)

    // Merge por id: los datos del servidor se actualizan, las marcas del usuario no se tocan.
    @Transaction
    open suspend fun aplicarPrograma(fichas: List<Charla>) {
        borrarAusentes(fichas.map { it.id })
        insertarNuevas(fichas)
        fichas.forEach {
            actualizarDatos(it.id, it.titulo, it.tema, it.dia, it.hora, it.sala, it.ponente, it.resumen)
        }
    }
}

@Database(entities = [Charla::class], version = 1, exportSchema = false)
abstract class Archivo : RoomDatabase() {
    abstract fun cartelera(): CarteleraDao
}
