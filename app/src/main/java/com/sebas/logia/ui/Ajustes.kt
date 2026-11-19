package com.sebas.logia.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.sebas.logia.BuildConfig
import com.sebas.logia.Casa
import com.sebas.logia.Huella

@Composable
fun Ajustes() {
    val act = LocalContext.current as? FragmentActivity
    var huella by remember { mutableStateOf(Casa.huellaActiva()) }
    var salir by remember { mutableStateOf(false) }
    val hayHuella = remember { act != null && Huella.disponible(act) }
    val pendientes = Casa.pendientes()

    Box(Modifier.fillMaxSize()) {
        Fondo {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Enlace("‹ Sala", Modifier.offset(x = (-10).dp), color = C.CremaSuave) { Nav.volver() }
                Titulo("Ajustes")

                Bloque {
                    Etiqueta("La nube")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Vela(encendida = !Casa.sincronizando && pendientes == 0)
                        Spacer(Modifier.width(10.dp))
                        Nota(Casa.estado.ifBlank { "Todavía no se ha sincronizado." }, color = C.Crema)
                    }
                    Nota("Cuenta: ${Casa.email()}")
                    Boton(if (Casa.sincronizando) "Guardando…" else "Sincronizar ahora", Modifier.fillMaxWidth(), primario = false, habilitado = !Casa.sincronizando) {
                        Casa.sincronizar()
                    }
                }

                Bloque {
                    Etiqueta("El telón")
                    if (hayHuella) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Nota("Abrir con huella", Modifier.weight(1f), color = C.Crema)
                            Switch(
                                checked = huella,
                                onCheckedChange = { huella = it; Casa.usarHuella(it) },
                                colors = SwitchDefaults.colors(checkedTrackColor = C.Rojo, checkedThumbColor = C.Crema),
                            )
                        }
                    }
                    Boton("Cambiar PIN", Modifier.fillMaxWidth(), primario = false) { Nav.ir(Vista.CambiarPin) }
                    Boton("Cerrar el telón ahora", Modifier.fillMaxWidth(), primario = false) { Casa.cerrar() }
                }

                Bloque {
                    Etiqueta("Cómo se cuida tu casa")
                    Nota(
                        "Todo se cifra en este celular con tu frase llave antes de subir a Supabase. " +
                            "Allá solo hay datos ilegibles. Si pierdes el celular, instala Logia en otro, " +
                            "entra con tu correo, tu contraseña y tu frase llave, y todo vuelve."
                    )
                    Nota("Si olvidas la frase llave, el código de recuperación que anotaste en papel es la única otra forma de entrar.")
                }

                Bloque {
                    Etiqueta("Este celular")
                    Boton("Salir de este celular", Modifier.fillMaxWidth(), primario = false) { salir = true }
                }

                Text("Logia ${BuildConfig.VERSION_NAME}", color = C.Humo, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 40.dp))
            }
        }

        Capa(salir, { salir = false }) {
            Titulo("¿Salir de este celular?", tam = 24)
            Nota("Se borra la casa de este teléfono. Lo que ya está en la nube vuelve cuando entres de nuevo con tu frase llave.")
            if (pendientes > 0) {
                Nota(
                    "Ojo: hay $pendientes ${if (pendientes == 1) "página que aún no sube" else "páginas que aún no suben"} a la nube y se perderían. Sincroniza primero.",
                    color = C.Dorado,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Boton("Quedarme", Modifier.weight(1f), primario = false) { salir = false }
                Boton("Salir", Modifier.weight(1f)) {
                    salir = false
                    Nav.reiniciar()
                    Casa.salirDeEsteCelular()
                }
            }
        }
    }
}

@Composable
private fun Bloque(contenido: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(C.Velo)
            .border(1.dp, C.Linea, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = contenido,
    )
}
