// SPDX-License-Identifier: Apache-2.0
package local.cardmin.ui

/** Input rules from the original app (flows.md §2.8, §4.5). */
object Validation {
    /** Returns an error text or null. Rule: ≥ 8 chars, no whitespace, 3 of {upper, lower, digit, special}. */
    fun password(pw: String, repeat: String): String? {
        if (pw != repeat) return "Die Passwörter stimmen nicht überein."
        val classes = listOf(
            pw.any { it.isUpperCase() }, pw.any { it.isLowerCase() },
            pw.any { it.isDigit() }, pw.any { !it.isLetterOrDigit() },
        ).count { it }
        if (pw.length < 8 || pw.any { it.isWhitespace() } || classes < 3) {
            return "Das Passwort muss mindestens 8 Zeichen lang sein und 3 von 4 enthalten: Großbuchstaben, Kleinbuchstaben, Ziffern, Sonderzeichen."
        }
        return null
    }

    /** 4 digits, at least 3 different digits, no ascending/descending run like 1234 or 4321. */
    fun pin(pin: String, repeat: String): String? {
        if (pin != repeat) return "Die PINs stimmen nicht überein."
        if (!Regex("^\\d{4}$").matches(pin)) return "Die PIN muss aus 4 Ziffern bestehen."
        if (pin.toSet().size < 3) return "Die PIN muss mindestens 3 verschiedene Ziffern enthalten."
        val d = pin.map { it - '0' }
        val steps = d.zipWithNext { a, b -> b - a }.toSet()
        if (steps == setOf(1) || steps == setOf(-1)) return "Die PIN darf keine Zahlenfolge wie 1234 oder 4321 sein."
        return null
    }

    /** Normalises to E.164; a leading 0 is treated as a German number. */
    fun phone(input: String): String? {
        var n = input.filter { it.isDigit() || it == '+' }
        if (n.startsWith("00")) n = "+" + n.drop(2)
        if (n.startsWith("0")) n = "+49" + n.drop(1)
        return n.takeIf { Regex("^\\+[1-9]\\d{6,14}$").matches(it) }
    }

    /** Accepts either a bare token or the full link from the e-mail (…?token=XYZ). */
    fun tokenFromLink(input: String): String {
        val t = input.trim()
        val q = Regex("[?&](?:token|activationToken|passwordResetToken)=([^&#\\s]+)").find(t)
        return q?.groupValues?.get(1) ?: t.substringAfterLast('/')
    }
}
