package hev.sockstun

import android.util.Log

/**
 * Wrapper class for hev-socks5-tunnel native library.
 * The native library looks for this specific class in JNI_OnLoad.
 */
object TProxyService {
    private const val TAG = "TProxyService"

    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
            Log.i(TAG, "Loaded hev-socks5-tunnel native library")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load hev-socks5-tunnel library", e)
        }
    }

    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int)

    @JvmStatic
    external fun TProxyStopService()

    @JvmStatic
    external fun TProxyGetStats(): LongArray
}
