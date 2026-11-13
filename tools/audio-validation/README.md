# 只读 Opus 检查

`inspect_opus.py` 使用本机 libopus 1.6.1 的 ctypes API。**没有预设 160/166 字节包格式，没有解密/拆协议、猜包边界或转写。** 真实文件已于 2026-11-07 在明确的 160B 候选边界上验证通过，见 `docs/validation/device-opus-validation-2026-11-07.md`；不据此推断所有固件/录音都同格式。

输入必须是调用方明确给定的 Opus packet 边界：

- 固定存储单元：`--packet-size N [--stride S --payload-offset H --start-offset O]`。N 是精确 Opus packet 字节数，S 是存储单元步长，H 是单元内 payload 偏移；都必须来自协议/实际字节验证。
- 外部 JSON 清单：`--packets-json /external/packets.json`，内容为按解码顺序排列的 `[{"offset":0,"length":123}, ...]`。拒绝越界、重叠和反序，不自动纠正输入。

`--channels 1|2` 明确请求解码输出通道；metadata 另行统计 packet 编码通道。默认输出采样率 48 kHz 是 Opus 标准解码时钟，**不代表推断设备录制采样率**；可显式选择 libopus 支持的其他输出率。

```sh
python3 tools/audio-validation/inspect_opus.py --help
# 确认真实布局后再填写 N/S/H/O，或使用验证过的外部 packet 清单：
python3 tools/audio-validation/inspect_opus.py /external/input.bin \
  --packets-json /external/packets.json --channels 1 --sample-rate 16000 \
  --output /tmp/opus-statistics.json --wav-name device-validation.wav
```

只输出统计 JSON：输入大小/hash、包数、Opus 帧数、采样点/时长、编码通道及包时长分布、完整解码包/帧数、失败分类/数量、RMS/峰值。`opus_packet_parse` 额外核对帧偏移/长度、头部与尾padding；连续性统计比较包边界与包内的相邻采样跳变。没有 packet bytes、PCM 数组或转写内容。libopus 解码按同一状态顺序执行，不插入 PLC/FEC 或静音来掩盖失败。解析/解码成功也不能独立证明解密、包边界与源音频完整性。

WAV 为可选，固定保存至 `~/.cache/codex/audio-validation/`，拒绝覆盖已有文件；只有所有显式提供的包成功解码且无固定单元尾余字节时才提交 WAV。失败不发布可能缺段的 WAV。源文件只读，并在结束时复核 hash。退出码：0=已解码全部指定包，2=不完整，1=配置/执行失败。

边界清单单元测试不使用真实录音。真实文件检查命令单独记录在 codec 验证报告：

```sh
python3 -m unittest discover -s tools/audio-validation -p 'test_*.py' -v
```
