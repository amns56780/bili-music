package com.bilimusic.desktop.player

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 真实音频频谱（WASAPI loopback → FFT）。
 *
 * VLCJ 4.8 不对外暴露 PCM 回调，所以拿不到播放中的采样。
 * 这里走 Windows 的 WASAPI 环回采集：抓默认输出设备的混音（别人听什么就抓什么），
 * 拿到 float32 PCM 后做 1024 点 FFT，压成 [BANDS] 个对数频段，给沉浸模式的律动条用。
 *
 * 采集失败（没有声卡 / COM 拒绝 / 非 Windows）时安静地降级：bands 一直是 0，
 * 律动条回到"没有数据"的状态，不影响播放。
 */
object AudioSpectrum {

    const val BANDS = 32
    private const val FFT_SIZE = 1024
    private const val SAMPLE_RATE_FALLBACK = 48000

    private val _bands = MutableStateFlow(FloatArray(BANDS))
    /** 32 个频段能量，0..1，从低频到高频 */
    val bands: StateFlow<FloatArray> = _bands.asStateFlow()

    @Volatile
    private var running = false
    private var worker: Thread? = null

    private const val CLSCTX_ALL = 23
    private const val AUDCLNT_SHAREMODE_SHARED = 0
    private const val AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000
    private const val AUDCLNT_BUFFERFLAGS_SILENT = 0x2

    private val CLSID_MMDeviceEnumerator = GUID.fromString("{BCDE0395-E52F-467C-8E3D-C4579291692E}")
    private val IID_IMMDeviceEnumerator = GUID.fromString("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
    private val IID_IAudioClient = GUID.fromString("{1CB9AD4C-DBFA-4c32-B178-C2F568A703B2}")
    private val IID_IAudioCaptureClient = GUID.fromString("{C8ADBD64-E71E-48a0-A4DE-185C395CD317}")

    /** 开始采集（重复调用安全） */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        _bands.value = FloatArray(BANDS)
        val t = Thread({ runCapture() }, "bilimusic-spectrum")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY + 2
        worker = t
        t.start()
    }

    @Synchronized
    fun stop() {
        running = false
        worker = null
    }

    // ------------------------------------------------------------------ 采集

