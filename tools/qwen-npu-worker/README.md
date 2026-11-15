# Qwen3-ASR on Qualcomm HTP

This optional build targets ARM64 SM8850 hardware with 4 KB pages. It was verified on the HONOR Magic V6 (PNM-AN10). It uses the OnSpeak47 Qwen3-ASR 0.6B model, fine-tuned for Vietnamese, Chinese and English with a pruned vocabulary. The encoder, prefill and token decoder run on HTP. The worker does WAV preparation and token handling on the CPU. It reports a failed segment if HTP execution fails. Hotwords can be used when generating notes.

Build with JDK 17, Python 3.12+ and Android NDK 28.2.13676358:

```sh
./gradlew :app:assembleDebug -PincludeQwenHtp=true
```

Add `-PincludeQnn=true` to retain the existing SenseVoice and speaker NPU paths in the same development build. The Qwen worker uses its own process and runtime directory, with ONNX Runtime 1.29, the Qualcomm QNN EP 2.7 and QAIRT 2.51. The build verifies its dependencies against `artifacts.json` and includes upstream licenses and notices. Model weights are installed separately.

Download the pinned [OnSpeak47 NPU model release](https://github.com/DS-PNQ/OnSpeak47-npu-models/releases/tag/v2.2.0-npu) and deploy it after installing and opening the app:

```sh
model_dir="$HOME/.cache/codex/models/qwen3-asr-onspeak47-sm8850/v2.2.0-npu"
gh release download v2.2.0-npu -R DS-PNQ/OnSpeak47-npu-models --dir "$model_dir"
python3 tools/qwen-npu-worker/deploy.py --serial SERIAL
```

Select **Qwen3-ASR NPU** in the transcription settings. The app uses one worker per recording. Each model call accepts up to eight seconds. The pinned encoder accepts a fixed mel input of `[1, 128, 800]`, with a 10 ms hop, and the compiled decoder has a 512-token KV cache. A longer single call requires a compatible encoder and decoder export, followed by device validation. Transcript timestamps are approximate, based on the source audio ranges. For a hardware check, run `QwenHtpPhoneTest` with the instrumentation argument `runQwenHtp=true` and place public/synthetic fixtures `mixed.wav` and `dialog.wav` under the app's external files directory at `validation/qwen-htp/`.

## 24-second SM8850 candidate

The standalone FP16 candidate is in `~/.cache/codex/models/qwen3-asr-sm8850/0.6b-fp16-24s-kv2048/`. It uses the official Qwen3-ASR-0.6B-hf checkpoint at `7f1569a48a89f3e3f4dc3a5c9d28bddd903bc76c` and adapts the [published QNN exporter and runner](https://huggingface.co/luyii/Qwen3-ASR-0.6B-QNN-FP16-SM8650/tree/07bd338d6fce2894d0289c13de5cd5502cd5e2f6) for SM8850 HTP v81. QAIRT 2.50 generated 17 contexts. The cache holds 2048 positions shared by the prompt, audio embeddings, and generated text. The runner accepts up to 384,000 mono 16 kHz float32 PCM samples and sends up to three encoder windows into one decoder session.

The manifest records the files and SHA-256 digests. The `validation/` directory records ONNX numerical checks, HTP context metadata, and the official model's CPU result for a public 24-second test sample. For the device check, run `./qwen_runner RUNTIME_DIR MODEL_DIR public-24s.f32 auto` with the matching QAIRT 2.50 Android HTP libraries.

### 8-bit weights and shared decoder contexts

The standalone `~/.cache/codex/models/qwen3-asr-sm8850/0.6b-w8fp16-24s-kv1024/` candidate uses the same official checkpoint and 24-second input. QAIRT compiled seven four-layer decoder contexts that each hold both the 16-token prefill graph and the single-token decode graph. The runner loads each context once. The projection and output-head weights use per-channel INT8, the token embedding table uses INT8 with FP32 row scales, and graph I/O and KV tensors use FP16. The three 3×3 audio convolutions and small non-projection parameters retain FP16. The vocabulary has 151,936 entries.

Model files and frontend data total 970,529,583 bytes, a 65.1% reduction from 2,784,852,959 bytes for the FP16 candidate. Compiling the same seven INT8 decoder blocks separately for prefill and decode took 900,401,376 bytes. Compiling them with weight sharing took 458,264,576 bytes, saving 442,136,800 bytes. The deployed 8-second OnSpeak47 model takes 895,849,330 bytes. These totals exclude executables and runtime libraries.

The 1024-slot cache covers audio embeddings, prompt tokens and generated text. A full 24-second input in automatic language mode leaves 697 generation slots. The host checks verified 17 graph signatures across 10 SM8850 HTP v81 contexts. The model manifest and `validation/sizes.json` record exact files and sizes. Use the same standalone device-check command above with the INT8 model directory.

Both 24-second candidates completed a 21-minute recording on the HONOR Magic V6 through the standalone HTP runners. With identical speaker-merged windows, INT8 took 20:10 and FP16 took 53:08. Their character error rates differed by less than 0.1 percentage point against each of two local machine references. The installed 8-second worker completed the same recording with speaker merging in 2:16. The app continues to use the 8-second worker.

### Fused 28-layer decoder with 64-token prefill

The standalone `~/.cache/codex/models/qwen3-asr-sm8850/0.6b-w8fp16-24s-kv1024-ar64-fused/` candidate combines all 28 layers and greedy token selection in each of two fixed-shape graphs. AR64 prefill and AR1 decode share INT8 weights in a single HTP context. The encoder, embeddings, vocabulary and 1024-slot KV capacity carry over from the split INT8 candidate.

On the Magic V6, the same 24-second public audio took 21.40 s with the split model and 16.05 s with the fused model, based on the median of three runs with each model loaded once. Prefill fell from 5.59 s to 1.39 s. Process peak RSS rose from 182 to 719 MiB. The 8-second excerpt took 7.64 s and 5.57 s, respectively. The model directory contains the verified manifest, standalone runner and benchmark data.

In a separate paired test of the full 24-second clip, the installed worker transcribed three consecutive 8-second slices in 2.27 s and the fused candidate transcribed the clip in one call in 16.15 s. Including one model load, the times to the final text were 4.73 s and 18.06 s. Each figure is the median of three runs.

### Shared GQA decoder export

The standalone `~/.cache/codex/models/qwen3-asr-sm8850/0.6b-w8fp16-24s-kv1024-ar64-gqa/` export keeps the 24-second encoder, per-channel INT8 weights, 1024-slot FP16 KV, all 151,936 vocabulary entries, and 64-token prefill. The 28-layer AR1 graph computes both query heads for each KV head together. AR1 and AR64 share weights in one 614,825,984-byte SM8850 HTP context. Separately compiling the same graphs takes 1,213,674,480 bytes. The model and frontend data total 968,729,335 bytes.

The Magic V6 transcribed the same public 24-second PCM in 5.48 s with the shared GQA export, compared with 2.41 s for three consecutive 8-second calls. Including model load, the medians were 8.80 s and 7.35 s. On the same 21-minute recording, the shared GQA export took 5:16 with 117 windows, and the installed worker took 2:14 with 215 windows. Peak process RSS was 717 vs 1,173 MiB, and sampled peak DMA-BUF was 1,370 vs 1,042 MiB. Each memory metric describes a different view of the worker's memory.

On the long recording, CER against the two local machine references was 9.71% and 24.72% for GQA, compared with 12.51% and 25.60% for the installed worker. On a 23.07-second ASCEND excerpt with human labels, CER was 17.42% and 10.97%, respectively. The export contains `reproduce/` build instructions and `validation/` with the public PCM, HTP metadata, checksum verification and aggregate results. The installed app uses the 8-second worker.

### GQA prefill and shared historical KV

The standalone `~/.cache/codex/models/qwen3-asr-sm8850/0.6b-w8fp16-24s-kv1024-gqa64-shared-kv/` export uses grouped attention in AR64 and AR1, with shared INT8 decoder weights and the full vocabulary. Enable `QWEN_HTP_SHARED_IO=1` to bind both decoder graphs to the same 1024-slot historical KV. The model and frontend data total 963,506,935 bytes.

In a four-configuration Magic V6 test on the same 24-second public audio, both changes together reduced median inference from 5.30 s to 4.39 s, and peak DMA-BUF from 1,370.15 MiB to 1,226.15 MiB. The six prefill graph calls fell from 1.34 s to 0.43 s. Shared historical KV accounted for 112 MiB of the DMA-BUF reduction. All 75 output token IDs matched the previous 24-second export.
