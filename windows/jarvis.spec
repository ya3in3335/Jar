# PyInstaller build spec for the JARVIS desktop app.
#   pyinstaller jarvis.spec
# Produces dist/JARVIS/JARVIS.exe (one folder; starts faster than onefile).

block_cipher = None

a = Analysis(
    ["jarvis_win/__main__.py"],
    pathex=["."],
    binaries=[],
    datas=[],
    hiddenimports=[
        # keyring resolves its Windows backend at runtime, so PyInstaller's
        # static analysis cannot see it.
        "keyring.backends.Windows",
        "win32ctypes.core",
    ],
    hookspath=[],
    runtime_hooks=[],
    # Qt modules this app never touches; excluding them saves ~80 MB.
    excludes=[
        "PySide6.QtWebEngineCore",
        "PySide6.QtWebEngineWidgets",
        "PySide6.Qt3DCore",
        "PySide6.QtCharts",
        "PySide6.QtDataVisualization",
        "PySide6.QtQuick",
        "PySide6.QtQml",
        "tkinter",
    ],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="JARVIS",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,  # GUI app: no console window
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=False,
    name="JARVIS",
)
