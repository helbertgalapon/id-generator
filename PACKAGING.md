# Creating the IdGenerator installer (EXE)

## Prerequisites

- **JDK 21** (with `jpackage` on PATH)
- **Maven** (`mvn` on PATH)
- **JavaFX 21 jmods** (e.g. `C:\javafx\javafx-21\jmods`)
- **WiX Toolset** (for Windows EXE installer; optional if using app-image only)

## Commands

From the project root (e.g. `c:\...\idgenerator`):

### 1. Build the project

```powershell
mvn clean package
```

### 2. Create the installable EXE

```powershell
.\build-installer.ps1 -Dest .\installer\dist
```

If JavaFX jmods are not in `C:\javafx\javafx-21\jmods`, set the path:

```powershell
.\build-installer.ps1 -Dest .\installer\dist -JavafxJmodsPath "C:\path\to\javafx\jmods"
```

Optional:

- `-AppVersion 1.0.0` — version shown in installer
- `-WinConsole` — show a console window when the app runs (useful for debugging)

### 3. Output

The installer will be created at:

```
installer\dist\IdGenerator-<version>.exe
```

Run that EXE to install; then launch **IdGenerator** from the Start Menu.

## One-liner

```powershell
mvn clean package; .\build-installer.ps1 -Dest .\installer\dist
```
