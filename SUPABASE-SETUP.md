## Supabase Storage setup

The app uses **Supabase Storage only** (no database). Generated ID PDFs are uploaded so the QR code points to a public URL that works from any network.

---

### 1. Create a Supabase project (or use existing)

- Sign in at [supabase.com](https://supabase.com)
- **New account**: Sign up, then create a project.
- **New project in existing account**: Dashboard → New project.
- **Switch project**: Use the project selector in the top-left of the dashboard.

Note your **Project URL** and **anon public key**:
- `Project Settings` → `API` → Project URL (e.g. `https://xyzcompany.supabase.co`)
- `Project Settings` → `API` → `Project API keys` → `anon` `public`

---

### 2. Create the storage bucket

In the Supabase dashboard:

1. Go to **Storage**
2. Click **New bucket**
3. Name it `idgenerator-files` (or another name you prefer)
4. Enable **Public bucket** so QR URLs can be opened from anywhere

---

### 3. Storage policies

In **Storage** → select your bucket → **Policies**, add a policy to allow uploads:

```sql
create policy "anon can manage idgenerator files"
on storage.objects
for all
to anon
using (bucket_id = 'idgenerator-files')
with check (bucket_id = 'idgenerator-files');
```

Or use the policy editor UI: allow `anon` to insert, update, delete, and select on `storage.objects` for your bucket.

---

### 4. Config file on your machine

Create `supabase-config.json` in the IdGenerator data folder:

| OS | Path |
|----|------|
| **Windows** | `C:\Users\<YourUsername>\IdGenerator\supabase-config.json` |
| **macOS / Linux** | `~/IdGenerator/supabase-config.json` |

You can also place it in the app directory (e.g. next to the JAR) as `supabase-config.json`.

Example:

```json
{
  "url": "https://your-project-id.supabase.co",
  "anonKey": "your-anon-public-key",
  "storageBucket": "idgenerator-files"
}
```

- **url** (required): Project URL
- **anonKey** (required): Anon public key
- **storageBucket** (optional): Bucket name; defaults to `idgenerator-files` if omitted

---

### 5. Changing project or account

1. Create a new project in Supabase (or log into another account and create a project)
2. Create the storage bucket and policies as in steps 2 and 3
3. Edit `supabase-config.json` and update:
   - `url` to the new project URL
   - `anonKey` to the new project’s anon key
   - `storageBucket` if you used a different bucket name
4. Restart the app

No database is used; only the storage bucket and config need to be updated.

---

### 6. Behavior

- **No config file**: Supabase storage is disabled. QR codes use the local server URL and only work on the same network.
- **Config file present**:
  - When you click **Preview/Generate**, the app tries to upload the ID PDF to Supabase Storage and uses the **public storage URL** for the QR code.
  - When you **save** an ID while **offline**, the upload is **queued** and will be uploaded automatically when your computer is back online (it will also retry after app restart).
