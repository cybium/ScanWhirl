#!/usr/bin/env python3
"""Create a new 1.3.0 handover folder, never overwrite an existing folder."""
import argparse
from pathlib import Path
import re
import shutil
import sys

try:
    from .verify_aab import sha256, verify
except ImportError:
    from verify_aab import sha256, verify

VERSION = "1.3.0"
CODE = 26112001
REQUIRED = {"CONSOLE_STEPS.txt", "RELEASE_NOTES_PLAY.txt", "STORE_LISTING.txt",
            "STORE_LISTING_hi-IN.txt", "STORE_LISTING_es-419.txt",
            "STORE_LISTING_pt-BR.txt", "STORE_LISTING_id.txt", "STORE_LISTING_fr-FR.txt",
            "RISKS.txt", "OWNER_PHONE_CHECKLIST.txt"}


def normalized(path):
    return path.read_text(encoding="utf-8-sig").replace("\r\n", "\n").replace("\r", "\n").rstrip("\n") + "\n"


def assemble(args):
    if args.out.exists():
        raise ValueError(f"output already exists: {args.out}")
    if not re.fullmatch(r"[0-9a-fA-F]{40}", args.sha):
        raise ValueError("--sha must be a full 40-character Git SHA")
    for label, value in (("gate", args.gate_run), ("e2e", args.e2e_run)):
        if not re.fullmatch(r"[1-9][0-9]*", value):
            raise ValueError(f"{label} run must be a numeric GitHub run ID")
    verdict = normalized(args.security)
    if not verdict.strip():
        raise ValueError("empty security verdict")
    files = {p.name: p for p in args.drafts.iterdir() if p.is_file() and p.suffix.lower() in (".txt", ".md")}
    missing = REQUIRED - files.keys()
    if missing:
        raise ValueError(f"missing release drafts: {sorted(missing)}")
    if "PROVENANCE.txt" in files:
        raise ValueError("drafts must not supply PROVENANCE.txt")
    # Decode all inputs and verify before creating anything.
    texts = {name: normalized(path) for name, path in files.items()}
    source_hash = sha256(args.aab)
    good, report = verify(args.aab, CODE, VERSION, bundletool=args.bundletool)
    if not good:
        raise ValueError("AAB verification refused packaging:\n" + report)
    if source_hash != sha256(args.aab):
        raise ValueError("AAB changed during verification")
    provenance = (f"ScanWhirl {VERSION}\nSource SHA (lead supplied): {args.sha.lower()}\n"
                  f"versionCode: {CODE}\nAAB SHA-256: {source_hash}\n"
                  f"AAB size: {args.aab.stat().st_size} bytes\n"
                  f"Gate run: https://github.com/cybium/ScanWhirl/actions/runs/{args.gate_run}\n"
                  f"E2E run: https://github.com/cybium/ScanWhirl/actions/runs/{args.e2e_run}\n"
                  "Run verdicts/source-to-binary correspondence: not independently verified by this script.\n"
                  "Signer must be compared manually with Play Console upload key before upload.\n"
                  "Draft claims/native translations require final lead approval; copied without rewriting claims.\n"
                  "Security verdict (lead supplied, not independently validated):\n" + verdict +
                  "\nAAB verification:\n" + report)
    args.out.mkdir(parents=True, exist_ok=False)
    target = args.out / f"ScanWhirl-{VERSION}.aab"
    try:
        shutil.copyfile(args.aab, target)
        if sha256(target) != source_hash:
            raise ValueError("copied AAB differs; incomplete output must not be used")
        for name, text in texts.items():
            (args.out / name).write_text(text, encoding="utf-8", newline="\n")
        (args.out / "PROVENANCE.txt").write_text(provenance, encoding="utf-8", newline="\n")
    except Exception:
        # Preserve evidence; never silently remove or overwrite a partial handover.
        print(f"INCOMPLETE output retained: {args.out}", file=sys.stderr)
        raise
    for path in sorted(args.out.iterdir()):
        print(f"{path.name}\t{path.stat().st_size} bytes\t{sha256(path)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--aab", required=True, type=Path)
    parser.add_argument("--drafts", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--gate-run", required=True)
    parser.add_argument("--e2e-run", required=True)
    parser.add_argument("--security", required=True, type=Path)
    parser.add_argument("--bundletool", type=Path)
    args = parser.parse_args()
    try:
        assemble(args)
        return 0
    except Exception as exc:
        print(f"ASSEMBLE FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
