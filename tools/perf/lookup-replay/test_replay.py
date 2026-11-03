import copy
import unittest
from unittest.mock import Mock, patch

from replay import Adb, SCOPE, calibrate_reports, prepare_compilation, replay_remote_path, summarize, verify_compilation_state


def report(multiplier=1):
    samples = []
    for index in range(3):
        samples.append(dict(caseId=f"lookup-{index}", ok=True, warmup=False,
                            mountQueueMs=10 * multiplier, prepareMs=100 * multiplier,
                            readyToDrawMs=20 * multiplier, drawToCommitMs=5 * multiplier,
                            firstDrawTotalMs=130 * multiplier, frameCommitTotalMs=135 * multiplier))
    return dict(protocol=1, scope=SCOPE, source="hoshidicts-output", bundleSha256="bundle",
                regime="warm-process", compilationMode="verify", runtime=dict(
                buildType="debug", rendererSourceSha256="same-renderer", sdk=36,
                fonts={"fonts.xml": "same-fonts"}, refreshRateHz=90), samples=samples)


class ReplayTests(unittest.TestCase):
    def test_baseline_profile_clears_learned_profiles_and_installs_packaged_rules(self):
        adb = Mock(package="app.test")
        adb.run.side_effect = [b"Success", b"36", b"", b"Broadcast completed: result=1", b"", b"Success",
                               b"x86_64: [status=speed-profile] [reason=cmdline] [primary-abi]"]
        self.assertEqual("speed-profile", prepare_compilation(adb, "baseline-profile")[0]["filter"])
        commands = [call.args for call in adb.run.call_args_list]
        self.assertEqual(("shell", "pm", "art", "clear-app-profiles", "app.test"), commands[2])
        self.assertEqual(("shell", "am", "force-stop", "app.test"), commands[4])
        self.assertIn("speed-profile", commands[5])

    def test_missing_packaged_profile_is_not_replaced_with_jit_training(self):
        adb = Mock(package="app.test")
        adb.run.side_effect = [b"Success", b"36", b"", b"Broadcast completed: result=0"]
        with self.assertRaises(RuntimeError):
            prepare_compilation(adb, "baseline-profile")
        self.assertEqual(4, adb.run.call_count)

    def test_replay_storage_paths_are_scoped_and_reject_unsafe_run_ids(self):
        run_id = "replay-0123456789abcdef"
        self.assertEqual(f"files/lookup-replay/{run_id}", replay_remote_path("app.test", "private", run_id))
        self.assertEqual(f"/data/user/0/app.test/files/lookup-replay/{run_id}",
                         replay_remote_path("app.test", "emulator", run_id))
        for package, storage, run in (("../app", "emulator", run_id), ("..", "emulator", run_id),
                                      ("app..test", "emulator", run_id), ("app.test", "other", run_id),
                                      ("app.test", "emulator", "../../files")):
            with self.subTest(package=package, storage=storage, run=run), self.assertRaises(ValueError):
                replay_remote_path(package, storage, run)

    def test_emulator_transport_selects_app_uid_and_private_still_uses_run_as(self):
        def reply(*args):
            if args == ("get-state",):
                return b"device"
            if args == ("shell", "getprop", "ro.kernel.qemu"):
                return b"1"
            if args[:5] == ("shell", "cmd", "package", "list", "packages"):
                return b"package:app.test uid:10152\n"
            if args == ("shell", "su", "10152", "id", "-u"):
                return b"10152"
            return b""
        for storage, prefix in (("emulator", ("su", "10152")), ("private", ("run-as", "app.test"))):
            with self.subTest(storage=storage), patch.object(Adb, "run", side_effect=reply) as run:
                adb = Adb("adb", "emulator-5580", "app.test", storage)
                adb.read("path")
                run.assert_called_with("exec-out", *prefix, "cat", "path")
                adb.file_command("mkdir", "-p", "path")
                run.assert_called_with("shell", *prefix, "mkdir", "-p", "path")

    def test_privileged_transport_refuses_phones_and_non_qemu_targets_before_staging(self):
        with patch.object(Adb, "run") as run, self.assertRaises(ValueError):
            Adb("adb", "phone-serial", "app.test", "emulator")
        run.assert_not_called()
        with patch.object(Adb, "run", return_value=b"device") as run, self.assertRaises(RuntimeError):
            Adb("adb", "emulator-5580", "app.test", "emulator")
        self.assertEqual(2, run.call_count)

    def test_compilation_command_success_cannot_substitute_for_actual_filter(self):
        dump = "  x86_64: [status=verify] [reason=cmdline] [primary-abi]\n"
        self.assertEqual("verify", verify_compilation_state(dump, "verify")[0]["filter"])
        with self.assertRaises(RuntimeError):
            verify_compilation_state(dump, "speed")
        with self.assertRaises(RuntimeError):
            verify_compilation_state("No dexopt status", "verify")

    def test_compilation_verification_ignores_secondary_abi_and_checks_all_primary_paths(self):
        primary = "x86_64: [status=speed] [reason=cmdline] [primary-abi]\n"
        secondary = "arm64: [status=verify] [reason=install]\n"
        self.assertEqual(1, len(verify_compilation_state(primary + secondary, "speed")))
        with self.assertRaises(RuntimeError):
            verify_compilation_state(primary + "x86_64: [status=verify] [reason=install] [primary-abi]\n", "speed")
        self.assertEqual([], verify_compilation_state("", "as-installed"))

    def test_sampled_diagnostics_cannot_calibrate(self):
        for key, value in (("diagnosticOnly", True), ("methodSamplingUs", 1000)):
            changed = report()
            changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                calibrate_reports(changed, copy.deepcopy(changed), ["lookup-0", "lookup-1"])

    def test_fits_training_only_and_evaluates_independent_holdout(self):
        result = calibrate_reports(report(2), report(), ["lookup-0", "lookup-1"])
        self.assertTrue(result["validated"])
        self.assertEqual(["lookup-2"], result["holdouts"])
        self.assertEqual(2, result["phases"]["prepareMs"]["factor"])
        self.assertEqual(0, result["phases"]["prepareMs"]["holdout"][0]["errorPercent"])
        self.assertEqual(260, result["totals"][0]["predictedFirstDrawMs"])

    def test_unseen_workload_error_is_not_hidden(self):
        phone = report(2)
        phone["samples"][-1]["prepareMs"] = 1000
        result = calibrate_reports(phone, report(), ["lookup-0", "lookup-1"])
        self.assertFalse(result["validated"])
        self.assertEqual(80, result["phases"]["prepareMs"]["holdout"][0]["errorPercent"])

    def test_requires_matching_workload_sources_and_compile_state(self):
        for key, value in (("bundleSha256", "other"), ("source", "synthetic-fixture"),
                           ("regime", "cold-process"), ("compilationMode", "speed")):
            changed = report()
            changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                calibrate_reports(changed, report(), ["lookup-0", "lookup-1"])
        for key in ("rendererSourceSha256", "buildType"):
            changed = report()
            changed["runtime"][key] = "different"
            with self.subTest(key=key), self.assertRaises(ValueError):
                calibrate_reports(changed, report(), ["lookup-0", "lookup-1"])

    def test_unknown_compilation_and_synthetic_inputs_cannot_calibrate(self):
        for key, value in (("compilationMode", "as-installed"), ("source", "synthetic-fixture")):
            a = report()
            a[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                calibrate_reports(a, copy.deepcopy(a), ["lookup-0", "lookup-1"])

    def test_runtime_mismatch_is_exploratory_not_validated(self):
        phone = report(2)
        phone["runtime"]["sdk"] = 34
        with self.assertRaises(ValueError):
            calibrate_reports(phone, report(), ["lookup-0", "lookup-1"])
        result = calibrate_reports(phone, report(), ["lookup-0", "lookup-1"], True)
        self.assertFalse(result["validated"])
        self.assertEqual(["sdk"], result["runtimeMismatches"])

    def test_warmups_excluded_failed_samples_rejected_and_holdouts_required(self):
        a = report()
        warmup = dict(a["samples"][0], warmup=True, prepareMs=10000)
        a["samples"].append(warmup)
        self.assertEqual(100, summarize(a)["lookup-0"]["prepareMs"])
        self.assertFalse(calibrate_reports(a, report(), ["lookup-0", "lookup-1", "lookup-2"])["validated"])
        a["samples"][0]["ok"] = False
        with self.assertRaises(ValueError):
            calibrate_reports(a, report(), ["lookup-0", "lookup-1"])

    def test_missing_phase_cannot_pass(self):
        a = report()
        del a["samples"][-1]["readyToDrawMs"]
        self.assertFalse(calibrate_reports(a, report(), ["lookup-0", "lookup-1"])["validated"])

    def test_invalid_timings_and_tolerances_rejected(self):
        for value in (0, -1, float("nan"), float("inf")):
            a = report()
            a["samples"][-1]["firstDrawTotalMs"] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                calibrate_reports(a, report(), ["lookup-0", "lookup-1"])
        for value in (-1, float("nan"), float("inf")):
            with self.subTest(tolerance=value), self.assertRaises(ValueError):
                calibrate_reports(report(), report(), ["lookup-0", "lookup-1"], max_error_percent=value)

    def test_total_error_cannot_pass_even_if_phases_fit(self):
        a = report()
        a["samples"][-1]["firstDrawTotalMs"] = 1000
        self.assertFalse(calibrate_reports(a, report(), ["lookup-0", "lookup-1"])["validated"])


if __name__ == "__main__":
    unittest.main()
