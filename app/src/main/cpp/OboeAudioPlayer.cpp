#include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <queue>
#include <mutex>
#include <atomic>
#include <condition_variable>
#include <cstring>
#include <time.h>
#include <algorithm>

#define LOG_TAG "OboeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

class OboeAudioPlayer : public oboe::AudioStreamDataCallback,
                        public oboe::AudioStreamErrorCallback
{
private:
    std::shared_ptr<oboe::AudioStream> stream;
    std::mutex queueMutex;
    std::condition_variable bufferCondition;

    // ✅ CORREÇÃO: Armazenar chunks como frames, não samples
    struct AudioChunk
    {
        std::vector<int16_t> data;
        int32_t frameCount; // Número de frames (não samples!)
    };

    std::queue<AudioChunk> audioQueue;
    AudioChunk currentChunk;
    int32_t currentFrameIndex = 0; // ✅ Mudado para frame index

    std::atomic<int32_t> underrunCount{0};
    std::atomic<int32_t> bufferSize{0};
    std::atomic<bool> isPlaying{false};
    std::atomic<bool> isPrebuffering{true};
    std::atomic<int32_t> totalCallbacks{0};
    std::atomic<int32_t> prebufferingCallbacks{0}; // ✅ Contador de callbacks em prebuffering

    // Rastreamento
    std::atomic<int64_t> totalFramesWritten{0};
    std::atomic<int64_t> startTimeMs{0};
    std::atomic<int32_t> chunksConsumed{0};
    std::atomic<int32_t> chunksAdded{0};

    // ✅ Timing simples para debug
    std::atomic<int64_t> lastChunkTimeMs{0};
    std::atomic<float> smoothedChunkInterval{20.0f};

    // ✅ Controle de volume
    std::atomic<float> volumeLevel{1.0f}; // 0.0 a 1.0

    // Configuração
    int32_t configuredSampleRate = 48000;
    int32_t configuredChannelCount = 2;

    // ✅ Buffer settings - BASEADO EM TEMPO, NÃO EM QUANTIDADE DE CHUNKS
    // Porque chunks podem ser de 10ms (1920 bytes) ou 20ms (3840 bytes)
    static constexpr int MAX_QUEUE_SIZE = 500;           // Aumentar de 200 para 500
    static constexpr int32_t TARGET_PREBUFFER_MS = 1000; // Aumentar de 700ms para 1000ms
    static constexpr int32_t MIN_BUFFER_MS = 700;        // Aumentar de 500ms para 700ms
    int32_t estimatedChunkMs = 20;                       // Assumir 20ms inicialmente, será ajustado

public:
    OboeAudioPlayer() = default;
    ~OboeAudioPlayer()
    {
        if (stream)
        {
            stream->close();
        }
    }

    // ✅ CALLBACK PRINCIPAL CORRIGIDO
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream *audioStream,
        void *audioData,
        int32_t numFrames) override
    {
        auto *outputData = static_cast<int16_t *>(audioData);
        int32_t channelCount = audioStream->getChannelCount();
        int32_t framesWritten = 0;

        totalCallbacks++;

        // Rastrear timing - INICIALIZAR APENAS QUANDO COMEÇAR A REPRODUZIR
        if (startTimeMs == 0 && !isPrebuffering)
        {
            startTimeMs = getCurrentTimeMs();
        }

        std::unique_lock<std::mutex> lock(queueMutex);

        // ✅ PREBUFFERING BASEADO EM TEMPO (suporta chunks de 10ms e 20ms)
        if (isPrebuffering.load())
        {
            prebufferingCallbacks++;

            // ⚠️ TIMEOUT: Se ficar mais de 10 segundos prebuffering, desistir
            // (300 callbacks × 10ms callback = 3000ms, mas com margem)
            if (prebufferingCallbacks > 1000 && audioQueue.size() == 0)
            {
                LOGE("💀 TIMEOUT: Prebuffering há %d callbacks sem chunks! Sender pode ter parado.",
                     prebufferingCallbacks.load());
                LOGE("   Continuando com buffer vazio (silêncio)...");
                isPrebuffering = false;
                prebufferingCallbacks = 0;
                // Continuar reproduzindo silêncio em vez de travar
            }

            // Calcular tempo total do buffer atual
            int32_t totalBufferMs = audioQueue.size() * estimatedChunkMs;

            if (totalBufferMs < TARGET_PREBUFFER_MS)
            {
                std::fill_n(outputData, numFrames * channelCount, int16_t(0));

                if (totalCallbacks % 50 == 0)
                {
                    LOGI("⏳ Prebuffering... %zu chunks (~%dms / %dms target) [callbacks: %d]",
                         audioQueue.size(), totalBufferMs, TARGET_PREBUFFER_MS, prebufferingCallbacks.load());
                }
                return oboe::DataCallbackResult::Continue;
            }
            else
            {
                isPrebuffering = false;
                prebufferingCallbacks = 0;
                // ✅ RESETAR CONTADORES PARA CÁLCULO CORRETO DO DRIFT
                startTimeMs = getCurrentTimeMs();
                totalFramesWritten = 0;
                LOGI("✅ Prebuffering completo! %zu chunks (~%dms buffer)",
                     audioQueue.size(), totalBufferMs);
            }
        }

        // ✅ PROCESSAR FRAMES - SIMPLES E DIRETO
        while (framesWritten < numFrames)
        {
            // Se chunk atual vazio, pegar próximo
            if (currentFrameIndex >= currentChunk.frameCount)
            {
                if (!audioQueue.empty())
                {
                    currentChunk = std::move(audioQueue.front());
                    audioQueue.pop();
                    currentFrameIndex = 0;
                    chunksConsumed++;
                    bufferSize = audioQueue.size();
                }
                else
                {
                    // Buffer vazio - silêncio
                    int32_t framesToFill = numFrames - framesWritten;
                    std::fill_n(outputData + (framesWritten * channelCount),
                                framesToFill * channelCount,
                                int16_t(0));

                    underrunCount++;
                    framesWritten = numFrames;

                    // ✅ VOLTAR AO PREBUFFERING de forma mais conservadora
                    int32_t currentBufferMs = audioQueue.size() * estimatedChunkMs;

                    // Se buffer completamente vazio, rebuffer após 10 underruns consecutivos (não 5)
                    if (audioQueue.size() == 0 && underrunCount % 10 == 0)
                    {
                        isPrebuffering = true;
                        prebufferingCallbacks = 0;
                        LOGW("⚠️ Buffer vazio! Prebuffering... (UR: %d)", underrunCount.load());
                    }
                    // Se buffer baixo mas não vazio, dar muito mais chances antes de rebuffer
                    else if (currentBufferMs < MIN_BUFFER_MS && underrunCount % 50 == 0)
                    {
                        isPrebuffering = true;
                        prebufferingCallbacks = 0;
                        LOGW("⚠️ Buffer crítico! %dms < %dms. Prebuffering... (UR: %d)",
                             currentBufferMs, MIN_BUFFER_MS, underrunCount.load());
                    }
                    else if (underrunCount % 100 == 0)
                    {
                        LOGW("⚠️ Underrun #%d | Queue: %zu chunks (~%dms)",
                             underrunCount.load(), audioQueue.size(), currentBufferMs);
                    }

                    break;
                }
            }

            // ✅ COPIAR FRAMES SIMPLES - SEM MANIPULAÇÃO DE TAXA
            int32_t framesToCopy = std::min(
                numFrames - framesWritten,
                currentChunk.frameCount - currentFrameIndex);

            int32_t samplesToCopy = framesToCopy * channelCount;
            int32_t sourceOffset = currentFrameIndex * channelCount;
            int32_t destOffset = framesWritten * channelCount;

            // Verificar limites rigorosamente
            if (sourceOffset >= 0 &&
                samplesToCopy > 0 &&
                sourceOffset + samplesToCopy <= (int32_t)currentChunk.data.size() &&
                destOffset + samplesToCopy <= numFrames * channelCount)
            {
                // ✅ COPIAR E APLICAR VOLUME
                float currentVolume = volumeLevel.load();
                const int16_t *sourceData = currentChunk.data.data() + sourceOffset;
                int16_t *destData = outputData + destOffset;

                if (currentVolume >= 0.99f)
                {
                    // Volume máximo - copiar direto (otimização)
                    std::copy_n(sourceData, samplesToCopy, destData);
                }
                else if (currentVolume <= 0.01f)
                {
                    // Volume mínimo - silêncio
                    std::fill_n(destData, samplesToCopy, int16_t(0));
                }
                else
                {
                    // Volume intermediário - aplicar ganho
                    for (int32_t i = 0; i < samplesToCopy; i++)
                    {
                        destData[i] = static_cast<int16_t>(sourceData[i] * currentVolume);
                    }
                }

                currentFrameIndex += framesToCopy;
                framesWritten += framesToCopy;
            }
            else
            {
                LOGE("❌ Bounds error! src=%d, samples=%d, chunkSize=%zu, dest=%d, maxDest=%d",
                     sourceOffset, samplesToCopy, currentChunk.data.size(),
                     destOffset, numFrames * channelCount);
                currentFrameIndex = currentChunk.frameCount; // Forçar próximo chunk
            }
        }

        // ✅ CONTAR FRAMES APENAS QUANDO NÃO ESTÁ EM PREBUFFERING
        if (!isPrebuffering.load())
        {
            totalFramesWritten += numFrames;
        }

        // ✅ Debug simples a cada 100 callbacks
        if (totalCallbacks % 100 == 0 && startTimeMs > 0 && !isPrebuffering.load())
        {
            int64_t elapsedMs = getCurrentTimeMs() - startTimeMs;
            if (elapsedMs > 1000)
            {
                float actualRate = (totalFramesWritten.load() * 1000.0f) / elapsedMs;
                float expectedRate = audioStream->getSampleRate();
                float driftPercent = ((actualRate - expectedRate) / expectedRate) * 100.0f;
                float currentInterval = smoothedChunkInterval.load();

                LOGI("📊 Playback: %.0f/%.0f Hz (drift: %.1f%%) | Interval: %.1fms | Queue: %zu | UR: %d",
                     actualRate, expectedRate, driftPercent, currentInterval,
                     audioQueue.size(), underrunCount.load());
            }
        }

        return oboe::DataCallbackResult::Continue;
    }
    void onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) override
    {
        LOGE("Stream error: %s", oboe::convertToText(error));

        if (isPlaying)
        {
            LOGI("Tentando recriar stream...");

            // ✅ USAR MESMA CONFIGURAÇÃO
            oboe::AudioStreamBuilder builder;
            configureBuilder(builder, configuredSampleRate, configuredChannelCount);

            oboe::Result result = builder.openStream(stream);
            if (result == oboe::Result::OK)
            {
                stream->start();
                LOGI("✅ Stream recriado");
            }
            else
            {
                LOGE("❌ Falha ao recriar: %s", oboe::convertToText(result));
            }
        }
    }

    // ✅ ADICIONAR DADOS CORRIGIDO
    bool addAudioData(JNIEnv *env, jbyteArray audioData, jint length)
    {
        if (!stream || !isPlaying)
            return false;

        // Validar tamanho
        if (length % (2 * configuredChannelCount) != 0)
        {
            LOGW("⚠️ Tamanho inválido: %d bytes (não é múltiplo de %d)",
                 length, 2 * configuredChannelCount);
            return false;
        }

        jbyte *bytes = env->GetByteArrayElements(audioData, nullptr);

        // ✅ CALCULAR FRAMES CORRETAMENTE
        int32_t numSamples = length / 2;                         // 2 bytes por sample
        int32_t numFrames = numSamples / configuredChannelCount; // ✅ IMPORTANTE!

        AudioChunk chunk;
        chunk.data.resize(numSamples);
        chunk.frameCount = numFrames;

        // Copiar dados como int16_t (Little Endian)
        memcpy(chunk.data.data(), bytes, length);

        env->ReleaseByteArrayElements(audioData, bytes, JNI_ABORT);

        // Debug ocasional
        if (chunksAdded % 100 == 0)
        {
            LOGI("� Chunk %d: %d frames (%d samples, %d bytes)",
                 chunksAdded.load(), numFrames, numSamples, length);

            // Verificar primeiros samples
            if (chunk.data.size() >= 4)
            {
                LOGI("   Samples: [%d, %d, %d, %d]",
                     chunk.data[0], chunk.data[1], chunk.data[2], chunk.data[3]);
            }
        }

        // ✅ TIMING E AUTO-DETECÇÃO DO TAMANHO DO CHUNK
        int64_t currentTime = getCurrentTimeMs();

        // Estimar duração do chunk baseado no número de frames
        // 48000 Hz: 480 frames = 10ms, 960 frames = 20ms
        int32_t chunkDurationMs = (numFrames * 1000) / configuredSampleRate;

        // Atualizar estimativa (média móvel)
        if (chunksAdded > 0 && chunkDurationMs > 5 && chunkDurationMs < 50)
        {
            int32_t current = estimatedChunkMs;
            estimatedChunkMs = (current * 9 + chunkDurationMs) / 10; // Média móvel suave

            if (chunksAdded % 100 == 0)
            {
                LOGI("📦 Chunk detectado: %d frames = %dms (média: %dms)",
                     numFrames, chunkDurationMs, estimatedChunkMs);
            }
        }

        if (lastChunkTimeMs > 0)
        {
            int32_t interval = (int32_t)(currentTime - lastChunkTimeMs);
            if (interval > 0 && interval < 1000)
            {
                // Suavização simples
                float current = smoothedChunkInterval.load();
                float newInterval = (current * 0.9f) + (interval * 0.1f);
                smoothedChunkInterval = newInterval;
            }
        }
        lastChunkTimeMs = currentTime;

        // Adicionar à fila
        {
            std::unique_lock<std::mutex> lock(queueMutex);

            // Limitar tamanho da fila
            while (audioQueue.size() >= MAX_QUEUE_SIZE)
            {
                audioQueue.pop();
                LOGW("⚠️ Fila cheia, descartando chunk antigo");
            }

            audioQueue.push(std::move(chunk));
            chunksAdded++;
            bufferSize = audioQueue.size();
        }

        bufferCondition.notify_one();
        return true;
    }

    // ✅ CRIAR STREAM MELHORADO
    bool createStream(int32_t sampleRate, int32_t channelCount)
    {
        configuredSampleRate = sampleRate;
        configuredChannelCount = channelCount;

        oboe::AudioStreamBuilder builder;

        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::I16)
            ->setSampleRate(sampleRate)
            ->setChannelCount(channelCount)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);

        oboe::Result result = builder.openStream(stream);

        if (result != oboe::Result::OK)
        {
            LOGE("❌ Failed to create stream: %s", oboe::convertToText(result));
            return false;
        }

        // Verificar configuração real
        int32_t actualSR = stream->getSampleRate();
        int32_t actualCC = stream->getChannelCount();
        oboe::AudioFormat actualFormat = stream->getFormat();
        int32_t framesPerBurst = stream->getFramesPerBurst();

        LOGI("✅ Stream criado:");
        LOGI("   Config: %dHz, %d ch, I16", sampleRate, channelCount);
        LOGI("   Actual: %dHz, %d ch, %s", actualSR, actualCC,
             oboe::convertToText(actualFormat));
        LOGI("   Frames/burst: %d", framesPerBurst);
        LOGI("   Buffer capacity: %d frames", stream->getBufferCapacityInFrames());

        // Alertas
        if (actualSR != sampleRate)
        {
            LOGW("⚠️ Sample rate: requested %d, got %d", sampleRate, actualSR);
        }
        if (actualCC != channelCount)
        {
            LOGW("⚠️ Channels: requested %d, got %d", channelCount, actualCC);
        }

        // ✅ Configurar buffer MÁXIMO para reduzir glitches
        // Usar 90% da capacidade para dar mais margem contra jitter
        int32_t targetBufferSize = (stream->getBufferCapacityInFrames() * 9) / 10;
        stream->setBufferSizeInFrames(targetBufferSize);
        LOGI("   Buffer size configurado: %d frames (%.1fms)",
             targetBufferSize, (targetBufferSize * 1000.0f) / actualSR);

        // Reset
        totalFramesWritten = 0;
        chunksConsumed = 0;
        chunksAdded = 0;
        startTimeMs = 0;
        underrunCount = 0;
        currentFrameIndex = 0;
        currentChunk.frameCount = 0;
        currentChunk.data.clear();

        // ✅ Reset timing e estimativa de chunk
        lastChunkTimeMs = 0;
        smoothedChunkInterval = 20.0f;
        estimatedChunkMs = 20; // Assumir 20ms inicialmente, será ajustado automaticamente

        return true;
    }
    // ✅ CONFIGURAÇÃO CONSISTENTE
    void configureBuilder(oboe::AudioStreamBuilder &builder, int32_t sampleRate, int32_t channelCount)
    {
        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::I16) // ✅ CORRIGIDO: ERA Float, AGORA É I16!
            ->setSampleRate(sampleRate)
            ->setChannelCount(channelCount)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);

        // ✅ NÃO FIXAR framesPerCallback - deixar Oboe decidir
        // builder.setFramesPerCallback(960); // REMOVIDO
    }

    void start()
    {
        isPlaying = true;
        isPrebuffering = true;
        if (stream)
        {
            oboe::Result result = stream->start();
            LOGI("▶️ Stream start: %s", oboe::convertToText(result));
        }
    }

    void pause()
    {
        if (stream)
        {
            stream->pause();
            LOGI("⏸️ Stream pausado");
        }
    }

    void stop()
    {
        isPlaying = false;
        isPrebuffering = true;
        if (stream)
        {
            stream->stop();
            LOGI("⏹️ Stream parado");
        }
        clearQueue();
    }

    void clearQueue()
    {
        std::unique_lock<std::mutex> lock(queueMutex);
        std::queue<AudioChunk> empty;
        std::swap(audioQueue, empty);
        currentChunk.data.clear();
        currentChunk.frameCount = 0;
        currentFrameIndex = 0;
        bufferSize = 0;
        LOGI("🗑️ Fila limpa");
    }

    // Getters
    int32_t getBufferSize() const { return bufferSize.load(); }
    int32_t getUnderrunCount() const { return underrunCount.load(); }

    int32_t getLatencyMillis()
    {
        if (!stream)
            return 0;

        auto result = stream->calculateLatencyMillis();
        return result ? result.value() : 0;
    }

    // 🔧 Volume control
    bool setVolume(float volume)
    {
        // Clamp volume between 0.0 and 1.0
        volumeLevel.store(std::clamp(volume, 0.0f, 1.0f));
        return true;
    }

    // 🔧 NOVO: Método helper para tempo
    int64_t getCurrentTimeMs()
    {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return (ts.tv_sec * 1000LL) + (ts.tv_nsec / 1000000LL);
    }
};

