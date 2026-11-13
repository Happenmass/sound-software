#!/usr/bin/env python3
"""Copy the original notices from the pinned Qualcomm SDK into the NPU release."""
import argparse
import hashlib
import json
import shutil
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    lock = json.loads(Path(__file__).with_name("qnn-runtime-lock.json").read_text())
    source = Path.home() / ".cache/codex/dependencies/qairt/2.40.0/notices"
    destination = args.output / "licenses/qualcomm-qairt-2.40"
    for name, expected in lock["notices"].items():
        notice = source / name
        if not notice.is_file() or hashlib.sha256(notice.read_bytes()).hexdigest() != expected:
            raise SystemExit(f"Missing or mismatched {name}. Extract the original notices from {lock['sdkUrl']} into {source}.")
        destination.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(notice, destination / name)


if __name__ == "__main__":
    main()
