package com.coc.suncode.core.qq

import kotlin.math.abs
import kotlin.math.roundToInt

/** Fixed CN 18.116.2 landscape controls, sampled from the 1280 x 720 login screens. */
internal object QqLoginColors {
    data class Frame(
        val supported: Boolean = true,
        val loginVisible: Boolean = false,
        val control: QqGameLogin.Control? = null
    )

    fun detect(width: Int, height: Int, pixel: (Int, Int) -> Int): Frame {
        // Do not stretch the profile onto portrait, letterboxed or differently proportioned layouts.
        if (width < 640 || height < 360 || abs(width.toDouble() / height - 1280.0 / 720) > 0.01) {
            return Frame(supported = false)
        }
        fun color(x: Int, y: Int): Int = pixel(
            (x * width / 1280.0).roundToInt(), (y * height / 720.0).roundToInt()
        )
        fun near(x: Int, y: Int, expected: Int): Boolean {
            val actual = color(x, y)
            return listOf(16, 8, 0).all { shift ->
                abs((actual shr shift and 255) - (expected shr shift and 255)) <= 20
            }
        }
        fun target(x: Int, y: Int, qq: Boolean = false) = QqGameLogin.Control(
            (x * width / 1280.0).roundToInt(), (y * height / 720.0).roundToInt(), qq, !qq
        )
        // Require both green buttons plus the header and panel corners, not an isolated green pixel.
        val consent = near(140, 30, 0x857e7c) && near(1130, 30, 0x857e7c) &&
            near(140, 75, 0x5e5451) && near(1130, 75, 0x5e5451) &&
            near(140, 560, 0xd0cbc5) && near(1130, 560, 0xd0cbc5) &&
            listOf(200, 570, 680, 1040).all { x -> near(x, 605, 0xcbe673) && near(x, 644, 0x63ac22) }
        if (consent) return Frame(control = target(389, 620))

        // The QQ icon and checkbox surround distinguish the login page from similarly colored game UI.
        val login = near(688, 415, 0x020202) && near(690, 435, 0xffffff) &&
            near(260, 500, 0xc2baac) && near(300, 500, 0xc2baac)
        if (!login) return Frame()
        val disabled = near(460, 420, 0xcbcbcb) && near(602, 420, 0xcbcbcb) &&
            near(720, 420, 0xa9a9a9) && near(865, 420, 0xa9a9a9) &&
            near(720, 445, 0x5c5c5c) && near(865, 445, 0x5c5c5c)
        val enabled = near(460, 420, 0xc1e269) && near(602, 420, 0xc1e268) &&
            near(720, 420, 0x5bc6e7) && near(865, 420, 0x5bc6e7) &&
            near(720, 445, 0x1970a8) && near(865, 445, 0x1970a8)
        if (!disabled && !enabled) return Frame()
        val empty = near(275, 490, 0xc2baac) && near(285, 481, 0xc2baac) && near(280, 503, 0xc2baac)
        val checked = near(275, 490, 0xa1d54f) && near(285, 481, 0xc0e06a) && near(280, 503, 0x5fad1f)
        return Frame(loginVisible = true, control = when {
            disabled && empty -> target(280, 490)
            enabled && checked -> target(794, 426, qq = true)
            // Gray buttons with a green tick mean a login is in flight; never toggle the checkbox back off.
            else -> null
        })
    }
}
