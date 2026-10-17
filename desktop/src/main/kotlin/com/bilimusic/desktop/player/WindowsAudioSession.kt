package com.bilimusic.desktop.player

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * 直接控制「本进程」在 Windows 音量合成器里的音量（WASAPI / ISimpleAudioVolume）。
 *
 * 为什么不用 VLC 的音量：
 * - VLC 的 audio_set_volume 是异步命令，要经它的音频线程，听感上有延迟；
 * - VLC 内部是立方曲线（gain = volume³），再把 gain 同步成会话音量，绕两层还不精确。
 *
 * 这里直接拿本进程的音频会话（VLC 跑在同一个进程里），调 SetMasterVolume：
 * 即时生效、精度 0.1%、音量合成器显示的就是我们设的值。
 *
 * 实现：JNA 手动走 COM vtable，不引入额外依赖。
 */
object WindowsAudioSession {

    private const val CLSCTX_ALL = 23

    private val CLSID_MMDeviceEnumerator = GUID.fromString("{BCDE0395-E52F-467C-8E3D-C4579291692E}")
    private val IID_IMMDeviceEnumerator = GUID.fromString("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
    private val IID_IAudioSessionManager2 = GUID.fromString("{77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F}")
    private val IID_IAudioSessionControl2 = GUID.fromString("{BFB7FFD3-56B6-4965-8C9C-2B6C5C4B8B7D}")
    private val IID_ISimpleAudioVolume = GUID.fromString("{87CE5498-68D6-44E5-9215-6DA47EF883D8}")

    @Volatile
    private var simpleVolume: Pointer? = null

    @Volatile
    private var comReady = false

    /** 找一次自己的会话，之后复用接口指针 */
    @Synchronized
    fun ensureReady(): Boolean {
        if (comReady && simpleVolume != null) return true
        return runCatching {
            Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED)
            val enumerator = PointerByReference()
            val hr = Ole32.INSTANCE.CoCreateInstance(
                CLSID_MMDeviceEnumerator,
                null,
                CLSCTX_ALL,
                IID_IMMDeviceEnumerator,
                enumerator,
            )
            if (hr != WinNT.S_OK) {
                println("[Audio] CoCreateInstance failed hr=" + hr.toString())
                return@runCatching false
            }
            val devEnum = enumerator.value ?: return@runCatching false

            // IMMDeviceEnumerator::GetDefaultAudioEndpoint(eRender=0, eConsole=0)
            val device = PointerByReference()
            val hrDev = comCall(devEnum, 4, 0, 0, device)
            if (hrDev != 0) {
                println("[Audio] GetDefaultAudioEndpoint failed hr=" + Integer.toHexString(hrDev))
                return@runCatching false
            }
            val dev = device.value ?: return@runCatching false

            // IMMDevice::Activate(IAudioSessionManager2)
            val manager = PointerByReference()
            val hrMgr = comCall(dev, 3, IID_IAudioSessionManager2, CLSCTX_ALL, null, manager)
            if (hrMgr != 0) {
                println("[Audio] Activate(IAudioSessionManager2) failed hr=" + Integer.toHexString(hrMgr))
                return@runCatching false
            }
            val mgr = manager.value ?: return@runCatching false

            // IAudioSessionManager2::GetSessionEnumerator
            val sessionEnum = PointerByReference()
            if (comCall(mgr, 5, sessionEnum) != 0) return@runCatching false
            val sesEnum = sessionEnum.value ?: return@runCatching false

            val count = IntByReference()
            val hrCount = comCall(sesEnum, 3, count)
            if (hrCount != 0) {
                println("[Audio] GetCount failed hr=" + Integer.toHexString(hrCount))
                return@runCatching false
            }

            val myPid = ProcessHandle.current().pid()
            for (i in 0 until count.value) {
                val ctlPtr = PointerByReference()
                if (comCall(sesEnum, 4, i, ctlPtr) != 0) continue
                val ctl = ctlPtr.value ?: continue

                val ctl2Ptr = PointerByReference()
                if (comCall(ctl, 0, IID_IAudioSessionControl2, ctl2Ptr) != 0) continue
                val ctl2 = ctl2Ptr.value ?: continue

                // IAudioSessionControl2::GetProcessId
                val pidRef = IntByReference()
                if (comCall(ctl2, 14, pidRef) != 0) continue
                if (pidRef.value.toLong() != myPid) continue

                val volPtr = PointerByReference()
                if (comCall(ctl, 0, IID_ISimpleAudioVolume, volPtr) != 0) continue
                val vol = volPtr.value ?: continue

                simpleVolume = vol
                comReady = true
                println("[Audio] 已接管本进程音频会话音量")
                return@runCatching true
            }
            false
        }.onFailure { println("[Audio] 初始化失败: " + it) }.getOrDefault(false)
    }

    /** 设置会话音量，[volume] 0.0..1.0 */
    @Synchronized
    fun setVolume(volume: Float): Boolean {
        if (!ensureReady()) return false
        val vol = simpleVolume ?: return false
        return runCatching {
            comCall(vol, 3, volume.coerceIn(0f, 1f), null) == 0
        }.getOrDefault(false)
    }

    /** 读当前会话音量 0..1，失败返回 null */
    @Synchronized
    fun getVolume(): Float? {
        if (!ensureReady()) return null
        val vol = simpleVolume ?: return null
        return runCatching {
            val ref = FloatByReference()
            if (comCall(vol, 4, ref) == 0) ref.value else null
        }.getOrNull()
    }

    /** 会话失效（切歌/音频重建）时调用，下次重新枚举 */
    @Synchronized
    fun invalidate() {
        simpleVolume = null
        comReady = false
    }

    /** 走 COM vtable：index 0 = QueryInterface */
    private fun comCall(obj: Pointer, index: Int, vararg args: Any?): Int {
        val vtable = obj.getPointer(0)
        val fnPtr = vtable.getPointer(index.toLong() * Native.POINTER_SIZE)
        val fn = Function.getFunction(fnPtr)
        val all = arrayOfNulls<Any>(args.size + 1)
        all[0] = obj
        args.forEachIndexed { i, a -> all[i + 1] = a }
        return fn.invokeInt(all)
    }
}
