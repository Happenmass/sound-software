"""Extend the pinned sherpa speaker extractor with ordered, variable-length batches."""
from pathlib import Path

def patch_batch_sources(root: Path):
    def replace(name, before, after):
        path = root / name
        text = path.read_text()
        if text.count(before) != 1:
            raise RuntimeError(f"Unexpected sherpa source: {name}")
        path.write_text(text.replace(before, after))

    signature = "std::vector<std::vector<float>> ComputeBatch(const std::vector<OnlineStream *> &streams) const"
    replace("speaker-embedding-extractor.h", "  std::vector<float> Compute(OnlineStream *s) const;",
            f"  std::vector<float> Compute(OnlineStream *s) const;\n  {signature};")
    replace("speaker-embedding-extractor.cc", "  return impl_->Compute(s);\n}",
            f"  return impl_->Compute(s);\n}}\n\n{signature.replace('ComputeBatch', 'SpeakerEmbeddingExtractor::ComputeBatch')}" + """ {
  return impl_->ComputeBatch(streams);
}""")
    replace("speaker-embedding-extractor-impl.h", "  virtual std::vector<float> Compute(OnlineStream *s) const = 0;",
            f"  virtual std::vector<float> Compute(OnlineStream *s) const = 0;\n  virtual {signature}" + """ {
    std::vector<std::vector<float>> result;
    for (auto *stream : streams) result.push_back(Compute(stream));
    return result;
  }""")
    name = "speaker-embedding-extractor-general-impl.h"
    replace(name, "  std::vector<float> Compute(OnlineStream *s) const override {", """  std::vector<float> Compute(OnlineStream *s) const override {
    return ComputeBatch({s}).front();
  }

  """ + signature + """ override {
    std::vector<std::vector<float>> features(streams.size()), results(streams.size());
    std::vector<Ort::Value> inputs;
    std::vector<size_t> indices;
    for (size_t index = 0; index < streams.size(); ++index) {
      if (!IsReady(streams[index])) continue;
      inputs.push_back(Features(streams[index], features[index]));
      indices.push_back(index);
    }
    auto embeddings = model_.ComputeBatch(std::move(inputs));
    for (size_t i = 0; i < indices.size(); ++i) {
      const auto *begin = embeddings[i].GetTensorData<float>();
      const auto count = embeddings[i].GetTensorTypeAndShapeInfo().GetElementCount();
      results[indices[i]].assign(begin, begin + count);
    }
    return results;
  }

 private:
  Ort::Value Features(OnlineStream *s, std::vector<float> &features) const {""")
    path = root / name
    text = path.read_text()
    start = text.index("    if (num_frames <= 0)")
    end = text.index("    s->GetNumProcessedFrames() += num_frames;", start)
    text = text[:start] + "    features = s->GetFrames(s->GetNumProcessedFrames(), num_frames);\n\n" + text[end:]
    start = text.index("    Ort::Value x =")
    end = text.index("  void SubtractGlobalMean", start)
    path.write_text(text[:start] + """    return Ort::Value::CreateTensor(memory_info, features.data(), features.size(),
                                    x_shape.data(), x_shape.size());
  }

""" + text[end:])
    replace("speaker-embedding-extractor-model.h", "  Ort::Value Compute(Ort::Value x) const;",
            "  Ort::Value Compute(Ort::Value x) const;\n  std::vector<Ort::Value> ComputeBatch(std::vector<Ort::Value> inputs) const;")
    replace("speaker-embedding-extractor-model.cc", "  const SpeakerEmbeddingExtractorModelMetaData &GetMetaData() const {", """  std::vector<Ort::Value> ComputeBatch(std::vector<Ort::Value> inputs) const {
    if (inputs.empty()) return {};
    if (std::getenv("SOUND_SPEAKER_NPU_MODELS")) {
      auto accelerated = SoundSpeakerNpu().ComputeBatch(inputs);
      if (std::getenv("SOUND_SPEAKER_NPU_VALIDATE")) {
        for (size_t i = 0; i < inputs.size(); ++i) {
          auto reference = sess_->Run({}, input_names_ptr_.data(), &inputs[i], 1,
                                      output_names_ptr_.data(), output_names_ptr_.size());
          SoundSpeakerNpu().Validate(reference[0], accelerated[i]);
        }
      }
      return accelerated;
    }
    std::vector<Ort::Value> outputs;
    for (auto &input : inputs) outputs.push_back(Compute(std::move(input)));
    return outputs;
  }

  const SpeakerEmbeddingExtractorModelMetaData &GetMetaData() const {""")
    replace("speaker-embedding-extractor-model.cc", "  return impl_->Compute(std::move(x));\n}",
            """  return impl_->Compute(std::move(x));
}

std::vector<Ort::Value> SpeakerEmbeddingExtractorModel::ComputeBatch(
    std::vector<Ort::Value> inputs) const {
  return impl_->ComputeBatch(std::move(inputs));
}""")
    name = "offline-speaker-diarization-pyannote-impl.h"
    path = root / name
    text = path.read_text()
    start = text.index("      SoundSpeakerParallelFor(sample_indexes.size(),")
    end = text.index("      for(int32_t i=0;", start)
    path.write_text(text[:start] + """      constexpr int32_t batch_size = 4;
      const auto count = static_cast<int32_t>(sample_indexes.size());
      SoundSpeakerParallelFor((count + batch_size - 1) / batch_size, [&](int32_t batch) {
        std::vector<std::unique_ptr<OnlineStream>> streams;
        std::vector<OnlineStream *> inputs;
        const int32_t start = batch * batch_size;
        const int32_t end = std::min(count, start + batch_size);
        for (int32_t i = start; i < end; ++i) {
          auto stream = embedding_extractor_.CreateStream();
          for (const auto &range : sample_indexes[i]) {
            const int32_t length = std::min(range.second, n) - range.first;
            if (length > 0) stream->AcceptWaveform(sample_rate, audio + range.first, length);
          }
          stream->InputFinished();
          if (!embedding_extractor_.IsReady(stream.get()))
            throw std::runtime_error("speaker input too short");
          inputs.push_back(stream.get());
          streams.push_back(std::move(stream));
        }
        auto embeddings = embedding_extractor_.ComputeBatch(inputs);
        std::move(embeddings.begin(), embeddings.end(), vectors.begin() + start);
      });
""" + text[end:])
