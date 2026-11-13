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
    parser.add_argument("--sdk", choices=("2.40", "2.50"), default="2.40")
    args = parser.parse_args()
    lock_name = "qnn-runtime-lock.json" if args.sdk == "2.40" else "speaker-npu-runtime-lock.json"
    lock = json.loads(Path(__file__).with_name(lock_name).read_text())
    source = Path.home() / ".cache/codex/dependencies/qairt" / (
        "2.40.0/notices" if args.sdk == "2.40" else "2.50.0/sdk/qairt/2.50.0.260828"
    )
    destination = args.output / f"licenses/qualcomm-qairt-{args.sdk}"
    for name, expected in lock["notices"].items():
        notice = source / name
        if not notice.is_file() or hashlib.sha256(notice.read_bytes()).hexdigest() != expected:
            raise SystemExit(f"Missing or mismatched {name}. Extract the original notices from {lock['sdkUrl']} into {source}.")
        destination.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(notice, destination / name)


if __name__ == "__main__":
    main()
