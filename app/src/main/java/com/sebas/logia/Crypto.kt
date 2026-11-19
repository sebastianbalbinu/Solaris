package com.sebas.logia

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Todo lo que sale del celular va cifrado con la llave maestra (AES-256-GCM).
 * La llave maestra solo viaja envuelta: una vez con la frase llave y otra con el código de recuperación.
 */
object Crypto {
    const val ITERACIONES = 210_000
    private val azar = SecureRandom()

    fun azar(n: Int): ByteArray = ByteArray(n).also { azar.nextBytes(it) }

    /** Deriva una llave de 32 bytes a partir de un texto (frase, código o PIN). */
    fun derivar(secreto: String, sal: ByteArray, iteraciones: Int): ByteArray {
        val spec = PBEKeySpec(secreto.toCharArray(), sal, iteraciones, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun sellar(llave: ByteArray, claro: ByteArray): ByteArray {
        val iv = azar(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(llave, "AES"), GCMParameterSpec(128, iv))
        return iv + c.doFinal(claro)
    }

    fun abrir(llave: ByteArray, caja: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(llave, "AES"), GCMParameterSpec(128, caja, 0, 12))
        return c.doFinal(caja, 12, caja.size - 12)
    }

    fun iguales(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun deB64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /** La frase se normaliza para que "canción" escrita en otro teclado dé la misma llave. */
    fun limpiarFrase(frase: String): String = Normalizer.normalize(frase.trim(), Normalizer.Form.NFC)

    private const val ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** Código de recuperación: 6 grupos de 4, sin letras que se confundan (O/0, I/1). */
    fun codigoRecuperacion(): String =
        (0 until 6).joinToString("-") { (0 until 4).map { ALFABETO[azar.nextInt(ALFABETO.length)] }.joinToString("") }

    fun limpiarCodigo(codigo: String): String =
        codigo.uppercase().filter { it.isLetterOrDigit() }
}

/**
 * Llave del propio celular, guardada en el Android Keystore (no se puede sacar del teléfono).
 * Con ella se guarda la llave maestra y la sesión, para que el PIN o la huella basten cada día.
 */
object LlaveDelCelular {
    private const val ALIAS = "logia_casa"

    private fun llave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun sellar(claro: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, llave())
        return c.iv + c.doFinal(claro)
    }

    fun abrir(caja: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, llave(), GCMParameterSpec(128, caja, 0, 12))
        return c.doFinal(caja, 12, caja.size - 12)
    }
}
