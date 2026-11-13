"""Prepare the ARM64 Node runtime and the locked JavaScript bundle for the APK."""
import argparse
import hashlib
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import zipfile

VERSION = "24.22.1"
RELEASE = f"https://github.com/fogtape/nodejs-mobile/releases/download/v{VERSION}-0"
DIGEST = "d6fd9f610fe5d81fede8f0d400c5a62723247725a19831835f15abf4f0604250"
NODE_LICENSE = f"https://raw.githubusercontent.com/nodejs/node/v{VERSION}/LICENSE"
NODE_LICENSE_DIGEST = "5888dbb9a1d2b18f2c3e6c5f6af1b39de658372b402a0577b002777f14c62ace"
PI_LICENSE = "https://raw.githubusercontent.com/earendil-works/pi/v1.5.2/LICENSE"
PI_LICENSE_DIGEST = "0457f5bcec3b3b211605dfb5d1a49042fd638f3686a410fe099c24a25af13c48"


def fetch(cache, filename, url, expected):
    path = cache / filename
    if not path.exists():
        with tempfile.NamedTemporaryFile(dir=cache, delete=False) as output:
            download = Path(output.name)
        try:
            subprocess.run(["curl", "-fsSL", "--retry", "2", "-o", download, url], check=True)
            verify(download, expected)
            download.replace(path)
        finally:
            download.unlink(missing_ok=True)
    verify(path, expected)
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--jni", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    args = parser.parse_args()

    source = Path(__file__).resolve().parent
    cache = Path.home() / ".cache/codex/dependencies/nodejs-mobile" / VERSION
    cache.mkdir(parents=True, exist_ok=True)
    archive = fetch(cache, "full.zip", f"{RELEASE}/nodejs-mobile-android-{VERSION}-0.zip", DIGEST)
    node_license = fetch(cache, "NODE-LICENSE.txt", NODE_LICENSE, NODE_LICENSE_DIGEST)
    pi_license = fetch(cache, "Pi-LICENSE.txt", PI_LICENSE, PI_LICENSE_DIGEST)

    platform_tag = {"Darwin": "darwin-x86_64", "Linux": "linux-x86_64"}[platform.system()]
    tools = args.ndk / "toolchains/llvm/prebuilt" / platform_tag / "bin"
    jni = args.jni / "arm64-v8a"
    jni.mkdir(parents=True, exist_ok=True)
    args.assets.mkdir(parents=True, exist_ok=True)
    licenses = args.assets / "licenses/pi"
    licenses.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(node_license, licenses / "Node-LICENSE.txt")
    shutil.copyfile(pi_license, licenses / "Pi-LICENSE.txt")

    with tempfile.TemporaryDirectory(prefix="sound-pi-") as directory:
        work = Path(directory)
        with zipfile.ZipFile(archive) as package:
            for name in package.namelist():
                if name.startswith("include/node/") or name == "bin/arm64-v8a/libnode.so":
                    package.extract(name, work)
        node = work / "bin/arm64-v8a/libnode.so"
        shutil.copyfile(node, jni / "libnode.so")
        subprocess.run([
            tools / "aarch64-linux-android26-clang++", "-std=c++20", "-O2", "-fPIC",
            "-shared", "-Wl,--no-undefined", "-Wl,-z,max-page-size=16384",
            "-I", work / "include/node", source / "node-jni.cc", node,
            "-o", jni / "libsound_pi_jni.so",
        ], check=True)
        shutil.copyfile(
            args.ndk / "toolchains/llvm/prebuilt" / platform_tag /
            "sysroot/usr/lib/aarch64-linux-android/libc++_shared.so",
            jni / "libc++_shared.so",
        )

        for name in ("package.json", "package-lock.json", "android-host.mjs", "management.mjs", "recording-loop.mjs",
                     "recording-sessions.mjs", "recording-prompts.mjs"):
            shutil.copyfile(source / name, work / name)
        subprocess.run(["npm", "ci", "--ignore-scripts", "--no-audit", "--no-fund"], cwd=work, check=True)
        for manifest in sorted((work / "node_modules").glob("**/package.json")):
            package = manifest.parent
            if package.parent.name != "node_modules" and not package.parent.name.startswith("@"):
                continue
            for notice in package.iterdir():
                if notice.is_file() and notice.name.upper().startswith(("LICENSE", "NOTICE", "COPYING")):
                    name = str(package.relative_to(work / "node_modules")).replace("/", "-")
                    shutil.copyfile(notice, licenses / f"{name}-{notice.name}")
        subprocess.run([
            work / "node_modules/.bin/esbuild", work / "android-host.mjs",
            "--bundle", "--platform=node", "--format=cjs", "--target=node22",
            f"--outfile={args.assets / 'pi-host.cjs'}", "--legal-comments=eof",
            "--log-level=warning",
        ], cwd=work, check=True)


def verify(path, expected):
    with path.open("rb") as data:
        digest = hashlib.file_digest(data, "sha256").hexdigest()
    if digest != expected:
        raise ValueError(f"{path.name} checksum does not match the pinned release")


if __name__ == "__main__":
    main()
