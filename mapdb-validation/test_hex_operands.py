#!/usr/bin/env python3
"""Run byte-grammar regressions through the real CLI, using sealed corpus controls.

Usage: python3 mapdb-validation/test_hex_operands.py JAR SCENARIOS
A private production-class restoration can supply --classpath OVERRIDE:JAR.
"""
import argparse
import copy
import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

FIXTURES = [
    "16-roaring/roaring_deserialize_roundtrip.json",
    "14-hyperloglog/hll_interop_roundtrip.json",
    "12-hash-pipeline/hash_bytes_le32.json",
    "12-hash-pipeline/hash64_bytes_le.json",
]


class HexOperands(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.controls = [json.loads((ARGS.scenarios / name).read_text()) for name in FIXTURES]
        cls.invocations = 0

    def run_cli(self, scenario):
        with tempfile.TemporaryDirectory(prefix="mapdb-hex-operand-") as td:
            path = Path(td) / "scenario.json"
            path.write_text(json.dumps(scenario))
            command = (["java", "-cp", ARGS.classpath, "org.mapdb.validation.ValidationRunner"]
                       if ARGS.classpath else ["java", "-jar", str(ARGS.jar)])
            result = subprocess.run(command + [str(path)], capture_output=True, text=True, timeout=30)
            type(self).invocations += 1
            return result

    def test_valid_controls(self):
        for control in self.controls:
            for uppercase in [False, True]:
                scenario = copy.deepcopy(control)
                if uppercase:
                    scenario["operations"][0]["bytes"] = scenario["operations"][0]["bytes"].upper()
                with self.subTest(name=scenario["name"], uppercase=uppercase):
                    result = self.run_cli(scenario)
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertRegex(result.stdout, r"(?m)^PASS " + re.escape(scenario["name"]) + r"$")
                    self.assertIsNone(re.search(r"(?m)^SKIP ", result.stdout), result.stdout)
        empty = {"name": "empty_hash_bytes", "collection": "HashPipeline",
                 "operations": [{"op": "hash_bytes", "bytes": "0x", "seed": "0"}],
                 "assertions": {"hash32": "0x00000000", "hash64": "0x0000000000000000"}}
        result = self.run_cli(empty)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertRegex(result.stdout, r"(?m)^PASS empty_hash_bytes$")

    def test_malformed_bytes_skip_without_observations(self):
        for control in self.controls:
            original = control["operations"][0]["bytes"]
            body = original[2:]
            pairs = [body[i:i + 2] for i in range(0, len(body), 2)]
            low = [i for i, pair in enumerate(pairs) if int(pair, 16) < 16]
            variants = [("wrong-prefix", "zz" + body), ("odd-length", original[:-1])]
            # These aliases preserve canonical byte values, so a passing old
            # runner demonstrates grammar acceptance rather than decoder damage.
            for label, index in [("plus-first", low[0]), ("plus-middle", low[len(low)//2]),
                                 ("plus-last", low[-1])]:
                variants.append((label, "0x" + body[:2*index] + "+" + pairs[index][1] + body[2*index+2:]))
            for pair, replacement in [("00", "-0"), ("ff", "-1")]:
                if pair in pairs:
                    index = pairs.index(pair)
                    variants.append((replacement, "0x" + body[:2*index] + replacement + body[2*index+2:]))
            index = next(i for i, pair in enumerate(pairs) if int(pair, 16) < 10)
            arabic = "".join(chr(0x660 + int(c)) for c in pairs[index])
            for label, replacement in [("unicode-digits", arabic), ("invalid-digit", "G0"), ("space-pair", " 1")]:
                variants.append((label, "0x" + body[:2*index] + replacement + body[2*index+2:]))
            for label, malformed in variants:
                scenario = copy.deepcopy(control)
                scenario["operations"][0]["bytes"] = malformed
                with self.subTest(name=scenario["name"], malformed=label):
                    result = self.run_cli(scenario)
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertRegex(result.stdout, r"(?m)^SKIP " + re.escape(scenario["name"]) + r" : ")
                    self.assertIsNone(re.search(r"(?m)^PASS ", result.stdout), result.stdout)
                    for key in scenario["assertions"]:
                        if key.startswith("comment"):
                            continue
                        self.assertIsNone(re.search(r"^" + re.escape(key) + r": ", result.stdout, re.MULTILINE),
                                          result.stdout)

    @classmethod
    def tearDownClass(cls):
        print("Real CLI invocations:", cls.invocations)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("jar", type=Path)
    parser.add_argument("scenarios", type=Path)
    parser.add_argument("--classpath")
    ARGS = parser.parse_args()
    unittest.main(argv=[__file__], verbosity=2)
