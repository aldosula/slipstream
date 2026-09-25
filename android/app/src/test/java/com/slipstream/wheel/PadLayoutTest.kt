package com.slipstream.wheel

import com.slipstream.wheel.pad.GyroAim
import com.slipstream.wheel.pad.PadButton
import com.slipstream.wheel.pad.PadStyle
import com.slipstream.wheel.pad.TriggerMath
import com.slipstream.wheel.pad.layout.Box
import com.slipstream.wheel.pad.layout.ButtonShape
import com.slipstream.wheel.pad.layout.ControlKind
import com.slipstream.wheel.pad.layout.DefaultLayouts
import com.slipstream.wheel.pad.layout.KeyValueStore
import com.slipstream.wheel.pad.layout.LayoutCodec
import com.slipstream.wheel.pad.layout.LayoutStore
import com.slipstream.wheel.pad.layout.PadControl
import com.slipstream.wheel.pad.layout.PadGeometry
import com.slipstream.wheel.pad.layout.PadLayout
import com.slipstream.wheel.pad.layout.StickClick
import com.slipstream.wheel.pad.layout.StickOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DefaultLayouts against the tables of ARCHITECTURE.md 7.2, typed in here independently. */
class DefaultLayoutsTest {
    private class Row(val id: String, val kind: ControlKind, val binding: Int, val cx: Float, val cy: Float, val w: Float, val h: Float, val shape: ButtonShape? = null)

    private val ps = listOf(
        Row("l2", ControlKind.TRIGGER, 0, 0.134f, 0.105f, 171f, 47f),
        Row("r2", ControlKind.TRIGGER, 1, 0.866f, 0.105f, 171f, 47f),
        Row("l1", ControlKind.BUTTON, PadButton.L1, 0.134f, 0.230f, 171f, 39f),
        Row("r1", ControlKind.BUTTON, PadButton.R1, 0.866f, 0.230f, 171f, 39f),
        Row("create", ControlKind.BUTTON, PadButton.CREATE, 0.313f, 0.098f, 77f, 30f, ButtonShape.PILL),
        Row("touchpad", ControlKind.TOUCHPAD, 0, 0.500f, 0.193f, 226f, 124f),
        Row("options", ControlKind.BUTTON, PadButton.OPTIONS, 0.692f, 0.098f, 85f, 30f, ButtonShape.PILL),
        Row("dpad", ControlKind.DPAD, 0, 0.144f, 0.480f, 127f, 127f),
        Row("left_stick", ControlKind.STICK, 0, 0.303f, 0.764f, 116f, 116f),
        Row("right_stick", ControlKind.STICK, 1, 0.697f, 0.764f, 116f, 116f),
        Row("face", ControlKind.FACE, 0, 0.856f, 0.500f, 154f, 154f),
        Row("ps", ControlKind.BUTTON, PadButton.HOME, 0.500f, 0.480f, 47f, 47f, ButtonShape.ROUND),
        Row("mute", ControlKind.BUTTON, PadButton.MUTE, 0.500f, 0.598f, 55f, 25f, ButtonShape.PILL),
    )

    private val xbox = listOf(
        Row("lt", ControlKind.TRIGGER, 0, 0.134f, 0.105f, 171f, 47f),
        Row("rt", ControlKind.TRIGGER, 1, 0.866f, 0.105f, 171f, 47f),
        Row("lb", ControlKind.BUTTON, PadButton.L1, 0.134f, 0.230f, 171f, 39f),
        Row("rb", ControlKind.BUTTON, PadButton.R1, 0.866f, 0.230f, 171f, 39f),
        Row("left_stick", ControlKind.STICK, 0, 0.153f, 0.507f, 116f, 116f),
        Row("dpad", ControlKind.DPAD, 0, 0.328f, 0.784f, 127f, 127f),
        Row("right_stick", ControlKind.STICK, 1, 0.681f, 0.764f, 116f, 116f),
        Row("face", ControlKind.FACE, 0, 0.856f, 0.500f, 154f, 154f),
        Row("view", ControlKind.BUTTON, PadButton.CREATE, 0.413f, 0.419f, 36f, 36f, ButtonShape.ROUND),
        Row("xbox", ControlKind.BUTTON, PadButton.HOME, 0.500f, 0.419f, 55f, 55f, ButtonShape.ROUND),
        Row("menu", ControlKind.BUTTON, PadButton.OPTIONS, 0.588f, 0.419f, 36f, 36f, ButtonShape.ROUND),
        Row("share", ControlKind.BUTTON, PadButton.SHARE, 0.500f, 0.605f, 61f, 25f, ButtonShape.PILL),
    )

