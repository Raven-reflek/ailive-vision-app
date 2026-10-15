package com.luxofilms.vision

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 内存版 SharedPreferences:不依赖 Robolectric,够 Prefs 的读写用。 */
private class FakePrefs : SharedPreferences {
    val map = HashMap<String, Any?>()
    override fun getAll(): MutableMap<String, *> = map
    override fun getString(k: String, d: String?) = map[k] as? String ?: d
    override fun getStringSet(k: String, d: MutableSet<String>?) = d
    override fun getInt(k: String, d: Int) = map[k] as? Int ?: d
    override fun getLong(k: String, d: Long) = map[k] as? Long ?: d
    override fun getFloat(k: String, d: Float) = map[k] as? Float ?: d
    override fun getBoolean(k: String, d: Boolean) = map[k] as? Boolean ?: d
    override fun contains(k: String) = map.containsKey(k)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val staged = HashMap<String, Any?>()
        val removed = HashSet<String>()
        var clearAll = false
        override fun putString(k: String, v: String?) = apply { staged[k] = v }
        override fun putStringSet(k: String, v: MutableSet<String>?) = apply { staged[k] = v }
        override fun putInt(k: String, v: Int) = apply { staged[k] = v }
        override fun putLong(k: String, v: Long) = apply { staged[k] = v }
        override fun putFloat(k: String, v: Float) = apply { staged[k] = v }
        override fun putBoolean(k: String, v: Boolean) = apply { staged[k] = v }
        override fun remove(k: String) = apply { removed += k; staged.remove(k) }
        override fun clear() = apply { clearAll = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clearAll) map.clear()
            removed.forEach { map.remove(it) }
            map.putAll(staged)
        }
    }
}

/** 令牌失效 / 解除绑定:清绑定信息,但标定与其它设置必须原样保留。 */
class PrefsClearBindingTest {
    private lateinit var sp: FakePrefs

    @Before fun setUp() {
        sp = FakePrefs()
        Prefs.initForTest(sp)
        Prefs.server = "https://live.example.com/"
        Prefs.storeId = "store_004"
        Prefs.storeName = "测试四店"
        Prefs.token = "tok-secret"
        Prefs.calibrationJson = """{"screenQuad":[0,0,1,0,1,1,0,1]}"""
        Prefs.sampleFps = 0.8f
        Prefs.batteryAsked = true
        Prefs.captureWanted = true
    }

    @Test fun beforeClear_isBoundAndCalibrated() {
        assertTrue(Prefs.bound)
        assertTrue(Prefs.calibrated)
    }

    @Test fun clearBinding_removesServerStoreAndToken() {
        Prefs.clearBinding("x")
        assertFalse(Prefs.bound)
        assertEquals("", Prefs.server)
        assertEquals("", Prefs.storeId)
        assertEquals("", Prefs.storeName)
        assertEquals("", Prefs.token)
    }

    @Test fun clearBinding_keepsCalibrationAndSettings() {
        val calBefore = Prefs.calibrationJson
        Prefs.clearBinding("x")
        assertTrue("标定必须保留", Prefs.calibrated)
        assertEquals(calBefore, Prefs.calibrationJson)
        assertEquals(0.8f, Prefs.sampleFps, 0f)
        assertTrue(Prefs.batteryAsked)
    }

    @Test fun clearBinding_stopsAutoCaptureAndLeavesNotice() {
        Prefs.clearBinding(BindingGuard.NOTICE_INVALID)
        assertFalse("重新绑定后不能自己把摄像头打开", Prefs.captureWanted)
        assertEquals(BindingGuard.NOTICE_INVALID, Prefs.bindNotice)
    }

    @Test fun clearBinding_isIdempotent() {
        Prefs.clearBinding("a")
        Prefs.clearBinding("b")
        assertFalse(Prefs.bound)
        assertTrue(Prefs.calibrated)
        assertEquals("b", Prefs.bindNotice)
    }

    @Test fun rebindAfterClear_worksAndKeepsCalibration() {
        Prefs.clearBinding("x")
        Prefs.server = "https://other.example.com"
        Prefs.storeId = "store_009"
        Prefs.token = "new-token"
        Prefs.bindNotice = ""
        assertTrue(Prefs.bound)
        assertTrue(Prefs.calibrated)
        assertEquals("", Prefs.bindNotice)
    }

    @Test fun fullClear_stillWipesEverything_forReference() {
        // 老的 Prefs.clear() 行为不变(会清标定)——这正是令牌失效时不能用它的原因
        Prefs.clear()
        assertFalse(Prefs.bound)
        assertFalse(Prefs.calibrated)
    }
}
