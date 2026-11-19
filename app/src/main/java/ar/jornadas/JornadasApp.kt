package ar.jornadas

import android.app.Application

// Vive lo mismo que el proceso: acá se arman una sola vez la base, Retrofit y el repositorio.
class JornadasApp : Application() {
    val servicios by lazy { Servicios(this) }
}
