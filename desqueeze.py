#!/usr/bin/env python3
"""
Fujifilm X100VI – Anamorphic desqueeze + MOV → MP4 converter
Squeeze factor: 1.33x (horizontal stretch)

Usage:
    python3 desqueeze.py input.MOV [output.mp4]
    python3 desqueeze.py *.MOV                  # batch – outputs to ./desqueezed/
    python3 desqueeze.py /path/to/folder/       # batch – all MOV files in folder
"""

import argparse
import os
import subprocess
import sys
from pathlib import Path

SQUEEZE = 1.33          # anamorphic lens squeeze factor
CRF = 18                # quality: 0=lossless, 18=visually lossless, 23=default
PRESET = "slow"         # encoding speed vs compression trade-off
AUDIO_BITRATE = "320k"  # preserve high-quality audio


def probe_video(path: Path) -> dict:
    """Return width, height, frame-rate string from the input file."""
    cmd = [
        "ffprobe", "-v", "error",
        "-select_streams", "v:0",
        "-show_entries", "stream=width,height,r_frame_rate",
        "-of", "default=noprint_wrappers=1",
        str(path),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, check=True)
    info = {}
    for line in result.stdout.splitlines():
        key, _, val = line.partition("=")
        info[key.strip()] = val.strip()
    return info


def desqueeze(
    input_path: Path,
    output_path: Path,
    squeeze: float = SQUEEZE,
    crf: int = CRF,
    preset: str = PRESET,
) -> None:
    info = probe_video(input_path)
    w = int(info["width"])
    h = int(info["height"])

    # Desqueeze: expand the width by the squeeze factor, keep height, round to even
    new_w = round(w * squeeze / 2) * 2

    print(f"  Source : {w}x{h}  →  Desqueezed : {new_w}x{h}")

    cmd = [
        "ffmpeg", "-y",
        "-i", str(input_path),
        "-vf", f"scale={new_w}:{h}:flags=lanczos",
        "-c:v", "libx264",
        "-crf", str(crf),
        "-preset", preset,
        "-pix_fmt", "yuv420p",
        "-c:a", "aac",
        "-b:a", AUDIO_BITRATE,
        "-map_metadata", "0",
        "-movflags", "+faststart",
        str(output_path),
    ]

    print(f"  Output : {output_path}")
    result = subprocess.run(cmd, stderr=subprocess.PIPE, text=True)
    if result.returncode != 0:
        print(f"\n[ERROR] FFmpeg failed:\n{result.stderr}", file=sys.stderr)
        sys.exit(1)


def collect_inputs(targets: list[str]) -> list[Path]:
    paths = []
    for t in targets:
        p = Path(t)
        if p.is_dir():
            paths.extend(sorted(p.glob("*.MOV")) + sorted(p.glob("*.mov")))
        else:
            paths.append(p)
    return paths


def default_output(input_path: Path, output_dir: Path) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    return output_dir / (input_path.stem + ".mp4")


def main():
    parser = argparse.ArgumentParser(
        description="Desqueeze Fujifilm X100VI anamorphic MOV files and convert to MP4."
    )
    parser.add_argument("inputs", nargs="+", help="MOV file(s) or folder(s)")
    parser.add_argument(
        "-o", "--output",
        help="Output file (single input) or output directory (batch)",
    )
    parser.add_argument(
        "-s", "--squeeze",
        type=float, default=SQUEEZE,
        help=f"Squeeze factor (default: {SQUEEZE})",
    )
    parser.add_argument(
        "--crf",
        type=int, default=CRF,
        help=f"H.264 CRF quality (lower = better, default: {CRF})",
    )
    parser.add_argument(
        "--preset",
        default=PRESET,
        choices=["ultrafast","superfast","veryfast","faster","fast","medium","slow","slower","veryslow"],
        help=f"x264 preset (default: {PRESET})",
    )
    args = parser.parse_args()

    crf = args.crf
    preset = args.preset

    inputs = collect_inputs(args.inputs)
    if not inputs:
        print("[ERROR] No MOV files found.", file=sys.stderr)
        sys.exit(1)

    single = len(inputs) == 1

    for i, src in enumerate(inputs, 1):
        if not src.exists():
            print(f"[SKIP] {src} not found", file=sys.stderr)
            continue

        if single and args.output and not Path(args.output).is_dir():
            dst = Path(args.output)
        else:
            out_dir = Path(args.output) if args.output else src.parent / "desqueezed"
            dst = default_output(src, out_dir)

        print(f"\n[{i}/{len(inputs)}] {src.name}")
        desqueeze(src, dst, squeeze=args.squeeze, crf=crf, preset=preset)

    print("\nDone.")


if __name__ == "__main__":
    main()
