package com.sebas.solaris.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.sebas.solaris.BuildConfig
import com.sebas.solaris.Casa
import com.sebas.solaris.Huella
import com.sebas.solaris.Exportador
import com.sebas.solaris.Sonidos
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch

@Composable
fun Ajustes() {
    val p = Tema.p
    val act = LocalContext.current as? FragmentActivity
    var huella by remember { mutableStateOf(Casa.huellaActiva()) }
    var salir by remember { mutableStateOf(false) }
    var letra by remember { mutableStateOf(false) }
    val hayHuella = remember { act != null && Huella.disponible(act) }
    val pendientes = Casa.pendientes()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var aviso by remember { mutableStateOf<String?>(null) }
    var editandoBoya by remember { mutableStateOf(false) }
    var boya by remember { mutableStateOf<com.sebas.solaris.Boya?>(null) }
    var notion by remember { mutableStateOf<Pair<List<com.sebas.solaris.Notion.Pagina>, Map<String, ByteArray>>?>(null) }
    val carpeta = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        Casa.pausarCandado = false
        if (uri != null) scope.launch { aviso = "Exportando…"; aviso = Exportador.exportar(ctx, uri) }
    }
    BackHandler(enabled = !salir && !letra && !editandoBoya && notion == null) { Nav.ajustes = false }

    Box(Modifier.fillMaxSize().background(p.fondo).systemBarsPadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Redondo(alTocar = { Nav.ajustes = false }) { IconoAtras(p.texto) }
                Spacer(Modifier.width(14.dp))
                TituloSerif("Ajustes", tam = 34)
            }
            Spacer(Modifier.height(10.dp))

            Grupo("La nube") {
                Fila(Casa.estado.ifBlank { "Todavía no se ha sincronizado" }, if (Casa.sincronizando) "…" else "")
                Fila("Cuenta", Casa.email())
                FilaAccion("Sincronizar ahora") { Casa.sincronizar() }
            }
            Grupo("Lectura") {
                FilaAccion("Letra, papel y modo") { letra = true }
                Fila("Modo", when (Tema.modo) { "dia" -> "Día"; "noche" -> "Noche"; else -> "Según la hora" })
                Fila("Papel de día", NombresPapel[Tema.papel.coerceIn(0, 2)])
                Fila("De noche", NombresOscuro[Tema.oscuro.coerceIn(0, 1)])
            }
            Grupo("El mar del Drake") {
                val prog = com.sebas.solaris.Paquete.progreso
                val completo = com.sebas.solaris.Paquete.completo
                val mb = com.sebas.solaris.Paquete.peso / 1_000_000
                Parrafo(
                    when {
                        prog != null -> "Bajando el mar… " + (prog * 100).toInt() + " %"
                        completo -> "El mar está entero en tu celular" + (if (mb > 0) " ($mb MB)" else "") + ": todos sus estados y las transiciones entre ellos."
                        else -> "Los estados del mar y sus transiciones se bajan una vez" + (if (mb > 0) " (unos $mb MB)" else "") +
                            ", solo con Wi-Fi. Mientras tanto se ve la calma."
                    },
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                com.sebas.solaris.Paquete.error?.let { Parrafo(it, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) }
                if (prog == null && !completo) FilaAccion("Bajarlo ahora, aunque no haya Wi-Fi") { com.sebas.solaris.Paquete.descargar(ahora = true) }
            }
            GrupoBoyas { b -> boya = b; editandoBoya = true }
            GrupoNotion { paginas, archivos -> notion = paginas to archivos }
            Grupo("Voces y rumores") {
                FilaAccion("Exportar todas a una carpeta") { Casa.pausarCandado = true; carpeta.launch(null) }
                if (aviso != null) Parrafo(aviso ?: "", Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
            Grupo("Entrada") {
                if (hayHuella) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Entrar con huella", color = p.texto, fontFamily = Sans, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = huella,
                            onCheckedChange = { huella = it; Casa.usarHuella(it); Sonidos.tic() },
                            colors = SwitchDefaults.colors(checkedTrackColor = Azules.Cianotipo, checkedThumbColor = Color.White),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Proteger la pantalla", color = p.texto, fontFamily = Sans, fontSize = 16.sp)
                        Text("Bloquea las capturas y oculta la estación en apps recientes", color = p.texto2, fontFamily = Sans, fontSize = 13.sp)
                    }
                    Switch(
                        checked = Tema.protegerPantalla,
                        onCheckedChange = { Tema.fijarProteger(it); Sonidos.tic() },
                        colors = SwitchDefaults.colors(checkedTrackColor = Azules.Cianotipo, checkedThumbColor = Color.White),
                    )
                }
                FilaAccion("Cambiar PIN") { Nav.cambiarPin = true }
                FilaAccion("Cerrar la estación ahora") { Casa.cerrar() }
            }
            Grupo("Cómo se cuida") {
                Parrafo(
                    "Notas y fotos se cifran en este celular con tu frase llave antes de subir: en la nube solo hay datos ilegibles. " +
                        "Las voces y los rumores se quedan en este celular; a la nube suben su nombre y, en las voces, su transcripción. " +
                        "Para transcribir, el audio viaja una vez (reducido a 16 kHz) a Whisper por medio de tu Supabase, y no se guarda allá. " +
                        "El océano lee el mar real del Paso de Drake cada hora en Open-Meteo.",
                    Modifier.padding(16.dp),
                )
            }
            Grupo("Este celular") {
                FilaAccion("Salir de este celular", Color(0xFFE0745F)) { salir = true }
            }
            Senal("Solaris ${BuildConfig.VERSION_NAME}", Modifier.padding(start = 6.dp, top = 10.dp, bottom = 40.dp))
        }

        HojaLetra(letra) { letra = false }
        HojaBoya(editandoBoya, boya) { editandoBoya = false }
        HojaNotion(notion?.first, notion?.second ?: emptyMap()) { notion = null }

        Hoja(salir, { salir = false }) {
            Text("¿Salir de este celular?", color = p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp)
            Parrafo("Se borra la estación de este teléfono, incluidas las cintas de audio. Las notas que ya están en la nube vuelven cuando entres de nuevo con tu frase llave.")
            if (pendientes > 0) Text("Hay $pendientes cambios que aún no suben a la nube y se perderían. Sincroniza primero.", color = Color(0xFFE0745F), fontFamily = Sans, fontSize = 14.sp)
            Pildora("Salir", Modifier.fillMaxWidth(), primaria = false) { salir = false; Nav.reiniciar(); Casa.salirDeEsteCelular() }
            Pildora("Quedarme", Modifier.fillMaxWidth()) { salir = false }
        }
    }
}

@Composable
private fun Grupo(titulo: String, contenido: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(titulo, color = Tema.p.texto2, fontFamily = Sans, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp, top = 10.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Tema.p.superficie), content = contenido)
    }
}

@Composable
private fun Fila(titulo: String, valor: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp)) {
        Text(titulo, color = Tema.p.texto, fontFamily = Sans, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(valor, color = Tema.p.texto2, fontFamily = Sans, fontSize = 15.sp)
    }
}

@Composable
private fun FilaAccion(titulo: String, color: Color? = null, alTocar: () -> Unit) {
    Text(
        titulo, color = color ?: Tema.p.acento, fontFamily = Sans, fontSize = 16.sp,
        modifier = Modifier.fillMaxWidth().clickable { Sonidos.tic(); alTocar() }.padding(horizontal = 16.dp, vertical = 15.dp),
    )
}
