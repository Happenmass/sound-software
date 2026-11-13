#!/usr/bin/env python3
"""Read-only Opus packet inspection using explicitly supplied packet boundaries.

No packet-format discovery, decryption, resampling, concealment, or transcription.
"""
import argparse
from array import array
from collections import Counter
import ctypes as C
import hashlib
import json
import math
from pathlib import Path
import sys
import wave

LIBOPUS = "/opt/homebrew/Cellar/opus/1.6.1/lib/libopus.0.dylib"
WAV_CACHE = Path.home() / ".cache/codex/audio-validation"


def boundaries(size, packet_size=None, stride=None, start_offset=0, payload_offset=0, manifest=None):
    """Return ordered (byte offset, packet length) without inferring a layout."""
    if manifest is not None:
        if not isinstance(manifest, list) or not manifest:
            raise ValueError("packet manifest must be a nonempty array")
        items = [(item["offset"], item["length"]) for item in manifest]
        layout = {"type": "explicit_manifest", "unselected_bytes": size - sum(n for _, n in items)}
    else:
        if packet_size is None or not 1 <= packet_size <= 65535:
            raise ValueError("packet-size must be 1..65535")
        stride = packet_size if stride is None else stride
        if start_offset < 0 or start_offset > size or payload_offset < 0 or stride < payload_offset + packet_size:
            raise ValueError("Invalid explicit fixed-unit layout")
        count, trailing = divmod(size - start_offset, stride)
        items = [(start_offset + index * stride + payload_offset, packet_size) for index in range(count)]
        layout = {"type": "explicit_fixed_units", "packet_size": packet_size, "stride": stride,
                  "start_offset": start_offset, "payload_offset": payload_offset,
                  "complete_units": count, "trailing_bytes": trailing,
                  "explicit_container_overhead_bytes": start_offset + count * (stride - packet_size)}
    previous_end = 0
    for offset, length in items:
        if type(offset) is not int or type(length) is not int:
            raise ValueError("Offsets and lengths must be integers")
        if offset < previous_end or length <= 0 or length > 65535 or offset + length > size:
            raise ValueError("Packet ranges must be ordered, non-overlapping, in-bounds, and 1..65535 bytes")
        previous_end = offset + length
    if not items:
        raise ValueError("No complete supplied packets")
    return items, layout


class Opus:
    def __init__(self, library):
        self.lib = C.CDLL(str(library))
        bytep, floatp = C.POINTER(C.c_ubyte), C.POINTER(C.c_float)
        specs = {
            "opus_get_version_string": ([], C.c_char_p),
            "opus_packet_get_nb_frames": ([bytep, C.c_int32], C.c_int),
            "opus_packet_get_nb_channels": ([bytep], C.c_int),
            "opus_packet_get_nb_samples": ([bytep, C.c_int32, C.c_int32], C.c_int),
            "opus_packet_get_bandwidth": ([bytep], C.c_int),
            "opus_packet_parse": ([bytep, C.c_int32, bytep, C.POINTER(bytep), C.POINTER(C.c_int16), C.POINTER(C.c_int)], C.c_int),
            "opus_decoder_create": ([C.c_int32, C.c_int, C.POINTER(C.c_int)], C.c_void_p),
            "opus_decode_float": ([C.c_void_p, bytep, C.c_int32, floatp, C.c_int, C.c_int], C.c_int),
            "opus_decoder_destroy": ([C.c_void_p], None),
        }
        for name, (argtypes, restype) in specs.items():
            fn = getattr(self.lib, name)
            fn.argtypes, fn.restype = argtypes, restype


