# Solaris

Una estación sobre el océano para guardar lo que piensas, sueñas y dices.

- **Cuaderno:** notas editoriales con fotos, **negrita**, listas y citas. Día en papel crema, noche en negro absoluto (OLED).
- **Cinta:** grabadora de casete en WAV 48 kHz, que sigue grabando con la pantalla apagada y transcribe la voz con Whisper.
- **Océano:** el mar del Paso de Drake en pixel art, con datos reales de Open-Meteo, y una terminal para buscar todo.

## Privacidad

Notas y fotos se cifran en el celular (AES-256-GCM) antes de subir a Supabase. Los audios no salen del celular;
para transcribir, cada parte viaja una vez (a 16 kHz) a la función `transcribir`, que la pasa a Whisper y no la guarda.

## Supabase

1. `supabase/esquema.sql` en el SQL Editor.
2. Edge Function `transcribir` con el código de `supabase/functions/transcribir/index.ts`.
3. Secreto `GROQ_API_KEY` (Edge Functions → Secrets) con una llave de console.groq.com.
