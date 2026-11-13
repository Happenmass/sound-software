package dev.soundsoftware.app.inference

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import dev.soundsoftware.core.asr.AsrException
import dev.soundsoftware.core.asr.ErrorCode
import dev.soundsoftware.core.asr.LocalAsrRuntime
import dev.soundsoftware.core.asr.LocalRecognition
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class SherpaModel { QWEN3_ASR, SENSE_VOICE, SENSE_VOICE_QNN }

data class QnnRuntimeFiles(
    val backendLibrary: File,
    val systemLibrary: File,
    val dspLibraryDirectory: File,
    val contextCache: File,
)

data class SherpaRuntimeConfig(
    val modelDirectory: File,
    val model: SherpaModel,
    val modelRevision: String,
    val numThreads: Int = 2,
    val maxChunkSeconds: Int = 20,
    val qnn: QnnRuntimeFiles? = null,
    /** SenseVoice language code ("zh", "en", …); null = automatic. */
    val language: String? = null,
    /** Qwen3-ASR context terms. */
    val hotwords: List<String> = emptyList(),
) {
    init {
        require(modelRevision.isNotBlank() && numThreads in 1..8 && maxChunkSeconds in 1..30)
        require((model == SherpaModel.SENSE_VOICE_QNN) == (qnn != null))
    }
}

/** Calls the pinned sherpa-onnx 2.0.1 AAR directly. Never substitutes fabricated transcripts. */
class SherpaAsrRuntime(private val settings: SherpaRuntimeConfig) : LocalAsrRuntime {
    private var recognizer: OfflineRecognizer? = null
    override val modelId = "${settings.model.name}:${settings.modelRevision}"
    override val runtimeName = "sherpa-onnx-2.0.1"
    override val backend = if (settings.model == SherpaModel.SENSE_VOICE_QNN) "qnn-htp-requested" else "cpu"
    override val maxSamples = settings.maxChunkSeconds * 16000

    override suspend fun prepare() {
        currentCoroutineContext().ensureActive()
        if (recognizer != null) return
        if (settings.model == SherpaModel.SENSE_VOICE_QNN) {
            throw AsrException(ErrorCode.BACKEND_UNAVAILABLE, "当前固定 AAR 未验证 QNN 编译支持；需接入专用 QNN 构建与匹配芯片模型后启用")
        }
        fun modelFile(name: String): String {
            val file = File(settings.modelDirectory, name)
            if (!file.isFile || file.length() == 0L) throw AsrException(ErrorCode.MODEL_NOT_READY, "模型未就绪，缺少 $name")
            return file.absolutePath
        }
        val config = when (settings.model) {
            SherpaModel.QWEN3_ASR -> {
                val frontend = modelFile("conv_frontend.onnx")
                val encoder = modelFile("encoder.int8.onnx")
                val decoder = modelFile("decoder.int8.onnx")
                modelFile("tokenizer/vocab.json"); modelFile("tokenizer/merges.txt")
                OfflineModelConfig(
                    qwen3Asr = OfflineQwen3AsrModelConfig(
                        convFrontend = frontend, encoder = encoder, decoder = decoder,
                        tokenizer = File(settings.modelDirectory, "tokenizer").absolutePath,
                        maxNewTokens = 512, maxTotalLen = 1024,
                        hotwords = settings.hotwords.joinToString(","),
                    ),
                    numThreads = settings.numThreads, provider = "cpu",
                )
            }
            SherpaModel.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(model = modelFile("model.int8.onnx"), language = settings.language ?: "auto", useInverseTextNormalization = false),
                tokens = modelFile("tokens.txt"), numThreads = settings.numThreads, provider = "cpu",
            )
            SherpaModel.SENSE_VOICE_QNN -> throw AsrException(ErrorCode.BACKEND_UNAVAILABLE, "QNN 专用运行库尚未接入")
        }
        try {
            recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000,
                    featureDim = if (settings.model == SherpaModel.QWEN3_ASR) 128 else 80), modelConfig = config,
            ))
        } catch (e: LinkageError) {
            throw AsrException(ErrorCode.BACKEND_UNAVAILABLE, "当前安装包或设备无法加载 sherpa 原生运行库")
        } catch (e: OutOfMemoryError) {
            throw AsrException(ErrorCode.OUT_OF_MEMORY, "加载语音模型时内存不足")
        } catch (e: IllegalArgumentException) {
            throw AsrException(ErrorCode.BACKEND_UNAVAILABLE, "模型配置或当前推理后端初始化失败")
        }
        currentCoroutineContext().ensureActive()
    }

    override suspend fun decode(samples: FloatArray, sampleRate: Int): LocalRecognition {
        require(sampleRate == 16000 && samples.isNotEmpty() && samples.size <= maxSamples)
        currentCoroutineContext().ensureActive()
        val engine = recognizer ?: throw AsrException(ErrorCode.MODEL_NOT_READY, "请先加载语音模型")
        val stream = engine.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            engine.decode(stream)
            currentCoroutineContext().ensureActive()
            val result = engine.getResult(stream)
            // Limit reached is not a complete transcription: never silently label truncation as success.
            if (settings.model == SherpaModel.QWEN3_ASR && result.tokens.size >= 512) {
                throw AsrException(ErrorCode.INVALID_RESPONSE, "识别达到输出长度上限，请减小切段后重试")
            }
            return LocalRecognition(result.text)
        } finally { stream.release() }
    }

    override fun close() { recognizer?.release(); recognizer = null }
}