    private fun check(layout: PadLayout, rows: List<Row>) {
        assertEquals(rows.size, layout.controls.size)
        for ((i, r) in rows.withIndex()) {
            val c = layout.controls[i]
            assertEquals(r.id, c.id)
            assertEquals(r.id, r.kind, c.kind)
            assertEquals(r.id, r.binding, c.binding)
            assertEquals(r.id, r.cx, c.cx, 0f)
            assertEquals(r.id, r.cy, c.cy, 0f)
            assertEquals(r.id, r.w, c.widthDp, 0f)
            assertEquals(r.id, r.h, c.heightDp, 0f)
            if (r.shape != null) assertEquals(r.id, r.shape, c.options.shape)
            if (c.kind == ControlKind.FACE) assertEquals(50f, c.options.faceButtonDp, 0f)
            assertEquals(1f, c.opacity, 0f)
        }
        assertEquals(882f, layout.refWidthDp, 0f)
        assertEquals(411f, layout.refHeightDp, 0f)
        assertTrue(layout.builtIn)
        assertTrue("slide-to-press on by default", layout.slideToPress)
        assertEquals("gyro aim off by default", GyroAim.Mode.OFF, layout.gyroMode)
    }

    @Test
    fun playStationTable() {
        val l = DefaultLayouts.playStation()
        assertEquals(PadStyle.PLAYSTATION, l.style)
        assertEquals("playstation", l.id)
        check(l, ps)
    }

    @Test
    fun xboxTable() {
        val l = DefaultLayouts.xbox()
        assertEquals(PadStyle.XBOX, l.style)
        assertEquals("xbox", l.id)
        check(l, xbox)
    }

    @Test
    fun pauseRingAndFaceArrangement() {
        assertEquals(0.500f, DefaultLayouts.PAUSE_CX, 0f)
        assertEquals(0.054f, DefaultLayouts.PAUSE_CY, 0f)
        // Top, right, bottom, left: Triangle / Y, Circle / B, Cross / A, Square / X.
        assertEquals(PadButton.TRIANGLE, DefaultLayouts.FACE_BITS[0])
        assertEquals(PadButton.CIRCLE, DefaultLayouts.FACE_BITS[1])
        assertEquals(PadButton.CROSS, DefaultLayouts.FACE_BITS[2])
        assertEquals(PadButton.SQUARE, DefaultLayouts.FACE_BITS[3])
        assertEquals("A", PadButton.name(PadButton.CROSS, PadStyle.XBOX))
        assertEquals("Triangle", PadButton.name(PadButton.TRIANGLE, PadStyle.PLAYSTATION))
        assertNull(PadButton.name(PadButton.MUTE, PadStyle.XBOX))
        assertNull(PadButton.name(PadButton.SHARE, PadStyle.PLAYSTATION))
    }

    @Test
    fun defaultsAreLegalOnTheReferenceScreen() {
        for (l in listOf(DefaultLayouts.playStation(), DefaultLayouts.xbox())) {
            val over = PadGeometry.overlapping(l, 882f, 411f)
            for (i in over.indices) assertFalse("${l.id}/${l.controls[i].id} overlaps", over[i])
            assertEquals("no shrink on the reference screen", 1f, PadGeometry.fitScale(l, 882f, 411f), 0f)
            for (c in l.controls) {
                val clamped = PadGeometry.clampControl(c, 882f, 411f)
                assertEquals("${l.id}/${c.id} already inside the safe margin", c, clamped)
            }
        }
    }
}

