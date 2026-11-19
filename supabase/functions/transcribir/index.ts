// Solaris · transcribir
// Recibe el audio de una cinta (WAV 16 kHz) desde la app y lo envía a Whisper en Groq.
// La llave vive solo aquí, como secreto GROQ_API_KEY; el audio no se guarda.
Deno.serve(async (req) => {
  if (req.method !== "POST") return new Response("Solo POST", { status: 405 });
  // solo personas con sesión iniciada en tu Supabase pueden usar tu llave
  const sesion = await fetch(`${Deno.env.get("SUPABASE_URL")}/auth/v1/user`, {
    headers: { Authorization: req.headers.get("Authorization") ?? "", apikey: Deno.env.get("SUPABASE_ANON_KEY") ?? "" },
  });
  if (!sesion.ok) return Response.json({ error: "Sesión no válida" }, { status: 401 });
  const llave = Deno.env.get("GROQ_API_KEY");
  if (!llave) {
    return Response.json({ error: "Falta GROQ_API_KEY en los secretos de Supabase" }, { status: 500 });
  }
  const audio = await req.arrayBuffer();
  if (audio.byteLength < 1000) return Response.json({ text: "" });

  const form = new FormData();
  form.append("file", new Blob([audio], { type: "audio/wav" }), "cinta.wav");
  form.append("model", "whisper-large-v3");
  form.append("language", "es");
  form.append("response_format", "json");
  form.append("temperature", "0");

  const r = await fetch("https://api.groq.com/openai/v1/audio/transcriptions", {
    method: "POST",
    headers: { Authorization: `Bearer ${llave}` },
    body: form,
  });
  const cuerpo = await r.text();
  return new Response(cuerpo, { status: r.status, headers: { "Content-Type": "application/json" } });
});
