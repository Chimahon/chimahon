"""Run the existing Android preparation benchmark against an already staged fidelity bundle."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import time
import uuid

from run_native_fidelity import ACTIVITY, Adb


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--font", type=Path, default=Path("F:/sohil/NotoSansCJKjp-Regular.otf"))
    parser.add_argument("--package", default="app.chimahon.fidelity")
    parser.add_argument("--adb", default="F:/Sdk/platform-tools/adb.exe")
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--expected-apk-sha256", help="Refuse to measure a different installed base APK")
    parser.add_argument("--sample", action="store_true", help="DIAGNOSTIC ONLY: short sampled ART run, not a timing result")
    parser.add_argument("--draw", action="store_true", help="Corpus software drawing CPU, not preparation or popup latency")
    parser.add_argument("--no-stage-timings", action="store_true",
                        help="Disable the per-stage counters, which cost real time on this target")
    args = parser.parse_args()
    if args.sample and args.draw:
        parser.error("--sample and --draw are separate measurements")
    if args.out.exists():
        parser.error("Output already exists; keep before/after evidence separate")
    adb = Adb(args.adb, args.package, args.serial)
    installed_paths = adb.run("shell", "pm", "path", args.package).stdout.decode("utf-8").splitlines()
    base_apk = next((line.removeprefix("package:") for line in installed_paths if line.endswith("/base.apk")), None)
    if base_apk is None:
        raise RuntimeError(f"Installed base APK not found: {installed_paths}")
    apk_sha256 = adb.run("shell", "sha256sum", base_apk).stdout.decode("utf-8").split()[0].lower()
    if args.expected_apk_sha256 and apk_sha256 != args.expected_apk_sha256.lower():
        raise RuntimeError(f"Installed APK mismatch: expected {args.expected_apk_sha256}, found {apk_sha256}")
    run_id = "prepare-" + uuid.uuid4().hex[:16]
    remote = f"files/native-fidelity/{run_id}"
    adb.run("shell", "am", "force-stop", args.package)
    adb.run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb.run("shell", "wm", "dismiss-keyguard", check=False)
    adb.run("shell", "run-as", args.package, "mkdir", "-p", remote)
    try:
        for name, source in (("bundle.zip", args.bundle), ("font.otf", args.font)):
            temporary = f"/data/local/tmp/{run_id}-{name}"
            adb.run("push", str(source), temporary)
            try:
                adb.run("shell", "run-as", args.package, "cp", temporary, f"{remote}/{name}")
            finally:
                adb.run("shell", "rm", "-f", temporary, check=False)
        adb.run("shell", "am", "start", "-W", "-n", f"{args.package}/{ACTIVITY}",
                "--es", "run_id", run_id, "--ez", "native_prepare_bench_only", "true",
                "--ez", "native_draw_bench_only", str(args.draw).lower(),
                "--ez", "native_stage_timings", str(not args.no_stage_timings).lower(),
                "--ez", "native_prepare_sampled", str(args.sample).lower())
        deadline = time.monotonic() + args.timeout
        while time.monotonic() < deadline:
            time.sleep(5)
            raw = adb.read(f"{remote}/status.json", check=False)
            if raw.returncode:
                continue
            try:
                status = json.loads(raw.stdout)
            except json.JSONDecodeError:
                continue  # A status write may be in progress.
            if status.get("error"):
                raise RuntimeError(status["error"])
            if not status.get("done"):
                continue
            benchmark = status.get("prepare_benchmark")
            if benchmark is None:
                raise RuntimeError(f"No preparation result: {status}")
            output = dict(run_id=run_id, bundle_sha256=hashlib.sha256(args.bundle.read_bytes()).hexdigest(),
                          native_library_sha256=status.get("native_library_sha256"),
                          font_sha256=status["font_sha256"], apk_updated=status["apk_updated"],
                          installed_apk_sha256=apk_sha256, benchmark=benchmark)
            package_dump = adb.run("shell", "dumpsys", "package", args.package).stdout.decode("utf-8", errors="replace")
            output["package_debuggable"] = bool(re.search(r"\bflags=\[[^\]]*\bDEBUGGABLE\b", package_dump))
            output["compilation_state"] = [dict(abi=abi, filter=mode, reason=reason)
                for abi, mode, reason in re.findall(
                    r"(?m)^\s*(\S+): \[status=([^\]]+)\] \[reason=([^\]]+)\] \[primary-abi\]", package_dump)]
            if args.sample:
                output["sampled_diagnostic"] = True
                args.out.with_suffix(".trace").write_bytes(adb.read(f"{remote}/methods.trace").stdout)
            args.out.write_text(json.dumps(output, indent=2), encoding="utf-8")
            print(json.dumps(benchmark, indent=2))
            return
        raise TimeoutError("Preparation benchmark timed out")
    finally:
        adb.run("shell", "am", "force-stop", args.package, check=False)
        adb.run("shell", "run-as", args.package, "rm", "-rf", remote, check=False)


if __name__ == "__main__":
    main()
