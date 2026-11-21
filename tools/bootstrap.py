"""Install verified JDK and Android tools into mobile/.toolchain (Windows x64)."""
import concurrent.futures
import hashlib
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / '.toolchain'
ROOT.mkdir(parents=True, exist_ok=True)
JDK = 'https://aka.ms/download-jdk/microsoft-jdk-21-windows-x64.zip'
PACKAGES = [
    ('jdk', JDK, None, 'sha256'),
    ('platform', 'https://dl.google.com/android/repository/platform-35_r02.zip', '0bb560a90a7a2cbd0dd8348224d518b638fe7949', 'sha1'),
    ('build-tools', 'https://dl.google.com/android/repository/build-tools_r35_windows.zip', 'af059bb67cf7786f45ee0db85e2d24985df1b4b6', 'sha1'),
]
def open_url(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'}), timeout=120)
def install(package):
    name, url, checksum, algorithm = package
    destination, archive = ROOT / name, ROOT / (name + '.zip')
    if destination.exists():
        return name + ': already installed'
    if not checksum:
        with open_url(url + '.sha256sum.txt') as response:
            checksum = response.read().decode().split()[0]
    if not archive.exists():
        print('Downloading ' + name, flush=True)
        with open_url(url) as response, archive.open('wb') as output:
            while data := response.read(1024 * 1024):
                output.write(data)
    digest = hashlib.new(algorithm)
    with archive.open('rb') as source:
        while data := source.read(1024 * 1024):
            digest.update(data)
    if digest.hexdigest() != checksum:
        raise RuntimeError('Checksum mismatch: ' + name)
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            if not (destination / entry.filename).resolve().is_relative_to(destination.resolve()):
                raise RuntimeError('Invalid archive path')
        source.extractall(destination)
    return name + ': verified and installed'
if __name__ == '__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        for result in pool.map(install, PACKAGES):
            print(result, flush=True)
