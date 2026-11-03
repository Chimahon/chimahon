"""Export lookup outputs, replay the production popup, and validate phase-level calibration."""
import argparse
import hashlib
import json
import math
import re
import statistics
import subprocess
import time
import uuid
from pathlib import Path

ACTIVITY = "eu.kanade.tachiyomi.ui.dictionary.bench.LookupReplayActivity"
SCOPE = "production-popup/results-to-frame-commit"
PHASES = ("mountQueueMs", "prepareMs", "readyToDrawMs", "drawToCommitMs")


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def new_output(path):
    path.mkdir(parents=True, exist_ok=False)
    return path


def verify_compilation_state(package_dump, requested):
    states = [dict(abi=abi, filter=mode, reason=reason) for abi, mode, reason in re.findall(
        r"(?m)^\s*(\S+): \[status=([^\]]+)\] \[reason=([^\]]+)\] \[primary-abi\]",
        package_dump)]
    if requested != "as-installed" and (not states or any(s["filter"] != requested for s in states)):
        raise RuntimeError(f"Requested compilation {requested!r}, but Android reports {states!r}; "
                           "refusing mislabeled timings (debuggable APKs can remain verify)")
    return states


def replay_remote_path(package, storage, run_id):
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*", package) or not re.fullmatch(r"replay-[0-9a-f]{16}", run_id):
        raise ValueError("Invalid replay package or run id")
    if storage not in ("private", "emulator"):
        raise ValueError("Invalid replay storage")
    prefix = "files" if storage == "private" else f"/data/user/0/{package}/files"
    return f"{prefix}/lookup-replay/{run_id}"


