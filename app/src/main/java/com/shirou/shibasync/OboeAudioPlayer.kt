package com.shirou.shibasync

class OboeAudioPlayer {
    companion object {
        init {
            System.loadLibrary("oboe-audio")
        }
    }
    
    // Native methods
    external fun nativeCreateStream(sampleRate: Int, channelCount: Int): Boolean
    external fun nativeAddData(audioData: ByteArray, length: Int): Boolean
    external fun nativeStart()
    external fun nativePause()
    external fun nativeStop()
    external fun nativeClearQueue()
    external fun nativeGetBufferSize(): Int
    external fun nativeGetUnderrunCount(): Int
    external fun nativeGetLatency(): Int
    external fun nativeSetVolume(volume: Float): Boolean
    external fun nativeDestroy()
    
    fun createStream(sampleRate: Int, channelCount: Int): Boolean {
        return nativeCreateStream(sampleRate, channelCount)
    }
    
    fun addData(audioData: ByteArray): Boolean {
        return nativeAddData(audioData, audioData.size)
    }
    
    fun start() = nativeStart()
    fun pause() = nativePause()
    fun stop() = nativeStop()
    fun clearQueue() = nativeClearQueue()
    fun getBufferSize() = nativeGetBufferSize()
    fun getUnderrunCount() = nativeGetUnderrunCount()
    fun getLatencyMillis() = nativeGetLatency()
    
    // ✅ NEW: Volume control (0.0 to 1.0)
    fun setVolume(volume: Float): Boolean {
        val clampedVolume = volume.coerceIn(0.0f, 1.0f)
        return nativeSetVolume(clampedVolume)
    }
    
    fun destroy() {
        nativeDestroy()
    }
    
    protected fun finalize() {
        destroy()
    }
}