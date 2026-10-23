package pl.f650gs.forum

import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

internal fun MainActivity.renderNotifications(page: NotificationsPage) {
    content.removeAllViews()
    // Opening the list marks everything read on the forum, as the web page does.
    counters = counters.copy(notifications = 0)
    setBadge(notificationsBadge, 0)
    content.addView(row {
        addView(label("Powiadomienia", 17f, INK, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        addView(pill("Ustawienia", TEAL_LIGHT, LINK) {
            navigate(Screen.NotificationSettings)
        })
    }.apply { layoutParams = margin(top = 2, bottom = 12) })
    if (page.items.isEmpty()) content.addView(emptyCard("Brak powiadomień", "Tu pojawią się cytaty, wzmianki i reakcje na Twoje wpisy."))
    addNotificationCards(page)
}

/** Cards for [page], plus a button that appends the next page in place of itself. */
private fun MainActivity.addNotificationCards(page: NotificationsPage) {
    page.items.forEach { item ->
        val card = baseCard()
        if (item.unread) card.background = rounded(if (darkMode) Color.rgb(34, 51, 54) else Color.rgb(233, 245, 241), 17)
        card.addView(row {
            addView(initialBadge(item.author.ifBlank { "?" }, item.photo), LinearLayout.LayoutParams(dp(42), dp(42)))
            val text = LinearLayout(this@addNotificationCards).apply { orientation = LinearLayout.VERTICAL }
            if (item.unread) text.addView(label("●  NOWE", 10f, ACCENT_NEW, bold = true))
            text.addView(label(item.text, 15f, INK, bold = item.unread).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
            if (item.time.isNotBlank()) text.addView(label(item.time, 12f, MUTED), margin(top = 3))
            addView(text, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        })
        card.setOnClickListener { routePostLink(Uri.parse(item.url)) }
        content.addView(card)
    }
    if (page.nextUrl.isBlank()) return
    lateinit var more: TextView
    more = pill("Starsze powiadomienia", TEAL_LIGHT, LINK) {
        more.isEnabled = false
        more.text = "Ładuję…"
        io.execute {
            val result = runCatching { repository.notifications(page.nextUrl) }
            main.post {
                if (currentScreen != Screen.Notifications || !more.isAttachedToWindow) return@post
                result.onSuccess {
                    content.removeView(more)
                    addNotificationCards(it)
                }.onFailure {
                    more.isEnabled = true
                    more.text = "Starsze powiadomienia"
                    showError(it)
                }
            }
        }
    }
    content.addView(more, margin(top = 4, bottom = 12))
}

/** The forum's own sign-up form, field by field, so new fields or questions appear without an app update. */
internal fun MainActivity.renderSignUp(form: SignUpForm) {
    content.removeAllViews()
    val card = baseCard().apply { setPadding(dp(18), dp(20), dp(18), dp(18)) }
    card.addView(label("Załóż konto na f650gs.pl", 20f, INK, bold = true))
    card.addView(label("Forum działa przez nieszyfrowane HTTP — dane i hasło nie są chronione w drodze.", 12f, MUTED), margin(top = 6))
    val errorColor = Color.rgb(220, 50, 47)
    if (form.error.isNotBlank()) card.addView(label(form.error, 13f, errorColor, bold = true), margin(top = 12))
    val values = form.fields.map { field -> field to signUpInput(card, field, errorColor) }
    val error = label("", 13f, errorColor, bold = true).apply { visibility = View.GONE }
    card.addView(error, margin(top = 10))
    lateinit var submit: TextView
    submit = pill("Utwórz konto", ACCENT_FILL, WHITE) {
        val entered = values.associate { (field, value) -> field to value() }
        val missing = entered.filter { (field, value) -> field.required && value.isBlank() }.keys.map { if (it.label.length > 30) "pytanie zabezpieczające" else it.label }
        val passwords = entered.filterKeys { it.kind == "password" }.values.distinct()
        error.text = when {
            missing.isNotEmpty() -> "Uzupełnij: ${missing.joinToString(", ")}."
            passwords.size > 1 -> "Hasła różnią się."
            else -> ""
        }
        error.visibility = if (error.text.isEmpty()) View.GONE else View.VISIBLE
        if (error.text.isNotEmpty()) return@pill
        hideKeyboard(submit)
        submit.isEnabled = false
        submit.text = "Zakładam konto…"
        // Unticked boxes are left out; the hidden "0" next to each one stands for them.
        val post = entered.filter { (field, value) -> field.kind != "checkbox" || value == "1" }.mapKeys { it.key.name }
        io.execute {
            val result = runCatching { repository.signUp(form, post) }
            main.post {
                if (currentScreen != Screen.SignUp) return@post
                result.onSuccess { outcome ->
                    when (outcome) {
                        is SignUpResult.Retry -> renderSignUp(outcome.form.withEntered(entered))
                        is SignUpResult.Done -> signedUp(outcome)
                    }
                }.onFailure {
                    submit.isEnabled = true
                    submit.text = "Utwórz konto"
                    error.text = it.localizedMessage ?: "Nie udało się założyć konta."
                    error.visibility = View.VISIBLE
                }
            }
        }
    }
    card.addView(submit, margin(top = 14))
    content.addView(card)
}

/** Keeps what the member typed when the forum sends the form back (it never returns passwords). */
private fun SignUpForm.withEntered(entered: Map<SignUpField, String>): SignUpForm {
    val byName = entered.mapKeys { it.key.name }
    return copy(fields = fields.map { field ->
        if (field.kind == "checkbox" || field.value.isNotBlank()) field else field.copy(value = byName[field.name].orEmpty())
    })
}

/** Adds the label, input, description and error of [field] to [card]; returns a reader for its value. */
internal fun MainActivity.signUpInput(card: LinearLayout, field: SignUpField, errorColor: Int): () -> String {
    val title = field.label + if (field.required) " *" else ""
    val reader: () -> String
    if (field.kind == "checkbox") {
        val box = CheckBox(this).apply {
            text = title
            isChecked = field.value == "1"
            setTextColor(INK)
            buttonTintList = android.content.res.ColorStateList.valueOf(LINK)
        }
        card.addView(box, margin(top = 12))
        if (field.link.isNotBlank()) card.addView(label("Przeczytaj", 13f, LINK, bold = true).apply {
            setPadding(dp(32), dp(2), 0, dp(4))
            setOnClickListener { openForumPage(field.link, field.label) }
        })
        reader = { if (box.isChecked) "1" else "" }
    } else {
        card.addView(label(title, 13f, MUTED, bold = true), margin(top = 14))
        if (field.kind == "select") {
            var chosen = field.value
            val picker = label(field.options.firstOrNull { it.first == chosen }?.second ?: chosen, 16f, INK).apply {
                background = rounded(CANVAS, 14)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            picker.setOnClickListener {
                AlertDialog.Builder(this).setTitle(field.label).setItems(field.options.map { it.second }.toTypedArray()) { _, index ->
                    chosen = field.options[index].first
                    picker.text = field.options[index].second
                }.show()
            }
            card.addView(picker, margin(top = 6))
            reader = { chosen }
        } else {
            val input = EditText(this).apply {
                setText(field.value)
                textSize = 16f
                setTextColor(INK)
                setHintTextColor(MUTED)
                background = rounded(CANVAS, 14)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                inputType = when (field.kind) {
                    "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    "number", "tel" -> InputType.TYPE_CLASS_NUMBER
                    "textarea" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    else -> InputType.TYPE_CLASS_TEXT
                }
                if (field.kind != "textarea") setSingleLine(true) else minLines = 2
                if (field.maxLength > 0) filters = arrayOf(android.text.InputFilter.LengthFilter(field.maxLength))
            }
            card.addView(input, margin(top = 6))
            if (field.memberSearch) {
                val hits = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                card.addView(hits, margin(top = 2))
                memberSuggestions(input, hits) { member ->
                    input.setText(member.name)
                    input.setSelection(member.name.length)
                    hits.removeAllViews()
                }
            }
            reader = { input.text.toString().trim().takeIf { field.kind != "password" } ?: input.text.toString() }
        }
    }
    if (field.description.isNotBlank()) card.addView(label(field.description, 12f, MUTED), margin(top = 4))
    if (field.error.isNotBlank()) card.addView(label(field.error, 13f, errorColor, bold = true), margin(top = 4))
    return reader
}

private fun MainActivity.signedUp(outcome: SignUpResult.Done) {
    if (outcome.signedIn) finishLogin(outcome.cookies, remember = true)
    content.removeAllViews()
    val card = baseCard().apply { setPadding(dp(18), dp(20), dp(18), dp(18)) }
    card.addView(label("Konto założone", 20f, INK, bold = true))
    card.addView(label(outcome.message, 14f, INK), margin(top = 8))
    if (!outcome.signedIn) card.addView(pill("Zaloguj się", ACCENT_FILL, WHITE) { openLogin() }, margin(top = 14))
    content.addView(card)
}

/** Forum notification settings as switches; each change is saved shortly after the last tap. */
internal fun MainActivity.renderNotificationSettings(form: SettingsForm) {
    content.removeAllViews()
    val checked = form.sections.flatMap { it.rows }.flatMap { it.toggles }.associateWith { it.checked }.toMutableMap()
    val save = Runnable {
        val on = checked.filterValues { it }.keys.toList()
        io.execute {
            val result = runCatching { repository.saveNotificationSettings(form, on) }
            main.post {
                result.onSuccess { android.widget.Toast.makeText(this, "Zapisano", android.widget.Toast.LENGTH_SHORT).show() }
                    .onFailure { showError(it) }
            }
        }
    }
    fun changed() { main.removeCallbacks(save); main.postDelayed(save, 800) }
    content.addView(label("Te ustawienia są zapisywane na forum; dotyczą też strony w przeglądarce.", 12f, MUTED), margin(top = 2, bottom = 4))
    form.sections.forEach { section ->
        if (section.title.isNotBlank()) content.addView(label(section.title.uppercase(), 11f, LINK, bold = true).apply {
            letterSpacing = 0.08f
        }, margin(top = 14, bottom = 8))
        val radios = section.rows.flatMap { it.toggles }.filter { it.radio }
        if (radios.isNotEmpty()) {
            // One card with the choices, like a preference list.
            val group = android.widget.RadioGroup(this)
            section.rows.forEach { choice ->
                val toggle = choice.toggles.first()
                group.addView(android.widget.RadioButton(this).apply {
                    id = View.generateViewId()
                    text = choice.title
                    setTextColor(INK)
                    textSize = 15f
                    buttonTintList = android.content.res.ColorStateList.valueOf(LINK)
                    isChecked = toggle.checked
                    isEnabled = toggle.enabled
                    setPadding(dp(6), dp(8), 0, dp(8))
                    setOnCheckedChangeListener { _, on ->
                        if (!on) return@setOnCheckedChangeListener
                        radios.filter { it.name == toggle.name }.forEach { checked[it] = it == toggle }
                        changed()
                    }
                })
            }
            content.addView(baseCard().apply { addView(group) })
            return@forEach
        }
        section.rows.forEach { setting ->
            content.addView(baseCard().apply {
                if (setting.toggles.size == 1) {
                    addView(row {
                        val text = LinearLayout(this@renderNotificationSettings).apply { orientation = LinearLayout.VERTICAL }
                        text.addView(label(setting.title, 15f, INK, bold = true))
                        if (setting.description.isNotBlank()) text.addView(label(setting.description, 12f, MUTED), margin(top = 2))
                        addView(text, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(settingSwitch(setting.toggles.first(), checked, ::changed))
                    })
                } else {
                    // Matrix row: the event, then one switch per channel ("Powiadomienie", "E-mail").
                    addView(label(setting.title, 15f, INK, bold = true))
                    if (setting.toggles.none { it.enabled }) addView(label("Ustawione przez administratora", 12f, MUTED), margin(top = 2))
                    addView(row {
                        setting.toggles.forEach { toggle ->
                            addView(label(if (toggle.label == "Powiadomienie") "W aplikacji" else toggle.label, 13f, MUTED, bold = true),
                                LinearLayout.LayoutParams(0, -2, 1f))
                            addView(settingSwitch(toggle, checked, ::changed), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
                        }
                    }, margin(top = 6))
                }
            })
        }
    }
}

private fun MainActivity.settingSwitch(toggle: FormToggle, checked: MutableMap<FormToggle, Boolean>, changed: () -> Unit) =
    android.widget.Switch(this).apply {
        isChecked = toggle.checked
        isEnabled = toggle.enabled
        styleSwitch(this)
        setOnCheckedChangeListener { _, value -> checked[toggle] = value; changed() }
    }

