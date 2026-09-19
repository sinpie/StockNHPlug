"""Sign an isolated, optimized preview APK and collect the debug APK for testing.

Build first with -PpreviewRelease=true assembleDebug assembleRelease. This script
never builds, publishes, creates signing keys, or accepts the production app ID.
Keep the test keystore outside the checkout. Supply passwords through the named
environment variables; subprocess arguments contain only their variable names.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def run(argv):
    result = subprocess.run([str(value) for value in argv], check=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding="utf-8", errors="replace")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--alias", required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("artifacts/test-release"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if args.keystore.resolve().is_relative_to(root):
        parser.error("The test signing key must be outside the checkout.")
    for name in ("STOCKNHPLUG_TEST_STORE_PASSWORD", "STOCKNHPLUG_TEST_KEY_PASSWORD"):
        if not os.environ.get(name):
            parser.error(f"Missing environment variable: {name}")
    # Invoke the signer JAR directly, avoiding shell/batch parsing of paths/aliases.
    java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
    signer = [java, "-jar", args.build_tools / "lib/apksigner.jar"]
    aapt = args.build_tools / ("aapt.exe" if os.name == "nt" else "aapt")
    unsigned = root / "app/build/outputs/apk/release/app-release-unsigned.apk"
    debug = root / "app/build/outputs/apk/debug/app-debug.apk"

    def check_apk(path, package, debuggable):
        badging = run([aapt, "dump", "badging", path])
        if f"package: name='{package}'" not in badging:
            raise ValueError(f"Unexpected package identity: {path.name}")
        if ("application-debuggable" in badging) != debuggable:
            raise ValueError(f"Unexpected debuggability: {path.name}")
        version = re.search(r"versionName='([^']+)'", badging)
        if not version:
            raise ValueError("Missing APK version")
        return version.group(1)

    version = check_apk(unsigned, "com.sinpie.stocknhplug.preview", False)
    check_apk(debug, "com.sinpie.stocknhplug.debug", True)
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    release_apk = out / f"StockNHPlug-{version}-release.apk"
    debug_apk = out / f"StockNHPlug-{version}-debug.apk"
    if release_apk.exists() or debug_apk.exists():
        raise ValueError("Use an empty output directory; never overwrite release evidence.")
    run(signer + ["sign", "--ks", args.keystore, "--ks-key-alias", args.alias,
                  "--ks-pass", "env:STOCKNHPLUG_TEST_STORE_PASSWORD",
                  "--key-pass", "env:STOCKNHPLUG_TEST_KEY_PASSWORD",
                  "--out", release_apk, unsigned])
    shutil.copyfile(debug, debug_apk)
    evidence = {}
    hashes = []
    for apk, package, debuggable in ((release_apk, "com.sinpie.stocknhplug.preview", False),
                                    (debug_apk, "com.sinpie.stocknhplug.debug", True)):
        check_apk(apk, package, debuggable)
        certificate = run(signer + ["verify", "--verbose", "--print-certs", apk])
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        hashes.append(f"{digest}  {apk.name}\n")
        evidence[apk.name] = {"applicationId": package, "debuggable": debuggable,
                              "sha256": digest, "bytes": apk.stat().st_size,
                              "signatureVerification": certificate}
    (out / "SHA256SUMS.txt").write_text("".join(hashes), encoding="utf-8")
    (out / "APK-VERIFICATION.json").write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Verified two test APKs: {out}")


if __name__ == "__main__":
    main()
