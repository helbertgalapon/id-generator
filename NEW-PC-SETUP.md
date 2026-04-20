# ID Generator - New PC Setup Guide (Windows)

This guide is for setting up the project on a brand new computer and restoring existing data from an `.idgbak` backup.

## 1) Install required software

Install these first:

1. **Git**  
   Download: [https://git-scm.com/download/win](https://git-scm.com/download/win)
2. **JDK 21** (required)  
   Download any JDK 21 distribution (Temurin, Oracle, etc.).
3. **Apache Maven 3.9+**  
   Download: [https://maven.apache.org/download.cgi](https://maven.apache.org/download.cgi)

After installing, open a new PowerShell and verify:

```powershell
git --version
java --version
mvn --version
```

If any command fails, fix PATH before continuing.

---

## 2) Get the project from GitHub

```powershell
git clone <YOUR_GITHUB_REPO_URL>
cd idgenerator
```

Example:

```powershell
git clone https://github.com/<your-org>/<your-repo>.git
cd idgenerator
```

---

## 3) Build and run the app (developer mode)

From the project root:

```powershell
mvn clean package
mvn javafx:run
```

Notes:
- `mvn clean package` downloads all Java dependencies automatically.
- `mvn javafx:run` starts the desktop app.

---

## 4) First-run data folders created by the app

On first launch, the app creates this folder structure under your Windows user profile:

- `C:\Users\<YourUsername>\IdGenerator\database\idgenerator.db`
- `C:\Users\<YourUsername>\IdGenerator\photos\`
- `C:\Users\<YourUsername>\IdGenerator\reference\`
- `C:\Users\<YourUsername>\IdGenerator\supabase-config.json` (if you add Supabase config)

Do not manually edit the `idgenerator.db` file while the app is running.

---

## 5) Restore data from a generated `.idgbak` backup

If you already have a backup from another PC:

1. Launch the app.
2. Open **Settings**.
3. Click **Import Database**.
4. Select your `.idgbak` file.
5. Confirm replacement.

What gets restored from `.idgbak`:
- Database records
- Profile setup (organization values)
- Reference template images
- Stored photo files
- Supabase config (if it was included in the backup)
- Pending Supabase upload queue (if present)

Safety behavior:
- Before import, the app automatically creates a backup of the current local DB in the database folder (timestamped file).
- If import fails, the app attempts to restore your previous DB automatically.

---

## 6) Supabase setup (for cloud QR/PDF links)

If you want QR codes to open from any network, configure Supabase Storage.

Use the full guide in:
- `SUPABASE-SETUP.md`

Quick requirements:
- A Supabase project
- A public storage bucket (default: `idgenerator-files`)
- `supabase-config.json` with:

```json
{
  "url": "https://your-project-id.supabase.co",
  "anonKey": "your-anon-public-key",
  "storageBucket": "idgenerator-files"
}
```

Recommended location:
- `C:\Users\<YourUsername>\IdGenerator\supabase-config.json`

Restart the app after changing this file.

---

## 7) Optional: Create a Windows installer (.exe)

If you want a distributable installer:

1. Install **JavaFX jmods** (version 21)
2. Install **WiX Toolset**
3. Run:

```powershell
mvn clean package
.\build-installer.ps1 -Dest .\installer\dist
```

See `PACKAGING.md` for full packaging options.

---

## 8) Daily run commands

From project root:

```powershell
mvn javafx:run
```

If dependencies or code changed:

```powershell
mvn clean package
mvn javafx:run
```

---

## 9) Troubleshooting

- **`mvn` not recognized**  
  Maven is not on PATH; reinstall or update environment variables.
- **Wrong Java version**  
  Ensure `java --version` shows Java 21.
- **App runs but old data missing**  
  Import your `.idgbak` file from **Settings -> Import Database**.
- **QR works only on local network**  
  Supabase config is missing/invalid; review `SUPABASE-SETUP.md`.