def inspect(args):
    source = args.input.resolve()
    size = source.stat().st_size
    manifest = json.loads(args.packets_json.read_text()) if args.packets_json else None
    spans, layout = boundaries(size, args.packet_size, args.stride, args.start_offset, args.payload_offset, manifest)
    opus = Opus(args.libopus)
    report = {"schema_version": 1, "status": "running", "source_bytes": size,
              "libopus": opus.lib.opus_get_version_string().decode(), "layout": layout,
              "packet_count": len(spans), "sample_rate": args.sample_rate,
              "output_channels": args.channels, "packets_metadata_valid": 0,
              "packets_fully_decoded": 0, "opus_frames_fully_decoded": 0,
              "packets_structure_valid": 0, "decoder_state_resets": 0, "fec_or_plc_calls": 0,
              "pcm_samples_per_channel": 0, "failure_count": 0,
              "failure_categories": {}, "encoded_channels_histogram": {},
              "packet_frame_count_histogram": {}, "packet_duration_ms_histogram": {},
              "metadata_duration_seconds": 0.0,
              "notes": ["Packet boundaries are caller supplied, not discovered or proven by Opus parsing.",
                        "Decode success does not prove source authenticity, decryption, or end-to-end completeness.",
                        "Decoder state is sequential; no PLC/FEC or silence is inserted for failed packets.",
                        "PCM duration/RMS cover decoded samples only; failures may leave gaps.",
                        "Requested PCM output rate/channels do not prove the recorder's native capture format."]}
    with source.open("rb") as f:
        report["source_sha256"] = hashlib.file_digest(f, "sha256").hexdigest()
    error = C.c_int()
    decoder = opus.lib.opus_decoder_create(args.sample_rate, args.channels, C.byref(error))
    if not decoder or error.value:
        raise RuntimeError(f"opus_decoder_create failed with code {error.value}")
    capacity = args.sample_rate * 120 // 1000
    pcm = (C.c_float * (capacity * args.channels))()
    frames_hist, channels_hist, durations_hist, failures = Counter(), Counter(), Counter(), Counter()
    payload_hist, header_hist, padding_hist, bandwidth_hist = Counter(), Counter(), Counter(), Counter()
    sum_squares, scalar_count, peak, above_unity = 0.0, 0, 0.0, 0
    boundary_jumps, within_jump_squares, within_jump_count, previous_tail = [], 0.0, 0, None
    wav_writer = None
    wav_temp = None
    wav_temp_owned = False
    wav_path = None
    try:
        if args.wav_name:
            if Path(args.wav_name).name != args.wav_name or not args.wav_name.endswith(".wav"):
                raise ValueError("wav-name must be a simple .wav filename without directories")
            WAV_CACHE.mkdir(parents=True, exist_ok=True)
            wav_path = (WAV_CACHE / args.wav_name).resolve()
            if wav_path.parent != WAV_CACHE.resolve() or wav_path == source or wav_path.exists():
                raise ValueError("WAV target must be new and inside global audio cache")
            wav_temp = wav_path.with_suffix(".wav.partial")
            if wav_temp.exists():
                raise ValueError("Partial WAV already exists; inspect it before retrying")
            wav_writer = wave.open(str(wav_temp), "wb")
            wav_temp_owned = True
            wav_writer.setnchannels(args.channels)
            wav_writer.setsampwidth(2)
            wav_writer.setframerate(args.sample_rate)
        with source.open("rb") as f:
            for offset, length in spans:
                f.seek(offset)
                packet = f.read(length)
                if len(packet) != length:
                    failures["source_changed_or_short_read"] += 1
                    continue
                data = (C.c_ubyte * length).from_buffer_copy(packet)
                frame_count = opus.lib.opus_packet_get_nb_frames(data, length)
                channels = opus.lib.opus_packet_get_nb_channels(data)
                expected = opus.lib.opus_packet_get_nb_samples(data, length, args.sample_rate)
                if frame_count <= 0 or expected <= 0 or expected > capacity or channels not in (1, 2):
                    failures["invalid_packet_metadata"] += 1
                    continue
                # Exact packet parse checks frame pointers/sizes and distinguishes declared padding.
                pointers = (C.POINTER(C.c_ubyte) * 48)()
                sizes = (C.c_int16 * 48)()
                toc, payload_offset = C.c_ubyte(), C.c_int()
                parsed = opus.lib.opus_packet_parse(data, length, C.byref(toc), pointers, sizes, C.byref(payload_offset))
                base_address = C.addressof(data)
                frame_ranges = []
                if 0 < parsed <= 48:
                    for i in range(parsed):
                        address = C.cast(pointers[i], C.c_void_p).value
                        frame_ranges.append((address - base_address if address else -1, int(sizes[i])))
                if parsed != frame_count or not frame_ranges or any(a < 0 or n < 0 or n > 1275 or a + n > length for a, n in frame_ranges):
                    failures["invalid_packet_structure"] += 1
                    continue
                report["packets_structure_valid"] += 1
                payload_hist[sum(n for _, n in frame_ranges)] += 1
                header_hist[payload_offset.value] += 1
                padding_hist[length - max(a + n for a, n in frame_ranges)] += 1
                bandwidth_hist[opus.lib.opus_packet_get_bandwidth(data)] += 1
                report["packets_metadata_valid"] += 1
                frames_hist[frame_count] += 1
                channels_hist[channels] += 1
                durations_hist[f"{expected * 1000 / args.sample_rate:g}"] += 1
                report["metadata_duration_seconds"] += expected / args.sample_rate
                count = opus.lib.opus_decode_float(decoder, data, length, pcm, capacity, 0)
                if count < 0:
                    failures[f"opus_decode_error_{count}"] += 1
                    continue
                if count != expected:
                    failures["decoded_sample_count_mismatch"] += 1
                    continue
                values = [float(pcm[i]) for i in range(count * args.channels)]
                if not all(math.isfinite(value) for value in values):
                    failures["non_finite_pcm"] += 1
                    continue
                report["packets_fully_decoded"] += 1
                report["opus_frames_fully_decoded"] += frame_count
                report["pcm_samples_per_channel"] += count
                sum_squares += sum(value * value for value in values)
                scalar_count += len(values)
                peak = max(peak, max(abs(value) for value in values))
                above_unity += sum(abs(value) > 1.0 for value in values)
                if previous_tail is not None:
                    boundary_jumps.extend(abs(values[channel] - previous_tail[channel]) for channel in range(args.channels))
                previous_tail = values[-args.channels:]
                within_jump_squares += sum((values[i] - values[i - args.channels]) ** 2 for i in range(args.channels, len(values)))
                within_jump_count += len(values) - args.channels
                if wav_writer:
                    integers = array("h", (max(-32768, min(32767, round(value * 32768))) for value in values))
                    if sys.byteorder != "little":
                        integers.byteswap()
                    wav_writer.writeframesraw(integers.tobytes())
        with source.open("rb") as f:
            if hashlib.file_digest(f, "sha256").hexdigest() != report["source_sha256"]:
                failures["source_changed_during_inspection"] += 1
        report.update(failure_count=sum(failures.values()), failure_categories=dict(failures),
                      encoded_channels_histogram=dict(channels_hist), packet_frame_count_histogram=dict(frames_hist),
                      packet_duration_ms_histogram=dict(durations_hist),
                      decoded_duration_seconds=report["pcm_samples_per_channel"] / args.sample_rate,
                      rms=math.sqrt(sum_squares / scalar_count) if scalar_count else None,
                      peak_absolute_float=peak if scalar_count else None, samples_above_unity=above_unity)
        report.update(packet_frame_payload_bytes_histogram=dict(payload_hist), packet_header_bytes_histogram=dict(header_hist),
                      packet_trailing_padding_bytes_histogram=dict(padding_hist), opus_bandwidth_enum_histogram=dict(bandwidth_hist),
                      continuity={"packet_boundary_jump_rms": math.sqrt(sum(x*x for x in boundary_jumps) / len(boundary_jumps)) if boundary_jumps else None,
                                  "packet_boundary_jump_max": max(boundary_jumps) if boundary_jumps else None,
                                  "within_packet_adjacent_jump_rms": math.sqrt(within_jump_squares / within_jump_count) if within_jump_count else None,
                                  "note": "Descriptive continuity statistics only, not an audio-quality acceptance threshold"})
        complete = not failures and not layout.get("trailing_bytes", 0) and report["packets_fully_decoded"] == len(spans)
        report["status"] = "decoded_all_supplied_packets" if complete else "incomplete"
        if wav_writer:
            wav_writer.close()
            wav_writer = None
            if complete:
                wav_temp.replace(wav_path)
                wav_temp = None
                report["wav_path"] = str(wav_path)
            else:
                report["wav_written"] = False
                report["wav_note"] = "No WAV published because packets failed or fixed-unit trailing bytes remain"
        return report
    finally:
        opus.lib.opus_decoder_destroy(decoder)
        if wav_writer:
            wav_writer.close()
        if wav_temp_owned and wav_temp and wav_temp.exists():
            wav_temp.unlink()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("input", type=Path)
    group = p.add_mutually_exclusive_group(required=True)
    group.add_argument("--packet-size", type=int, help="Explicit Opus packet bytes; no default")
    group.add_argument("--packets-json", type=Path, help="Array of exact {offset,length} packet byte ranges")
    p.add_argument("--stride", type=int, help="Explicit storage unit bytes; defaults to packet-size")
    p.add_argument("--start-offset", type=int, default=0)
    p.add_argument("--payload-offset", type=int, default=0)
    p.add_argument("--sample-rate", type=int, choices=[8000, 12000, 16000, 24000, 48000], default=48000)
    p.add_argument("--channels", type=int, choices=[1, 2], required=True, help="Requested PCM output channels")
    p.add_argument("--libopus", type=Path, default=Path(LIBOPUS))
    p.add_argument("--wav-name", help="Optional new filename inside ~/.cache/codex/audio-validation; only published on complete decode")
    p.add_argument("--output", type=Path, help="Optional statistics-only JSON, no packet bytes or text")
    args = p.parse_args()
    if args.packets_json and (args.stride is not None or args.start_offset or args.payload_offset):
        p.error("Fixed-unit options cannot be combined with packets-json")
    if args.output and args.output.resolve() == args.input.resolve():
        p.error("Statistics output must not overwrite the input")
    try:
        report = inspect(args)
        code = 0 if report["status"] == "decoded_all_supplied_packets" else 2
    except Exception as exc:
        # Errors contain operational paths/codes only; packet contents are never interpolated.
        report = {"schema_version": 1, "status": "failed", "error": type(exc).__name__, "message": str(exc)}
        code = 1
    output = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output)
    print(output, end="")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
