# Logia

*Lo que no quieres perder entre el sueño y la vigilia.*

Una bitácora íntima con la estética de la Sala Roja: páginas con texto y fotos, habitaciones, un tomo que se llena cada mes, velas por cada día que entras y un modo para quemar lo que solo necesitabas soltar.

## Privacidad

- Todo se cifra **en el celular** (AES-256-GCM) con una llave maestra.
- La llave maestra solo sale envuelta con tu **frase llave** y con tu **código de recuperación**. Supabase guarda datos ilegibles.
- PIN o huella para el día a día; el telón se cierra apenas sales de la app.
- No se pueden tomar capturas y la app no aparece en "recientes".

## Preparar Supabase (una sola vez)

1. Crea un proyecto gratis en [supabase.com](https://supabase.com).
2. **Authentication → Sign In / Providers → Email**: apaga **Confirm email**.
3. **SQL Editor → New query**: pega todo `supabase/esquema.sql` y dale **Run**.
4. **Project Settings → API**: copia el **Project URL** y la **anon public key**.
5. En GitHub, en este repositorio: **Settings → Secrets and variables → Actions → New repository secret**:
   - `SUPABASE_URL` = el Project URL
   - `SUPABASE_ANON_KEY` = la anon key

   (Si no pones los secretos, la app te los pide la primera vez que la abres.)
6. Cuando ya hayas creado tu casa desde la app, puedes apagar **Allow new users to sign up** en Authentication para que nadie más cree cuentas en tu proyecto.

## Compilar

Cada cambio en `main` compila la APK con GitHub Actions y la publica en **Releases** como `Logia.apk`. Si falla, el registro queda en un release llamado "Registro de error".

## Fases

1. **La casa** ← esta versión: telón, cifrado, Supabase, páginas con texto y fotos, habitaciones, quemar, tomo y velas.
2. La grabadora: audios de voz y sonido de campo.
3. El libro: tomos encuadernados en el estante, sala que se amuebla, páginas de personas y lugares.
4. Transcripción de audios y buscador.
5. Editor de cinta.