class PadGeometryTest {
    private val w = 882f
    private val h = 411f

    private fun ctl(cx: Float, cy: Float, width: Float = 100f, height: Float = 50f, kind: ControlKind = ControlKind.BUTTON) =
        PadControl("c", kind, 0, cx, cy, width, height)

    @Test
    fun safeMarginIs12DpFromTheLongEdges() {
        val top = PadGeometry.clampControl(ctl(0.5f, 0f), w, h)
        assertEquals(12f + 25f, top.cy * h, 0.01f)
        val bottom = PadGeometry.clampControl(ctl(0.5f, 1f), w, h)
        assertEquals(h - 12f - 25f, bottom.cy * h, 0.01f)
        // The short edges only keep the control on screen.
        val left = PadGeometry.clampControl(ctl(0f, 0.5f), w, h)
        assertEquals(50f, left.cx * w, 0.01f)
        val right = PadGeometry.clampControl(ctl(1f, 0.5f), w, h)
        assertEquals(w - 50f, right.cx * w, 0.01f)
        // Portrait area: the long edges are left and right.
        val portrait = PadGeometry.clampControl(ctl(0f, 0f), 411f, 882f)
        assertEquals(12f + 50f, portrait.cx * 411f, 0.01f)
        assertEquals(25f, portrait.cy * 882f, 0.01f)
    }

    @Test
    fun sizeIsKeptUnlessItCannotFit() {
        val c = PadGeometry.clampControl(ctl(0.5f, 0.5f, 120f, 60f), w, h)
        assertEquals(120f, c.widthDp, 0f)
        assertEquals(60f, c.heightDp, 0f)
        val huge = PadGeometry.clampControl(ctl(0.5f, 0.5f, 200f, 480f), w, h)
        assertEquals(h - 24f, huge.heightDp, 0.01f)
        assertEquals(200f, huge.widthDp, 0f)
        val stick = PadGeometry.clampControl(ctl(0.5f, 0.5f, 450f, 450f, ControlKind.STICK), w, h)
        assertEquals("square kinds shrink evenly", stick.widthDp, stick.heightDp, 0.01f)
        assertEquals(h - 24f, stick.heightDp, 0.01f)
    }

    @Test
    fun controlsAreMovedClearOfTheDisplayCutout() {
        // Hole punch on the left short edge, vertically centred (Note 20 Ultra in landscape).
        val cut = Box(0f, 195f, 14f, 216f)
        val c = PadGeometry.clampControl(ctl(0.04f, 0.5f, 60f, 60f), w, h, listOf(cut))
        val left = c.cx * w - c.widthDp / 2f
        val top = c.cy * h - c.heightDp / 2f
        val box = Box(left, top, left + c.widthDp, top + c.heightDp)
        assertFalse("clear of the cutout: $box", box.intersects(cut))
        assertEquals("smallest move: to the right of the hole", 14f, left, 0.01f)
    }

    @Test
    fun uniformShrinkOnlyWhenControlsWouldOverlap() {
        val l = DefaultLayouts.playStation()
        assertEquals("a larger screen never grows controls", 1f, PadGeometry.fitScale(l, 1000f, 480f), 0f)
        val s = PadGeometry.fitScale(l, 640f, 320f)
        assertTrue("small screen shrinks, got $s", s < 1f && s >= PadGeometry.MIN_SCALE)
        // After the shrink, nothing that was apart on the reference screen overlaps.
        val a = Box()
        val b = Box()
        for (i in l.controls.indices) {
            for (j in i + 1 until l.controls.size) {
                PadGeometry.rect(l.controls[i], 640f, 320f, s, 1f, a)
                PadGeometry.rect(l.controls[j], 640f, 320f, s, 1f, b)
                assertFalse("${l.controls[i].id} vs ${l.controls[j].id}", a.intersects(b))
            }
        }
        // A deliberate overlap on the reference screen does not shrink anything.
        val overlapped = l.copy(controls = l.controls + ctl(0.5f, 0.193f, 60f, 60f).copy(id = "extra"))
        assertEquals(1f, PadGeometry.fitScale(overlapped, 882f, 411f), 0f)
        val flags = PadGeometry.overlapping(overlapped, 882f, 411f)
        assertTrue(flags[l.controls.indexOfFirst { it.id == "touchpad" }])
        assertTrue(flags.last())
    }