// JNI Interface
extern "C"
{
    // Global instance
    static OboeAudioPlayer *g_player = nullptr;

    JNIEXPORT jboolean JNICALL
    Java_com_shirou_shibasync_OboeAudioPlayer_nativeCreateStream(
        JNIEnv *env, jobject thiz, jint sampleRate, jint channelCount)
    {
        if (g_player == nullptr)
        {
            g_player = new OboeAudioPlayer();
        }
        return g_player->createStream(sampleRate, channelCount) ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT jboolean JNICALL
    Java_com_shirou_shibasync_OboeAudioPlayer_nativeAddData(
        JNIEnv *env, jobject thiz, jbyteArray audioData, jint length)
    {
        if (g_player == nullptr)
            return JNI_FALSE;
        return g_player->addAudioData(env, audioData, length) ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT void JNICALL
    Java_com_shirou_shibasync_OboeAudioPlayer_nativeStart(
        JNIEnv *env, jobject thiz)
    {
        if (g_player != nullptr)
        {
            g_player->start();
        }
    }

    JNIEXPORT void JNICALL
    Java_com_shirou_shibasync_OboeAudioPlayer_nativePause(
        JNIEnv *env, jobject thiz)
    {
        if (g_player != nullptr)
        {
            g_player->pause();
        }
    }

    JNIEXPORT void JNICALL
    Java_com_shirou_shibasync_OboeAudioPlayer_nativeStop(
        JNIEnv *env, jobject thiz)
    {
        if (g_player != nullptr)
        {
            g_player->stop();
        }
    }

    JNIEXPORT void JNICALL
    Java_com_shirou_shibasync_OboeAudioPlayer_nativeClearQueue(
        JNIEnv *env, jobject thiz)
    {
        if (g_player != nullptr)
        {
            g_player->clearQueue();
        }
    }

    JNIEXPORT jint JNICALL
    Java_com_shirou_shibasync_OboeAudioPlayer_nativeGetBufferSize(
        JNIEnv *env, jobject thiz)
    {
        if (g_player == nullptr)
            return 0;
        return g_player->getBufferSize();
    }

    JNIEXPORT jint JNICALL
    Java_com_shirou_shibasync_OboeAudioPlayer_nativeGetUnderrunCount(
        JNIEnv *env, jobject thiz)
    {
        if (g_player == nullptr)
            return 0;
        return g_player->getUnderrunCount();
    }

    JNIEXPORT jint JNICALL
    Java_com_shirou_shibasync_OboeAudioPlayer_nativeGetLatency(
        JNIEnv *env, jobject thiz)
    {
        if (g_player == nullptr)
            return 0;
        return g_player->getLatencyMillis();
    }

    JNIEXPORT jboolean JNICALL
    Java_com_shirou_shibasync_OboeAudioPlayer_nativeSetVolume(
        JNIEnv *env, jobject thiz, jfloat volume)
    {
        if (g_player == nullptr)
            return JNI_FALSE;
        return g_player->setVolume(volume) ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT void JNICALL
    Java_com_shirou_shibasync_OboeAudioPlayer_nativeDestroy(
        JNIEnv *env, jobject thiz)
    {
        if (g_player != nullptr)
        {
            delete g_player;
            g_player = nullptr;
        }
    }
}