    private fun runCapture() {
        var audioClient: Pointer? = null
        try {
            Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED)
            val enumerator = PointerByReference()
            if (Ole32.INSTANCE.CoCreateInstance(
                    CLSID_MMDeviceEnumerator, null, CLSCTX_ALL, IID_IMMDeviceEnumerator, enumerator,
                ) != WinNT.S_OK
            ) return
            val devEnum = enumerator.value ?: return

            // GetDefaultAudioEndpoint(eRender=0, eConsole=0)
            val deviceRef = PointerByReference()
            if (comCall(devEnum, 4, 0, 0, deviceRef) != 0) return
            val device = deviceRef.value ?: return

            // Activate(IID_IAudioClient)
            val clientRef = PointerByReference()
            if (comCall(device, 3, IID_IAudioClient, CLSCTX_ALL, null, clientRef) != 0) return
            val client = clientRef.value ?: return
            audioClient = client

            // GetMixFormat
            val formatRef = PointerByReference()
            if (comCall(client, 8, formatRef) != 0) return
            val format = formatRef.value ?: return
            val channels = format.getShort(2).toInt()
            val sampleRate = format.getInt(4)
            val bits = format.getShort(14).toInt()
            if (channels <= 0) return

            // Initialize(shared, loopback, 1s buffer, 0, format, null)
            val hr = comCall(
                client, 3, AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK,
                10_000_000L, 0L, format, null,
            )
            if (hr != 0) return

            // GetService(IAudioCaptureClient)
            val captureRef = PointerByReference()
            if (comCall(client, 14, IID_IAudioCaptureClient, captureRef) != 0) return
            val capture = captureRef.value ?: return

            if (comCall(client, 10) != 0) return // Start

            val isFloat = bits == 32
            val bytesPerSample = if (isFloat) 4 else 2
            val frameBytes = bytesPerSample * channels
            val ring = FloatArray(FFT_SIZE * 8)
            var ringPos = 0
            var filled = 0
            val fftReal = FloatArray(FFT_SIZE)
            val fftImag = FloatArray(FFT_SIZE)
            val window = FloatArray(FFT_SIZE) { i ->
                (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))).toFloat()  // Hann
            }
            val smooth = FloatArray(BANDS)
            var lastAnalyze = 0L

            while (running) {
                val packetRef = IntByReference()
                if (comCall(capture, 5, packetRef) != 0) break
                var packet = packetRef.value
                while (packet > 0) {
                    val dataRef = PointerByReference()
                    val framesRef = IntByReference()
                    val flagsRef = IntByReference()
                    val posRef = com.sun.jna.ptr.LongByReference()
                    val qpcRef = com.sun.jna.ptr.LongByReference()
                    if (comCall(capture, 3, dataRef, framesRef, flagsRef, posRef, qpcRef) != 0) {
                        packet = 0
                        break
                    }
                    val data = dataRef.value
                    val frames = framesRef.value
                    val silent = (flagsRef.value and AUDCLNT_BUFFERFLAGS_SILENT) != 0
                    if (!silent && data != null && frames > 0) {
                        val bytes = frames * frameBytes
                        if (isFloat) {
                            val buf = ByteArray(bytes)
                            data.read(0, buf, 0, bytes)
                            var off = 0
                            for (f in 0 until frames) {
                                var sum = 0f
                                for (c in 0 until channels) {
                                    val b0 = buf[off].toInt() and 0xFF
                                    val b1 = buf[off + 1].toInt() and 0xFF
                                    val b2 = buf[off + 2].toInt() and 0xFF
                                    val b3 = buf[off + 3].toInt() and 0xFF
                                    val bits32 = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                                    sum += Float.fromBits(bits32)
                                    off += 4
                                }
                                ring[ringPos] = sum / channels
                                ringPos = (ringPos + 1) % ring.size
                                if (filled < ring.size) filled++
                            }
                        }
                    }
                    comCall(capture, 4, frames) // ReleaseBuffer
                    if (comCall(capture, 5, packetRef) != 0) break
                    packet = packetRef.value
                }

                val now = System.currentTimeMillis()
                if (filled >= FFT_SIZE && now - lastAnalyze >= 33) {
                    lastAnalyze = now
                    // 取最近 FFT_SIZE 个采样
                    val start = (ringPos - FFT_SIZE + ring.size) % ring.size
                    for (i in 0 until FFT_SIZE) {
                        val v = ring[(start + i) % ring.size]
                        fftReal[i] = v * window[i]
                        fftImag[i] = 0f
                    }
                    fft(fftReal, fftImag)
                    val effectiveRate = if (sampleRate > 0) sampleRate.toFloat() else SAMPLE_RATE_FALLBACK.toFloat()
                    computeBands(fftReal, fftImag, effectiveRate, smooth)
                    _bands.value = smooth.copyOf()
                }
                Thread.sleep(6)
            }

            comCall(client, 11) // Stop
        } catch (_: Throwable) {
            // 安静降级
        } finally {
            running = false
        }
    }

    /** 把 FFT 结果压成 [BANDS] 个对数频段（20Hz..16kHz），带时间平滑 */
    private fun computeBands(real: FloatArray, imag: FloatArray, sampleRate: Float, smooth: FloatArray) {
        val bins = FFT_SIZE / 2
        val nyquist = sampleRate / 2f
        val minF = 25f
        val maxF = minOf(16000f, nyquist * 0.95f)
        val logMin = ln(minF.toDouble())
        val logMax = ln(maxF.toDouble())
        var peak = 0f
        val raw = FloatArray(BANDS)
        for (b in 0 until BANDS) {
            val f0 = Math.exp(logMin + (logMax - logMin) * b / BANDS).toFloat()
            val f1 = Math.exp(logMin + (logMax - logMin) * (b + 1) / BANDS).toFloat()
            val i0 = max(1, (f0 / (sampleRate / FFT_SIZE)).toInt())
            val i1 = minOf(bins - 1, max(i0, (f1 / (sampleRate / FFT_SIZE)).toInt()))
            var sum = 0f
            for (i in i0..i1) {
                val re = real[i]
                val im = imag[i]
                sum += sqrt(re * re + im * im)
            }
            val count = (i1 - i0 + 1).coerceAtLeast(1)
            val mag = sum / count
            raw[b] = mag
            if (mag > peak) peak = mag
        }
        if (peak <= 1e-6f) {
            // 静音：平滑回落到 0
            for (b in 0 until BANDS) smooth[b] *= 0.85f
            return
        }
        for (b in 0 until BANDS) {
            val norm = (raw[b] / peak).coerceIn(0f, 1f)
            val boosted = sqrt(norm) // 提亮低能量段
            smooth[b] = smooth[b] * 0.45f + boosted * 0.55f
        }
    }

    /** 原地基 2 FFT（长度必须是 2 的幂） */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = real[i + k]
                    val uIm = imag[i + k]
                    val vRe = real[i + k + len / 2] * curRe - imag[i + k + len / 2] * curIm
                    val vIm = real[i + k + len / 2] * curIm + imag[i + k + len / 2] * curRe
                    real[i + k] = uRe + vRe
                    imag[i + k] = uIm + vIm
                    real[i + k + len / 2] = uRe - vRe
                    imag[i + k + len / 2] = uIm - vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len *= 2
        }
    }

    /** 走 COM vtable：index 0 = QueryInterface */
    private fun comCall(obj: Pointer, index: Int, vararg args: Any?): Int {
        return try {
            val vtable = obj.getPointer(0)
            val fnPtr = vtable.getPointer(index.toLong() * Native.POINTER_SIZE)
            val fn = Function.getFunction(fnPtr)
            val all = arrayOfNulls<Any>(args.size + 1)
            all[0] = obj
            args.forEachIndexed { i, a -> all[i + 1] = a }
            fn.invokeInt(all)
        } catch (_: Throwable) {
            -1
        }
    }
}