    @Test
    fun snapAndMirror() {
        assertEquals(12f, PadGeometry.snap(13.9f), 0f)
        assertEquals(16f, PadGeometry.snap(14.1f), 0f)
        assertEquals(-4f, PadGeometry.snap(-5f), 0f)
        val l = DefaultLayouts.xbox()
        val m = PadGeometry.mirror(l)
        assertEquals(1f - 0.153f, m.control("left_stick")!!.cx, 1e-6f)
        assertEquals("bindings unchanged", 0, m.control("left_stick")!!.binding)
        assertEquals(l.control("face")!!.cy, m.control("face")!!.cy, 0f)
        val back = PadGeometry.mirror(m)
        for (i in l.controls.indices) assertEquals(l.controls[i].cx, back.controls[i].cx, 1e-6f)
    }
}

class LayoutStoreTest {
    private class MemoryStore : KeyValueStore {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    @Test
    fun jsonRoundTripKeepsEveryField() {
        val base = DefaultLayouts.playStation()
        val edited = base.copy(
            id = "custom-7",
            name = "Racing \"thumbs\"",
            builtIn = false,
            slideToPress = false,
            gyroMode = GyroAim.Mode.WHILE_TOUCHING,
            gyroSensitivity = 1.75f,
            gyroInvertY = true,
            refWidthDp = 800f,
            refHeightDp = 380f,
            controls = base.controls.map {
                when (it.kind) {
                    ControlKind.STICK -> it.copy(
                        opacity = 0.6f, haptic = 0f,
                        options = it.options.copy(stickOrigin = StickOrigin.FLOATING, stickClick = StickClick.BOTH, deadzone = 0.12f, curve = 1.6f),
                    )
                    ControlKind.TRIGGER -> it.copy(options = it.options.copy(triggerMode = TriggerMath.Mode.TAP), haptic = 1f)
                    ControlKind.FACE -> it.copy(widthDp = 180f, heightDp = 180f, options = it.options.copy(faceButtonDp = 58.4f))
                    else -> it.copy(cx = it.cx * 0.9f)
                }
            },
        )
        val text = LayoutCodec.encode(edited)
        val back = LayoutCodec.decode(text)
        assertEquals(edited, back)
        assertEquals(DefaultLayouts.xbox(), LayoutCodec.decode(LayoutCodec.encode(DefaultLayouts.xbox())))
    }

    @Test
    fun decodeIsDefensive() {
        assertNull(LayoutCodec.decode(null))
        assertNull(LayoutCodec.decode(""))
        assertNull(LayoutCodec.decode("{not json"))
        assertNull(LayoutCodec.decode("{\"v\":99,\"id\":\"x\",\"controls\":[]}"))
        val weird = """{"v":1,"id":"custom-1","name":"  ","style":"nintendo","controls":[
            {"id":"a","kind":"button","binding":3,"cx":7,"cy":-2,"width_dp":1,"height_dp":9999,"opacity":0,"haptic":5,
             "options":{"shape":"hexagon","deadzone":3}},
            {"id":"b","kind":"laser","cx":0.5,"cy":0.5}]}"""
        val l = LayoutCodec.decode(weird)
        assertNotNull(l)
        l!!
        assertEquals(PadStyle.PLAYSTATION, l.style)
        assertEquals("Controller", l.name)
        assertEquals("unknown kinds are skipped", 1, l.controls.size)
        val c = l.controls[0]
        assertEquals(1f, c.cx, 0f)
        assertEquals(0f, c.cy, 0f)
        assertEquals(PadGeometry.MIN_SIZE_DP, c.widthDp, 0f)
        assertEquals(PadGeometry.MAX_SIZE_DP, c.heightDp, 0f)
        assertTrue(c.opacity > 0f)
        assertEquals(1f, c.haptic, 0f)
        assertEquals(ButtonShape.RECT, c.options.shape)
        assertEquals(0.5f, c.options.deadzone, 0f)
    }

