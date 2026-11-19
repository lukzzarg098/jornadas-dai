package ar.jornadas.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ar.jornadas.data.Charla

private val DIAS = listOf("Ambos días", "Día 1", "Día 2")   // el índice es el valor del filtro
private val TEMAS = listOf<String?>(null, "Android", "Datos", "Calidad")

// Recibe estado y callbacks: solo dibuja y avisa. No conoce al ViewModel.
@Composable
fun Catalogo(
    estado: CatalogoUiState,
    elegirDia: (Int) -> Unit,
    elegirTema: (String?) -> Unit,
    actualizar: () -> Unit,
    abrir: (String) -> Unit,
) {
    Selector(DIAS, estado.dia, elegirDia)
    Selector(TEMAS.map { it ?: "Todos" }, TEMAS.indexOf(estado.tema)) { elegirTema(TEMAS[it]) }
    TextButton(onClick = actualizar) { Text("Actualizar") }
    if (estado.descargando) LinearProgressIndicator(Modifier.fillMaxWidth())
    estado.aviso?.let { Text(it) }   // el aviso se suma arriba; ya no reemplaza a la lista
    when (val contenido = estado.contenido) {
        Contenido.Cargando -> Unit
        Contenido.SinDescarga -> OutlinedButton(onClick = actualizar) { Text("Reintentar") }
        Contenido.SinCharlas -> Text("Sin charlas programadas.")
        Contenido.SinCoincidencias -> Text("Ninguna charla coincide con los filtros.")
        is Contenido.ConCharlas -> Grilla(contenido.charlas, abrir)
    }
}

@Composable
private fun Selector(opciones: List<String>, indice: Int, elegir: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        opciones.forEachIndexed { i, texto ->
            FilterChip(selected = i == indice, onClick = { elegir(i) }, label = { Text(texto) })
        }
    }
}

@Composable
fun Grilla(fichas: List<Charla>, abrir: (String) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 12.dp)) {
        items(fichas, key = { it.id }) { ficha ->
            ElevatedCard(onClick = { abrir(ficha.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Día ${ficha.dia} · ${ficha.hora} · ${ficha.sala}", style = MaterialTheme.typography.labelMedium)
                    Text(ficha.titulo, style = MaterialTheme.typography.titleLarge)
                    Text(if (ficha.favorita) "${ficha.tema} · En mi agenda" else ficha.tema)
                }
            }
        }
    }
}
