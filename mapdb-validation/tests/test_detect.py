"""Run the built validation CLI against first-match and sentinel boundaries.

Usage: python3 mapdb-validation/tests/test_detect.py [path/to/runner.jar]
"""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

jar = Path(sys.argv[1] if len(sys.argv) > 1 else "mapdb-validation/target/mapdb-validation.jar").resolve()
CASES = [
    ("first match", [9, 4, 12], 3, 9),
    ("not found", [-3, 0, 2], 2, None),
    ("empty", [], -2, None),
    ("zero match", [-2, 0, 2], -1, 0),
    ("minimum threshold", [-2147483648, -2147483647, 0], -2147483648, -2147483647),
    ("maximum threshold", [2147483647, 0], 2147483647, None),
    ("maximum match", [2147483647], 2147483646, 2147483647),
]
with tempfile.TemporaryDirectory(prefix="mapdb-detect-") as scratch:
    for name, values, threshold, expected in CASES:
        key = "detect_gt_" + str(threshold)
        scenario = {"name": name, "collection": "ArrayList<i32>", "required_keys": ["size"],
                    "operations": [{"op": "add", "value": value} for value in values],
                    "assertions": {"size": len(values), key: expected}}
        path = Path(scratch) / "scenario.json"
        path.write_text(json.dumps(scenario))
        result = subprocess.run(["java", "-jar", str(jar), str(path)], capture_output=True, text=True)
        assert result.returncode == 0, result.stdout + result.stderr
        emitted = dict(line.split(": ", 1) for line in result.stdout.splitlines() if ": " in line)
        assert key in emitted, (name, "missing emission", result.stdout)
        assert json.loads(emitted[key]) == expected, (name, result.stdout)
        print("PASS:", name, key, emitted[key])
print("PASS: seven actual runner cases")
