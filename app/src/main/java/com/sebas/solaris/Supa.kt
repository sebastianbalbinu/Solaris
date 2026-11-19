package com.sebas.solaris

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Un error que se le puede mostrar tal cual a la persona. */
class Problema(mensaje: String) : Exception(mensaje)

data class Sesion(val access: String, val refresh: String, val expira: Long, val uid: String, val email: String) {
    fun json(): JSONObject = JSONObject()
        .put("access", access).put("refresh", refresh).put("expira", expira).put("uid", uid).put("email", email)

    companion object {
        fun de(o: JSONObject) = Sesion(
            o.getString("access"), o.getString("refresh"), o.getLong("expira"), o.getString("uid"), o.optString("email")
        )
    }
}

/**
 * Cliente mínimo de Supabase (cuentas, tablas y archivos) por HTTP.
 * Todas las funciones bloquean: llamarlas desde Dispatchers.IO.
 * Supabase solo recibe datos ya cifrados.
 */
class Supa(
    base: String,
    private val llave: String,
    var sesion: Sesion?,
    private val alCambiarSesion: (Sesion?) -> Unit,
) {
    private val base = base.trim().trimEnd('/')
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()
    private val tipoJson = "application/json; charset=utf-8".toMediaType()
    private val tipoBinario = "application/octet-stream".toMediaType()

    fun uid(): String = sesion?.uid ?: throw Problema("No hay sesión. Sal de este celular y vuelve a entrar.")

    // ---------- cuentas ----------

    fun registrar(email: String, clave: String): Sesion =
        tomarSesion(auth("/auth/v1/signup", JSONObject().put("email", email).put("password", clave)), email)

    fun entrar(email: String, clave: String): Sesion =
        tomarSesion(auth("/auth/v1/token?grant_type=password", JSONObject().put("email", email).put("password", clave)), email)

    private fun auth(ruta: String, cuerpo: JSONObject): JSONObject {
        val req = Request.Builder().url(base + ruta)
            .header("apikey", llave)
            .post(cuerpo.toString().toRequestBody(tipoJson))
            .build()
        return JSONObject(texto(req))
    }

    private fun tomarSesion(o: JSONObject, email: String): Sesion {
        val access = o.optString("access_token")
        if (access.isBlank()) throw Problema(
            "Supabase pide confirmar el correo. En Supabase ve a Authentication → Sign In / Providers → Email y apaga " +
                "“Confirm email”. Luego borra este usuario en Authentication → Users y vuelve a tocar Construir."
        )
        val user = o.getJSONObject("user")
        val s = Sesion(
            access = access,
            refresh = o.getString("refresh_token"),
            expira = System.currentTimeMillis() / 1000 + o.optLong("expires_in", 3600),
            uid = user.getString("id"),
            email = user.optString("email", email),
        )
        sesion = s
        alCambiarSesion(s)
        return s
    }

    @Synchronized
    private fun token(): String {
        val s = sesion ?: throw Problema("No hay sesión. Sal de este celular y vuelve a entrar.")
        if (System.currentTimeMillis() / 1000 < s.expira - 120) return s.access
        return tomarSesion(auth("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", s.refresh)), s.email).access
    }

    private fun conPermiso(url: String): Request.Builder =
        Request.Builder().url(url).header("apikey", llave).header("Authorization", "Bearer ${token()}")

    // ---------- bóveda (la llave maestra envuelta) ----------

    fun leerBoveda(): JSONObject? {
        val arr = JSONArray(texto(conPermiso("$base/rest/v1/boveda?select=*").get().build()))
        return if (arr.length() == 0) null else arr.getJSONObject(0)
    }

    fun guardarBoveda(v: JSONObject) {
        texto(
            conPermiso("$base/rest/v1/boveda")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(JSONArray().put(v).toString().toRequestBody(tipoJson))
                .build()
        )
    }

    // ---------- páginas y habitaciones ----------

    fun subirItems(filas: JSONArray) {
        texto(
            conPermiso("$base/rest/v1/items")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(filas.toString().toRequestBody(tipoJson))
                .build()
        )
    }

    fun traerItems(desde: Long, maximo: Int): JSONArray = JSONArray(
        texto(
            conPermiso("$base/rest/v1/items?select=id,updated_at,deleted,data,rev&rev=gt.$desde&order=rev.asc&limit=$maximo")
                .get().build()
        )
    )

    // ---------- fotos ----------

    fun subirFoto(ruta: String, bytes: ByteArray) {
        texto(
            conPermiso("$base/storage/v1/object/fotos/$ruta")
                .header("x-upsert", "true")
                .post(bytes.toRequestBody(tipoBinario))
                .build()
        )
    }

    fun bajarFoto(ruta: String): ByteArray {
        http.newCall(conPermiso("$base/storage/v1/object/authenticated/fotos/$ruta").get().build()).execute().use { r ->
            if (!r.isSuccessful) throw Problema(traducir(r))
            return r.body?.bytes() ?: ByteArray(0)
        }
    }

    fun borrarFoto(ruta: String) {
        http.newCall(conPermiso("$base/storage/v1/object/fotos/$ruta").delete().build()).execute().use { r ->
            if (!r.isSuccessful && r.code != 404 && r.code != 400) throw Problema(traducir(r))
        }
    }

    // ---------- red ----------

    private fun texto(req: Request): String {
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Problema(traducir(r))
            return r.body?.string() ?: ""
        }
    }

    private fun traducir(r: Response): String {
        val cuerpo = try { r.body?.string() ?: "" } catch (e: Exception) { "" }
        val msg = try {
            val o = JSONObject(cuerpo)
            listOf("msg", "error_description", "message", "error").map { o.optString(it) }.firstOrNull { it.isNotBlank() } ?: cuerpo
        } catch (e: Exception) {
            cuerpo.take(160)
        }
        return when {
            msg.contains("Invalid login credentials", true) -> "Correo o contraseña incorrectos."
            msg.contains("already registered", true) -> "Ese correo ya tiene una casa. Usa “Ya tengo una casa”."
            msg.contains("Password should", true) -> "La contraseña de la cuenta debe tener al menos 6 caracteres."
            msg.contains("Email not confirmed", true) -> "Falta confirmar el correo. Apaga “Confirm email” en Supabase."
            msg.contains("Signups not allowed", true) -> "Esta casa no acepta cuentas nuevas (los registros están apagados en Supabase)."
            msg.contains("invalid", true) && msg.contains("email", true) -> "Ese correo no parece válido."
            msg.contains("does not exist", true) || msg.contains("schema cache", true) ->
                "Falta preparar la base: pega el archivo supabase/esquema.sql en el SQL Editor de Supabase."
            r.code == 401 || r.code == 403 -> "Supabase no dio permiso ($msg)."
            r.code >= 500 -> "Supabase no responde. Si el proyecto estuvo quieto una semana, entra a supabase.com y reactívalo."
            else -> "Supabase respondió ${r.code}: $msg"
        }
    }
}