    @Test
    fun builtInsExistResetButCannotBeDeletedOrRenamed() {
        val store = LayoutStore(MemoryStore())
        assertEquals(listOf("playstation", "xbox"), store.ids())
        assertEquals(DefaultLayouts.playStation(), store.load("playstation"))
        assertEquals(DefaultLayouts.xbox(), store.load("xbox"))
        assertNull(store.load("custom-1"))
        assertFalse(store.delete("playstation"))
        assertFalse(store.rename("xbox", "Mine"))
        // Edit a built-in, then reset it.
        val edited = DefaultLayouts.xbox().let { it.copy(controls = it.controls.map { c -> c.copy(opacity = 0.5f) }, name = "Hacked") }
        store.save(edited)
        val loaded = store.load("xbox")!!
        assertEquals("built-in name is fixed", "Xbox", loaded.name)
        assertEquals(0.5f, loaded.controls[0].opacity, 0f)
        assertEquals(DefaultLayouts.xbox(), store.reset("xbox"))
        assertEquals(DefaultLayouts.xbox(), store.load("xbox"))
    }

    @Test
    fun duplicateRenameDeleteAndLastUsed() {
        val kv = MemoryStore()
        val store = LayoutStore(kv)
        assertEquals("PlayStation until one is chosen", "playstation", store.lastUsedId)
        val copy = store.duplicate("xbox")!!
        assertEquals("Xbox copy", copy.name)
        assertFalse(copy.builtIn)
        assertEquals(PadStyle.XBOX, copy.style)
        assertEquals(listOf("playstation", "xbox", copy.id), store.ids())
        store.lastUsedId = copy.id
        assertEquals(copy.id, LayoutStore(kv).lastUsedId) // remembered across instances
        assertTrue(store.rename(copy.id, "  Couch  "))
        assertEquals("Couch", store.load(copy.id)!!.name)
        assertFalse(store.rename(copy.id, "   "))
        val second = store.duplicate(copy.id, "Second")!!
        assertNotEquals(copy.id, second.id)
        // Reset of a custom profile: default of its style, same id and name.
        store.save(store.load(second.id)!!.let { it.copy(controls = it.controls.drop(3)) })
        val reset = store.reset(second.id)!!
        assertEquals(second.id, reset.id)
        assertEquals("Second", reset.name)
        assertEquals(DefaultLayouts.xbox().controls, reset.controls)
        assertTrue(store.delete(copy.id))
        assertNull(store.load(copy.id))
        assertEquals("deleting the last used profile falls back", "playstation", store.lastUsedId)
        assertEquals(listOf("playstation", "xbox", second.id), store.ids())
        store.lastUsedId = "nope"
        assertEquals("playstation", store.lastUsedId)
        assertEquals(DefaultLayouts.playStation(), store.current())
    }

    @Test
    fun corruptStoredProfileFallsBackToTheDefault() {
        val kv = MemoryStore()
        kv.put("profile.playstation", "{garbage")
        assertEquals(DefaultLayouts.playStation(), LayoutStore(kv).load("playstation"))
        kv.put("profiles", "not an array")
        assertEquals(listOf("playstation", "xbox"), LayoutStore(kv).ids())
    }

    @Test
    fun savedLayoutsAreClampedToTheSafeMarginFirst() {
        // The editor clamps before saving; a stored profile read back is legal on the same screen.
        val bad = DefaultLayouts.playStation().copy(id = "custom-1", builtIn = false, name = "Bad").let { l ->
            l.copy(controls = l.controls.map { if (it.id == "l2") it.copy(cy = 0f) else it })
        }
        val clamped = PadGeometry.clampLayout(bad, 882f, 411f)
        val store = LayoutStore(MemoryStore())
        store.save(clamped)
        val back = store.load("custom-1")!!
        val l2 = back.control("l2")!!
        assertEquals(12f, l2.cy * 411f - l2.heightDp / 2f, 0.01f)
    }
}
