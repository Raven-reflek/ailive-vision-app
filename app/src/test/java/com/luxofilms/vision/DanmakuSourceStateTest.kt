package com.luxofilms.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 弹幕来源提示(ADR-0033):什么时候提示"这台上传的弹幕不会被使用"。 */
class DanmakuSourceStateTest {

    @Before fun setUp() = DanmakuSourceState.reset()

    @Test fun initially_unknown_noNotice() {
        assertEquals("", DanmakuSourceState.source)
        assertFalse(DanmakuSourceState.ignoredByServer)
    }

    @Test fun me_dycast_showsNotice() {
        DanmakuSourceState.noteFromMe("dycast")
        assertTrue(DanmakuSourceState.ignoredByServer)
    }

    @Test fun me_visionOrBoth_noNotice() {
        for (s in listOf("vision", "both")) {
            DanmakuSourceState.noteFromMe(s)
            assertFalse(s, DanmakuSourceState.ignoredByServer)
            assertEquals(s, DanmakuSourceState.source)
        }
    }

    @Test fun me_missingOrDirtyValue_isUnknown_soOldServerNeverShowsNotice() {
        DanmakuSourceState.noteFromMe("dycast")
        for (s in listOf(null, "", " ", "weibo")) {
            DanmakuSourceState.noteFromMe(s)
            assertEquals("[$s]", "", DanmakuSourceState.source)
            assertFalse(DanmakuSourceState.ignoredByServer)
        }
    }

    @Test fun me_isCaseAndSpaceInsensitive() {
        DanmakuSourceState.noteFromMe(" DYCAST ")
        assertTrue(DanmakuSourceState.ignoredByServer)
    }

    @Test fun upload_ignored_showsNotice_evenIfResponseOmitsSource() {
        DanmakuSourceState.noteUpload(ignored = true, src = "dycast")
        assertTrue(DanmakuSourceState.ignoredByServer)
        DanmakuSourceState.reset()
        DanmakuSourceState.noteUpload(ignored = true, src = null)        // 被忽略只可能是 dycast
        assertTrue(DanmakuSourceState.ignoredByServer)
    }

    @Test fun upload_accepted_clearsNotice_butDoesNotInventTheSource() {
        DanmakuSourceState.noteFromMe("dycast")
        DanmakuSourceState.noteUpload(ignored = false, src = null)
        assertFalse(DanmakuSourceState.ignoredByServer)
        assertEquals("被正常处理只能说明不是 dycast,不替云端断言是 both 还是 vision", "", DanmakuSourceState.source)
    }

    @Test fun upload_accepted_keepsKnownVisionOrBoth() {
        DanmakuSourceState.noteFromMe("vision")
        DanmakuSourceState.noteUpload(ignored = false, src = null)
        assertEquals("vision", DanmakuSourceState.source)
    }

    @Test fun reset_forgetsPreviousStore() {
        DanmakuSourceState.noteFromMe("dycast")
        DanmakuSourceState.reset()
        assertFalse(DanmakuSourceState.ignoredByServer)
        assertEquals("", DanmakuSourceState.source)
    }
}