class Adb:
    def __init__(self, executable, serial, package, storage="private"):
        if not serial or not re.fullmatch(r"[A-Za-z0-9_.]+", package):
            raise ValueError("An explicit serial and valid package are required")
        self.command = [str(executable), "-s", serial]
        self.package = package
        replay_remote_path(package, storage, "replay-0000000000000000")
        self.storage = storage
        if storage == "emulator" and not re.fullmatch(r"emulator-\d+", serial):
            raise ValueError("Privileged private-file transport is restricted to a local emulator")
        if self.run("get-state").strip() != b"device":
            raise RuntimeError("Selected Android target is not ready")
        self.file_prefix = ("run-as", package)
        if storage == "emulator":
            if self.run("shell", "getprop", "ro.kernel.qemu").strip() != b"1":
                raise RuntimeError("Target is not an Android emulator; refusing privileged transport")
            listing = self.run("shell", "cmd", "package", "list", "packages", "--user", "0", "-U", package)
            matches = re.findall(rf"(?m)^package:{re.escape(package)} uid:(\d+)\s*$", listing.decode())
            if len(matches) != 1 or int(matches[0]) < 10000:
                raise RuntimeError("Could not resolve an unambiguous application UID")
            self.file_prefix = ("su", matches[0])
            if self.run("shell", *self.file_prefix, "id", "-u").strip() != matches[0].encode():
                raise RuntimeError("Emulator su did not select the application UID")

    def run(self, *args, timeout=120, check=True):
        result = subprocess.run([*self.command, *map(str, args)], capture_output=True, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError(result.stderr.decode("utf-8", "replace") or result.stdout.decode("utf-8", "replace"))
        return result.stdout

    def file_command(self, *args, output=False):
        command = "exec-out" if output else "shell"
        return self.run(command, *self.file_prefix, *args)

    def read(self, path):
        return self.file_command("cat", path, output=True)

    def execute(self, request, local, bundle=None, timeout=900):
        run_id = "replay-" + uuid.uuid4().hex[:16]
        remote = replay_remote_path(self.package, self.storage, run_id)
        write_json(local / "request.json", request)
        self.run("shell", "am", "force-stop", self.package)
        self.file_command("mkdir", "-p", remote)
        files = {"request.json": local / "request.json"}
        if bundle:
            files["bundle.json"] = bundle
        for filename, source in files.items():
            temporary = f"/data/local/tmp/{run_id}-{filename}"
            self.run("push", source, temporary)
            self.file_command("cp", temporary, f"{remote}/{filename}")
            self.run("shell", "rm", temporary)
        launched = self.run("shell", "am", "start", "-W", "-n", f"{self.package}/{ACTIVITY}",
                            "--es", "run_id", run_id)
        if b"Error" in launched:
            raise RuntimeError(launched.decode("utf-8", "replace"))
        deadline = time.monotonic() + timeout
        status = None
        while time.monotonic() < deadline:
            try:
                status = json.loads(self.read(f"{remote}/status.json"))
            except (RuntimeError, ValueError):
                time.sleep(0.5)
                continue
            if status.get("runId") != run_id:
                raise RuntimeError("Stale replay output")
            if status.get("done"):
                break
            time.sleep(0.5)
        else:
            raise TimeoutError(f"Replay timed out; diagnostics retained at {remote}")
        write_json(local / "status.json", status)
        if status.get("error"):
            raise RuntimeError(f"{status['error']} (see {local / 'status.json'})")
        if request.get("action") == "capture":
            (local / "bundle.json").write_bytes(self.read(f"{remote}/bundle.json"))
        # Only this generated run's files, after successful archival. Never clear app data.
        if remote != replay_remote_path(self.package, self.storage, run_id):
            raise RuntimeError("Unsafe cleanup path")
        self.file_command("rm", "-rf", remote)
        return status


def prepare_compilation(adb, mode):
    actual_filter = "speed-profile" if mode == "baseline-profile" else mode
    if mode != "as-installed":
        reset = adb.run("shell", "cmd", "package", "compile", "--reset", adb.package, timeout=600)
        if b"Success" not in reset:
            raise RuntimeError(f"Compilation reset failed: {reset!r}")
        if mode == "baseline-profile":
            sdk = int(adb.run("shell", "getprop", "ro.build.version.sdk").strip())
            if sdk >= 34:
                adb.run("shell", "pm", "art", "clear-app-profiles", adb.package)
            installed = adb.run("shell", "am", "broadcast", "-a", "androidx.profileinstaller.action.INSTALL_PROFILE",
                                "-n", f"{adb.package}/androidx.profileinstaller.ProfileInstallReceiver")
            if not re.search(rb"\bresult=1\b", installed):
                raise RuntimeError(f"Packaged Baseline Profile was not installed: {installed!r}")
            adb.run("shell", "am", "force-stop", adb.package)
        compiled = adb.run("shell", "cmd", "package", "compile", "-m", actual_filter, "-f", adb.package, timeout=600)
        if b"Success" not in compiled:
            raise RuntimeError(f"Compilation command failed: {compiled!r}")
    return verify_compilation_state(
        adb.run("shell", "dumpsys", "package", adb.package).decode("utf-8", "replace"), actual_filter)


def capture(args):
    out = new_output(args.out)
    queries = read_json(args.queries)
    if not isinstance(queries, list) or not queries or not all(isinstance(x, str) and x.strip() for x in queries):
        raise ValueError("Queries must be a nonempty JSON array of strings")
    request = dict(action="capture", queries=queries)
    if (args.width is None) != (args.height is None):
        raise ValueError("Pass both --width and --height, or neither")
    if args.width is not None:
        request.update(widthPx=args.width, heightPx=args.height)
    Adb(args.adb, args.serial, args.package, args.storage).execute(request, out)
    print(f"Exported {len(queries)} real lookup snapshots to {out / 'bundle.json'}")


def replay(args):
    bundle = read_json(args.bundle)
    if bundle.get("protocol") != 1 or bundle.get("scope") != SCOPE:
        raise ValueError("Unsupported replay bundle")
    if args.iterations < 1 or args.warmups < 0:
        raise ValueError("Invalid iteration counts")
    out = new_output(args.out)
    adb = Adb(args.adb, args.serial, args.package, args.storage)
    compilation_state = prepare_compilation(adb, args.compilation)
    runs = []
    if args.regime == "cold-process":
        jobs = [(c["id"], 0, 1) for _ in range(args.iterations) for c in bundle["cases"]]
    else:
        jobs = [("", args.warmups, args.iterations)]
    for index, (case_id, warmups, iterations) in enumerate(jobs):
        local = out / f"run-{index:03d}"
        local.mkdir()
        status = adb.execute(dict(action="replay", regime=args.regime, caseId=case_id, warmups=warmups,
                                  iterations=iterations, compilationMode=args.compilation), local, args.bundle)
        if status.get("scope") != SCOPE or status.get("protocol") != 1:
            raise RuntimeError("Unexpected benchmark scope")
        expected_hash = hashlib.sha256(args.bundle.read_bytes()).hexdigest()
        if status.get("bundleSha256") != expected_hash:
            raise RuntimeError("Replay bundle changed in transit")
        runs.append(status)
        print(f"Completed batch {index + 1}/{len(jobs)}", flush=True)
    if any(r["runtime"] != runs[0]["runtime"] for r in runs):
        raise RuntimeError("Runtime changed within the run")
    result = dict(protocol=1, scope=SCOPE, source=bundle["source"], regime=args.regime, serial=args.serial,
                  compilationMode=args.compilation, compilationState=compilation_state, storage=args.storage,
                  runtime=runs[0]["runtime"],
                  bundleSha256=runs[0]["bundleSha256"], samples=[s for r in runs for s in r["samples"]])
    write_json(out / "results.json", result)
    print(json.dumps(summarize(result), indent=2, ensure_ascii=False))


def summarize(report):
    cases = {}
    for sample in report["samples"]:
        if not sample.get("warmup") and sample.get("ok"):
            cases.setdefault(sample["caseId"], []).append(sample)
    return {case: {phase: statistics.median(values) for phase in (*PHASES, "firstDrawTotalMs", "frameCommitTotalMs")
                   if (values := [s[phase] for s in rows if isinstance(s.get(phase), (int, float)) and math.isfinite(s[phase])])}
            for case, rows in cases.items()}


def calibrate_reports(phone, emulator, training, allow_runtime_mismatch=False, max_error_percent=25):
    if not math.isfinite(max_error_percent) or max_error_percent < 0:
        raise ValueError("Calibration tolerance must be finite and nonnegative")
    if any(r.get("diagnosticOnly") or r.get("methodSamplingUs") for r in (phone, emulator)):
        raise ValueError("Sampled diagnostics cannot calibrate unsampled popup timings")
    for key in ("protocol", "scope", "source", "bundleSha256", "regime", "compilationMode"):
        if phone.get(key) != emulator.get(key):
            raise ValueError(f"Incompatible {key}")
    if phone.get("protocol") != 1 or phone.get("scope") != SCOPE or phone.get("source") != "hoshidicts-output":
        raise ValueError("Calibration requires real HoshiDicts snapshots, not synthetic fixtures")
    if phone.get("compilationMode") not in ("verify", "speed"):
        raise ValueError("Use an explicit matching --compilation verify or speed")
    p, e = phone["runtime"], emulator["runtime"]
    for key in ("buildType", "rendererSourceSha256"):
        if not p.get(key) or p[key] != e.get(key):
            raise ValueError(f"Mismatched {key}; rebuild both ABI APKs from the same sources")
    mismatches = [key for key in ("sdk", "fonts") if not p.get(key) or p.get(key) != e.get(key)]
    if not math.isclose(p.get("refreshRateHz", 0), e.get("refreshRateHz", 0), abs_tol=0.5):
        mismatches.append("refreshRateHz")
    if mismatches and not allow_runtime_mismatch:
        raise ValueError(f"Runtime differs: {mismatches}; inspect or explicitly allow exploratory calibration")
    for report in (phone, emulator):
        if any(not s.get("ok") for s in report["samples"]):
            raise ValueError("Failed samples cannot be silently excluded from calibration")
        for sample in report["samples"]:
            if sample.get("warmup"):
                continue
            total = sample.get("firstDrawTotalMs")
            if not isinstance(total, (int, float)) or not math.isfinite(total) or total <= 0:
                raise ValueError("First-draw totals must be finite and positive")
            for phase in PHASES:
                value = sample.get(phase)
                if value is not None and (not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0):
                    raise ValueError(f"Invalid phase duration: {phase}")
    pm, em = summarize(phone), summarize(emulator)
    if set(pm) != set(em) or not training or not set(training) <= set(pm):
        raise ValueError("Training IDs must exist in both identical case sets")
    holdouts = sorted(set(pm) - set(training))
    phases = {}
    for phase in PHASES:
        usable = [c for c in training if pm[c].get(phase, 0) > 0 and em[c].get(phase, 0) > 0]
        if not usable:
            continue
        factor = statistics.median(pm[c][phase] / em[c][phase] for c in usable)
        checks = [dict(caseId=c, actualMs=pm[c][phase], predictedMs=em[c][phase] * factor,
                       absoluteErrorMs=abs(em[c][phase] * factor - pm[c][phase]),
                       errorPercent=100 * abs(em[c][phase] * factor / pm[c][phase] - 1))
                  for c in holdouts if pm[c].get(phase, 0) > 0 and phase in em[c]]
        phases[phase] = dict(factor=factor, trainingCases=usable, holdout=checks)
    required = PHASES[:3]
    evaluated = bool(holdouts) and len(set(training)) >= 2 and all(
        phase in phases and len(phases[phase]["trainingCases"]) >= 2 and
        len(phases[phase]["holdout"]) == len(holdouts) for phase in required)
    acceptable = evaluated and all(check["errorPercent"] <= max_error_percent
                                   for phase in phases.values() for check in phase["holdout"])
    totals = []
    for case in holdouts:
        if not all(phase in phases and phase in em[case] for phase in required):
            continue
        predicted = sum(em[case][phase] * phases[phase]["factor"] for phase in required)
        actual = pm[case]["firstDrawTotalMs"]
        totals.append(dict(caseId=case, predictedFirstDrawMs=predicted, actualFirstDrawMs=actual,
                           errorPercent=100 * abs(predicted / actual - 1)))
    return dict(protocol=1, scope=SCOPE, regime=phone["regime"], training=training, holdouts=holdouts,
                holdoutEvaluated=evaluated, validated=acceptable and not mismatches and
                all(t["errorPercent"] <= max_error_percent for t in totals),
                tolerancePercent=max_error_percent, runtimeMismatches=mismatches, phases=phases, totals=totals,
                note="Validation requires independent holdouts within the reported per-phase error tolerance. No global multiplier or artificial slowdown; not a hardware equivalence guarantee.")


def fixture(args):
    """Smoke-test input from existing glossary fixtures, NEVER a measured phone workload."""
    source = read_json(args.cases)
    if isinstance(source, dict):
        source = source["cases"]
    selected = source[:args.limit]
    cases = []
    for i, c in enumerate(selected):
        name = c["dictName"]
        css_file = c.get("dictCss", "")
        css = c.get("css", "") or ((args.cases.parent / css_file).read_text(encoding="utf-8") if css_file else "")
        term = c["term"]
        results = [dict(matched=term, deinflected=term, preprocessorSteps=0, process=[], term=dict(
            expression=term, reading="", rules="", glossaries=[dict(dictName=name, glossary=c["glossary"],
            definitionTags="", termTags="")], frequencies=[], pitches=[]))]
        cases.append(dict(id=f"fixture-{i}", query=term, widthPx=825, heightPx=990, density=2.75, fontScale=1,
                          fontSize=16, direction="ltr", profile=dict(id="replay", name="replay"), results=results,
                          styles=[dict(dictName=name, styles=css)], media={}, environment=dict(
                          dark=False, amoled=False, seedColor=-16733696, dictionaryTitles={name: name}),
                          options=dict(groupTerms=True, groupPitches=False, showFrequencyHarmonic=False,
                          showFrequencyAverage=False, showPitchDiagram=True, showPitchNumber=True, showPitchText=True,
                          customCss="", eInkMode=False, wordAudioEnabled=False, fontFamily="")))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    if args.out.exists():
        raise FileExistsError(args.out)
    write_json(args.out, dict(protocol=1, scope=SCOPE, source="synthetic-fixture", cases=cases))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("capture", "replay"):
        sub = commands.add_parser(name)
        sub.add_argument("--serial", required=True, help="Explicit target; never auto-select a phone")
        sub.add_argument("--adb", type=Path, default=Path("F:/Sdk/platform-tools/adb.exe"))
        sub.add_argument("--package", default="app.chimahon.dev")
        sub.add_argument("--storage", choices=("private", "emulator"), default="private",
                         help="Emulator mode stages private files under the app UID using userdebug su; refuses phones")
        sub.add_argument("--out", type=Path, required=True)
        if name == "capture":
            sub.add_argument("--queries", type=Path, required=True)
            sub.add_argument("--width", type=int)
            sub.add_argument("--height", type=int)
        else:
            sub.add_argument("--bundle", type=Path, required=True)
            sub.add_argument("--regime", choices=("cold-process", "warm-process"), default="warm-process")
            sub.add_argument("--iterations", type=int, default=5)
            sub.add_argument("--warmups", type=int, default=3)
            sub.add_argument("--compilation", choices=("as-installed", "verify", "speed", "baseline-profile"), default="as-installed")
    sub = commands.add_parser("calibrate")
    sub.add_argument("--phone", type=Path, required=True)
    sub.add_argument("--emulator", type=Path, required=True)
    sub.add_argument("--train", nargs="+", required=True)
    sub.add_argument("--out", type=Path, required=True)
    sub.add_argument("--allow-runtime-mismatch", action="store_true")
    sub.add_argument("--max-error-percent", type=float, default=25)
    sub = commands.add_parser("fixture")
    sub.add_argument("--cases", type=Path, required=True)
    sub.add_argument("--out", type=Path, required=True)
    sub.add_argument("--limit", type=int, default=2)
    args = parser.parse_args()
    if args.command == "capture":
        capture(args)
    elif args.command == "replay":
        replay(args)
    elif args.command == "fixture":
        fixture(args)
    else:
        if args.out.exists():
            raise FileExistsError(args.out)
        write_json(args.out, calibrate_reports(read_json(args.phone), read_json(args.emulator), args.train,
                                              args.allow_runtime_mismatch, args.max_error_percent))


if __name__ == "__main__":
    main()
