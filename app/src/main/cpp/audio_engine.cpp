#include <android/log.h>
#include <oboe/Oboe.h>

#include <atomic>
#include <memory>

#define LOG_TAG "MastarAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace mastar {

/**
 * Phase-3 low-latency audio output via Google Oboe.
 *
 * Current scope: owns the output stream and renders silence — the mixer graph
 * (track mixing, fades, Sonic pitch effects) plugs into onAudioReady later.
 * Preview audio currently flows through ExoPlayer; this stream is for the
 * effects pipeline where ExoPlayer's latency is too high.
 */
class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    static AudioEngine &instance() {
        static AudioEngine engine;
        return engine;
    }

    bool start() {
        oboe::AudioStreamBuilder builder;
        oboe::Result result = builder.setDirection(oboe::Direction::Output)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(oboe::SharingMode::Exclusive)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(oboe::ChannelCount::Stereo)
                ->setDataCallback(this)
                ->openStream(stream_);
        if (result != oboe::Result::OK) {
            LOGE("Failed to open Oboe stream: %s", oboe::convertToText(result));
            return false;
        }
        stream_->requestStart();
        LOGI("Oboe stream started: sampleRate=%d framesPerBurst=%d",
             stream_->getSampleRate(), stream_->getFramesPerBurst());
        return true;
    }

    void stop() {
        if (stream_) {
            stream_->stop();
            stream_->close();
            stream_.reset();
        }
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData,
                                          int32_t numFrames) override {
        auto *out = static_cast<float *>(audioData);
        const int32_t samples = numFrames * stream->getChannelCount();
        // Mixer graph TBD: output silence for now.
        for (int32_t i = 0; i < samples; ++i) out[i] = 0.0f;
        return oboe::DataCallbackResult::Continue;
    }

private:
    AudioEngine() = default;
    std::shared_ptr<oboe::AudioStream> stream_;
};

bool audioEngineStart() { return AudioEngine::instance().start(); }
void audioEngineStop() { AudioEngine::instance().stop(); }

} // namespace mastar
