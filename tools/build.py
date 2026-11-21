"""Build a self-contained debug APK with the official Android build tools.

No Gradle or third-party Android runtime is required. Set JAVA_HOME and
ANDROID_HOME to use an existing SDK, or run bootstrap.py for workspace-local tools.
"""
import os
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.toolchain'
BUILD = ROOT / 'build'
OUT = ROOT / 'output'
SOURCE = ROOT / 'android/app/src/main'

def first(root, pattern):
    matches = list(root.glob(pattern))
    if not matches:
        raise RuntimeError(f'Missing {pattern} in {root}. Run python tools/bootstrap.py first.')
    return sorted(matches)[-1]

def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)

def main():
    java_home = Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else first(TOOLS / 'jdk', '*')
    sdk = Path(os.environ['ANDROID_HOME']) if os.environ.get('ANDROID_HOME') else None
    android = first(sdk / 'platforms', 'android-*/android.jar') if sdk else first(TOOLS / 'platform', '*/android.jar')
    build_tools = first(sdk / 'build-tools', '*') if sdk else first(TOOLS / 'build-tools', '*')
    java = java_home / 'bin/java.exe'
    for folder in [BUILD, OUT, BUILD / 'classes', BUILD / 'dex']:
        folder.mkdir(parents=True, exist_ok=True)
    run(java_home / 'bin/javac.exe', '--release', '8', '-encoding', 'UTF-8', '-classpath', android, '-d', BUILD / 'classes', *SOURCE.glob('java/**/*.java'))
    run(java_home / 'bin/jar.exe', 'cf', BUILD / 'classes.jar', '-C', BUILD / 'classes', '.')
    run(java, '-cp', build_tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--lib', android, '--min-api', '26', '--output', BUILD / 'dex', BUILD / 'classes.jar')
    run(build_tools / 'aapt2.exe', 'compile', '--dir', SOURCE / 'res', '-o', BUILD / 'resources.zip')
    run(build_tools / 'aapt2.exe', 'link', '-I', android, '--manifest', SOURCE / 'AndroidManifest.xml', '-R', BUILD / 'resources.zip', '--auto-add-overlay', '-A', SOURCE / 'assets', '-o', BUILD / 'unsigned.apk')
    with zipfile.ZipFile(BUILD / 'unsigned.apk', 'a') as apk:
        for dex in (BUILD / 'dex').glob('*.dex'):
            apk.write(dex, dex.name)
    run(build_tools / 'zipalign.exe', '-f', '4', BUILD / 'unsigned.apk', BUILD / 'aligned.apk')
    keystore = TOOLS / 'garden-debug.keystore'
    if not keystore.exists():
        TOOLS.mkdir(parents=True, exist_ok=True)
        run(java_home / 'bin/keytool.exe', '-genkeypair', '-keystore', keystore, '-storepass', 'android', '-keypass', 'android', '-alias', 'garden-debug', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Cyber Garden Development, O=Cyber Garden, C=RU')
    output = OUT / 'CyberGarden-debug.apk'
    run(java, '-jar', build_tools / 'lib/apksigner.jar', 'sign', '--ks', keystore, '--ks-pass', 'pass:android', '--out', output, BUILD / 'aligned.apk')
    run(java, '-jar', build_tools / 'lib/apksigner.jar', 'verify', '--verbose', output)
    print(f'APK ready: {output} ({output.stat().st_size:,} bytes)', flush=True)

if __name__ == '__main__':
    main()
