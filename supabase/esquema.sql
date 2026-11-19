-- Logia · esquema de Supabase
-- Pega TODO este archivo en Supabase → SQL Editor → New query → Run.
-- Se puede correr más de una vez sin problema.

-- 1) La bóveda: tu llave maestra, envuelta con tu frase llave y con tu código de recuperación.
create table if not exists public.boveda (
  user_id  uuid primary key default auth.uid() references auth.users on delete cascade,
  salt     text not null,
  iter     int  not null,
  wrapped  text not null,
  rsalt    text not null,
  rwrapped text not null,
  creada   timestamptz not null default now()
);

-- 2) Las páginas y habitaciones. "data" va cifrado: Supabase no puede leerlo.
create table if not exists public.items (
  id         text primary key,
  user_id    uuid not null default auth.uid() references auth.users on delete cascade,
  updated_at bigint  not null,
  deleted    boolean not null default false,
  data       text    not null,
  rev        bigint  not null default 0
);
create index if not exists items_usuario_rev on public.items (user_id, rev);

-- Cada cambio recibe un número creciente; así cada celular sabe qué le falta traer.
create or replace function public.poner_rev() returns trigger
language plpgsql as $$
begin
  new.rev := (extract(epoch from clock_timestamp()) * 1000000)::bigint;
  return new;
end $$;

drop trigger if exists items_rev on public.items;
create trigger items_rev before insert or update on public.items
for each row execute function public.poner_rev();

-- 3) Cada quien solo ve lo suyo.
grant select, insert, update, delete on public.boveda to authenticated;
grant select, insert, update, delete on public.items  to authenticated;

alter table public.boveda enable row level security;
alter table public.items  enable row level security;

drop policy if exists "boveda propia" on public.boveda;
create policy "boveda propia" on public.boveda for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

drop policy if exists "items propios" on public.items;
create policy "items propios" on public.items for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

-- 4) Las fotos (también cifradas), en una carpeta privada por persona.
insert into storage.buckets (id, name, public)
values ('fotos', 'fotos', false)
on conflict (id) do nothing;

drop policy if exists "fotos ver" on storage.objects;
create policy "fotos ver" on storage.objects for select to authenticated
  using (bucket_id = 'fotos' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "fotos subir" on storage.objects;
create policy "fotos subir" on storage.objects for insert to authenticated
  with check (bucket_id = 'fotos' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "fotos cambiar" on storage.objects;
create policy "fotos cambiar" on storage.objects for update to authenticated
  using (bucket_id = 'fotos' and (storage.foldername(name))[1] = auth.uid()::text)
  with check (bucket_id = 'fotos' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "fotos borrar" on storage.objects;
create policy "fotos borrar" on storage.objects for delete to authenticated
  using (bucket_id = 'fotos' and (storage.foldername(name))[1] = auth.uid()::text);
