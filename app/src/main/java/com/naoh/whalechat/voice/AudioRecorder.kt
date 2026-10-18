package com.naoh.whalechat.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本机录音。
 *
 * 固定 16 kHz / 单声道 / PCM 16bit：这是讯飞和各家云端 ASR 共同要求的格式，
 * 也是采样率里最小的那一档 —— 录满 [MAX_SECONDS] 也只有 1.9MB，
 * 手表上边录边攒完全扛得住（对内存的占用也就 2MB 量级）。
 *
 * 为什么要自己录：系统那套 `SpeechRecognizer` 只把音频交给设备上装的识别服务，
 * 我们碰不到数据，也就没法把它送给别的服务。要接自建识别，只能自己拿麦克风。
 *
 * 两种用法都支持：
 *  - 不传 [record] 的 onFrame：录完拿一整段 WAV，喂批量接口；
 *  - 传了 onFrame：边录边按 1280 字节外抛，喂讯飞那种流式接口。
 */
class AudioRecorder {

    private val stopRequested = AtomicBoolean(false)
    private var active: AudioRecord? = null

    /**
     * 开录，直到 [requestStop] 被调用、或者录满 [MAX_SECONDS]。
     *
     * @param onFrame 可选。给了就按 [FRAME_BYTES] 切片实时回调，用于流式识别。
     *   **回调发生在 IO 线程**，里面绝不能碰 Compose 状态，只能往通道里塞。
     * @return 带 WAV 头的完整字节
     */
    @SuppressLint("MissingPermission")
    suspend fun record(onFrame: ((ByteArray) -> Unit)? = null): ByteArray =
        withContext(Dispatchers.IO) {
            check(active == null) { "正在录音" }
            stopRequested.set(false)

            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            if (minBuffer <= 0) throw IllegalStateException("这个设备不支持 16kHz 单声道录音")
            // 缓冲区抬到 200ms 以上：低于这个值在手表上很容易丢帧，识别会漏字
            val bufferBytes = maxOf(minBuffer, SAMPLE_RATE * BYTES_PER_SAMPLE / 5)

            val instance = open(bufferBytes)
            active = instance

            val samples = ShortArray(bufferBytes / BYTES_PER_SAMPLE)
            val pcm = ByteArrayOutputStream(MAX_PCM_BYTES)

            // 流式切片用的攒货缓冲：读回来的块大小是驱动定的，和 1280 对不齐，
            // 必须自己攒够一片再发，否则服务端会按非法帧序拒掉
            val frame = ByteArray(FRAME_BYTES)
            var frameFill = 0

            try {
                instance.startRecording()
                while (!stopRequested.get() && pcm.size() < MAX_PCM_BYTES) {
                    val read = instance.read(samples, 0, samples.size)
                    if (read <= 0) continue

                    val chunk = ByteArray(read * BYTES_PER_SAMPLE)
                    for (i in 0 until read) {
                        val value = samples[i].toInt()
                        // 小端序：低字节在前，WAV 就是这么定的
                        chunk[i * 2] = (value and 0xFF).toByte()
                        chunk[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
                    }

                    if (onFrame != null) {
                        var offset = 0
                        while (offset < chunk.size) {
                            val take = minOf(FRAME_BYTES - frameFill, chunk.size - offset)
                            System.arraycopy(chunk, offset, frame, frameFill, take)
                            frameFill += take
                            offset += take
                            if (frameFill == FRAME_BYTES) {
                                onFrame(frame.copyOf())
                                frameFill = 0
                            }
                        }
                    }

                    // ⚠️ 1.984：电平条已删（用户原话「干脆把声音起伏指示条删了，
                    // 不仅有延迟还卡」）。原来这里每 ~80ms 把 dbfs 值推给 UI，
                    // 每次都触发状态卡的重组+重绘 —— 在真机上是持续掉帧源，
                    // 而用户要看的是 partial 的「边说边出字」，不是音量。**别再加回来。**
                    // 1.992：电平条当年的数据源 `sum`（平方和，喂 dbfs() 的）也删了
                    // —— 当时删了常量和 dbfs() 却漏了它，这里曾有 96 万次/分钟的
                    // 无消费 double 乘加。别跟着 dbfs() 一起加回来。
                    pcm.write(chunk)
                }
            } finally {
                runCatching { instance.stop() }
                instance.release()
                active = null
            }

            // 尾巴那点不足一片的也要交出去，否则最后一个字会被吞掉
            if (onFrame != null && frameFill > 0) {
                onFrame(frame.copyOf(frameFill))
            }

            val data = pcm.toByteArray()
            // 短于 0.4 秒基本都是误触，送去识别只会白花一次请求和一次等待
            if (data.size < SAMPLE_RATE * BYTES_PER_SAMPLE * 4 / 10) {
                throw IllegalStateException("说得太短了")
            }
            wav(data)
        }

    /** 让录音循环收尾。可以重复调用，也可以在没录音时调用。 */
    fun requestStop() {
        stopRequested.set(true)
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** 单次录音上限。讯飞那边整个会话也是 60 秒封顶，正好对齐。 */
        const val MAX_SECONDS = 60

        /**
         * 流式接口要求的分片大小：1280 字节 = 16000Hz × 2 字节 × 40ms。
         * 讯飞文档写的是「每次发送 1280 字节、间隔 40ms」。
         */
        const val FRAME_BYTES = 1280

        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
        private const val MAX_PCM_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * MAX_SECONDS

        // ⚠️ 1.984：电平条（及其全部映射常量 LEVEL_INTERVAL_MS / LEVEL_JUMP /
        // NOISE_FLOOR_DB / LOUD_DB / SILENT_FLOOR_FRACTION 与 dbfs()）已整个删除。
        // 起因与经过见 record() 循环里那段注释。

        /**
         * 优先 VOICE_RECOGNITION 音源：系统会按「这是给识别用的」来走音频链，
         * 效果比 MIC 好（也更不容易把音乐/振动当成语音）。
         * 少数设备没有这个源，退回 MIC —— 顺序尝试，不是二选一。
         */
        private fun open(bufferBytes: Int): AudioRecord {
            val sources = intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
            )
            for (source in sources) {
                val instance = AudioRecord(source, SAMPLE_RATE, CHANNEL, ENCODING, bufferBytes)
                if (instance.state == AudioRecord.STATE_INITIALIZED) return instance
                instance.release()
            }
            throw IllegalStateException("麦克风不可用，可能被别的应用占着")
        }

        /**
         * 补 44 字节标准 WAV 头。
         *
         * 批量接口基本靠文件名/魔数判格式，裸 PCM 会被拒或者识别成噪声。
         * 自己拼头只要 44 字节，不值得为它引依赖。
         */
        private fun wav(pcm: ByteArray): ByteArray {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + pcm.size)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)                                  // fmt 块长度
            header.putShort(1.toShort())                       // 1 = PCM
            header.putShort(1.toShort())                       // 单声道
            header.putInt(SAMPLE_RATE)
            header.putInt(SAMPLE_RATE * BYTES_PER_SAMPLE)      // byte rate
            header.putShort(BYTES_PER_SAMPLE.toShort())        // block align
            header.putShort(16.toShort())                      // 位深
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(pcm.size)
            return header.array() + pcm
        }
    }
}
