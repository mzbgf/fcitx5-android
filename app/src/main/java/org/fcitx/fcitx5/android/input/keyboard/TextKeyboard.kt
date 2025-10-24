/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.annotation.Keep
import androidx.core.view.allViews
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.popup.PopupAction
import splitties.views.imageResource
import java.io.File
import kotlinx.serialization.json.*
import org.fcitx.fcitx5.android.utils.appContext
import kotlinx.serialization.Serializable

object DisplayTextResolver {
    fun resolve(
        displayText: JsonElement?,
        subModeLabel: String,
        default: String
    ): String {
        return when {
            displayText == null -> default
            displayText is JsonPrimitive -> displayText.content
            displayText is JsonObject -> resolveMap(displayText, subModeLabel) ?: default
            else -> default
        }
    }

    private fun resolveMap(
        map: JsonObject,
        subModeLabel: String
    ): String? {
        // 直接匹配子模式标签
        return map[subModeLabel]?.jsonPrimitive?.content
            ?: map[""]?.jsonPrimitive?.content
    }
}

@SuppressLint("ViewConstructor")
class TextKeyboard(
    context: Context,
    theme: Theme
) : BaseKeyboard(context, theme, ::Layout) {

    enum class CapsState { None, Once, Lock }

    companion object {
        const val Name = "Text"
        private var lastModified = 0L
        var ime: InputMethodEntry? = null

        @Serializable
        data class KeyJson(
            val type: String,
            val main: String? = null,
            val alt: String? = null,
            val displayText: JsonElement? = null,
            val label: String? = null,
            val subLabel: String? = null,
            val weight: Float? = null
        )
        var cachedLayoutJsonMap: Map<String, List<List<KeyJson>>>? = null

        val textLayoutJsonMap: Map<String, List<List<KeyJson>>>?
            @Synchronized
            get() {
                val file = File(appContext.getExternalFilesDir(null), "config/TextKeyboardLayout.json")
                if (!file.exists()) {
                    cachedLayoutJsonMap = null
                    return null
                }
                if (cachedLayoutJsonMap == null || file.lastModified() != lastModified) {
                    try {
                        lastModified = file.lastModified()
                        val json = file.readText()
                        cachedLayoutJsonMap = Json.decodeFromString<Map<String, List<List<KeyJson>>>>(json)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        cachedLayoutJsonMap = null
                    }
                }
                return cachedLayoutJsonMap
            }

        private fun getTextLayoutJsonForIme(displayName: String): List<List<KeyJson>>? {
            val map = textLayoutJsonMap ?: return null
            return map[displayName] ?: null
        }

        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                AlphabetKey("Q", "1"),
                AlphabetKey("W", "2"),
                AlphabetKey("E", "3"),
                AlphabetKey("R", "4"),
                AlphabetKey("T", "5"),
                AlphabetKey("Y", "6"),
                AlphabetKey("U", "7"),
                AlphabetKey("I", "8"),
                AlphabetKey("O", "9"),
                AlphabetKey("P", "0")
            ),
            listOf(
                AlphabetKey("A", "@"),
                AlphabetKey("S", "*"),
                AlphabetKey("D", "+"),
                AlphabetKey("F", "-"),
                AlphabetKey("G", "="),
                AlphabetKey("H", "/"),
                AlphabetKey("J", "#"),
                AlphabetKey("K", "("),
                AlphabetKey("L", ")")
            ),
            listOf(
                CapsKey(),
                AlphabetKey("Z", "'"),
                AlphabetKey("X", ":"),
                AlphabetKey("C", "\""),
                AlphabetKey("V", "?"),
                AlphabetKey("B", "!"),
                AlphabetKey("N", "~"),
                AlphabetKey("M", "\\"),
                BackspaceKey()
            ),
            listOf(
                LayoutSwitchKey("?123", ""),
                CommaKey(0.1f, KeyDef.Appearance.Variant.Alternative),
                LanguageKey(),
                SpaceKey(),
                SymbolKey(".", 0.1f, KeyDef.Appearance.Variant.Alternative),
                ReturnKey()
            )
        )
    }

    val caps: ImageKeyView by lazy { findViewById(R.id.button_caps) }
    val backspace: ImageKeyView by lazy { findViewById(R.id.button_backspace) }
    val quickphrase: ImageKeyView by lazy { findViewById(R.id.button_quickphrase) }
    val lang: ImageKeyView by lazy { findViewById(R.id.button_lang) }
    val space: TextKeyView by lazy { findViewById(R.id.button_space) }
    val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        updateLangSwitchKey(v)
    }

    private val keepLettersUppercase by AppPrefs.getInstance().keyboard.keepLettersUppercase

    init {
    }

    private val textKeys: List<TextKeyView> by lazy {
        allViews.filterIsInstance(TextKeyView::class.java).toList()
    }

    private var capsState: CapsState = CapsState.None

    private fun transformAlphabet(c: String): String {
        return when (capsState) {
            CapsState.None -> c.lowercase()
            else -> c.uppercase()
        }
    }

    private var punctuationMapping: Map<String, String> = mapOf()
    private fun transformPunctuation(p: String) = punctuationMapping.getOrDefault(p, p)

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        var transformed = action
        when (action) {
            is KeyAction.FcitxKeyAction -> when (source) {
                KeyActionListener.Source.Keyboard -> {
                    when (capsState) {
                        CapsState.None -> {
                            transformed = action.copy(act = action.act.lowercase())
                        }
                        CapsState.Once -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.Shift)
                            )
                            switchCapsState()
                        }
                        CapsState.Lock -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.CapsLock)
                            )
                        }
                    }
                }
                KeyActionListener.Source.Popup -> {
                    if (capsState == CapsState.Once) {
                        switchCapsState()
                    }
                }
            }
            is KeyAction.CapsAction -> switchCapsState(action.lock)
            else -> {}
        }
        super.onAction(transformed, source)
    }

    override fun onAttach() {
        capsState = CapsState.None
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateLangSwitchKey(showLangSwitchKey.getValue())
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.imageResource = returnDrawable
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        updatePunctuationKeys()
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        // update ime of companion object ime
        TextKeyboard.ime = ime
        updateAlphabetKeys()
        space.mainText.text = buildString {
            append(ime.displayName)
            ime.subMode.run { label.ifEmpty { name.ifEmpty { null } } }?.let { append(" ($it)") }
        }
        if (capsState != CapsState.None) {
            switchCapsState()
        }
    }

    private fun transformPopupPreview(c: String): String {
        if (c.length != 1) return c
        if (c[0].isLetter()) return transformAlphabet(c)
        return transformPunctuation(c)
    }

    override fun onPopupAction(action: PopupAction) {
        val newAction = when (action) {
            is PopupAction.PreviewAction -> action.copy(content = transformPopupPreview(action.content))
            is PopupAction.PreviewUpdateAction -> action.copy(content = transformPopupPreview(action.content))
            is PopupAction.ShowKeyboardAction -> {
                val label = action.keyboard.label
                if (label.length == 1 && label[0].isLetter())
                    action.copy(keyboard = KeyDef.Popup.Keyboard(transformAlphabet(label)))
                else action
            }
            else -> action
        }
        super.onPopupAction(newAction)
    }

    private fun switchCapsState(lock: Boolean = false) {
        capsState =
            if (lock) {
                when (capsState) {
                    CapsState.Lock -> CapsState.None
                    else -> CapsState.Lock
                }
            } else {
                when (capsState) {
                    CapsState.None -> CapsState.Once
                    else -> CapsState.None
                }
            }
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    private fun updateCapsButtonIcon() {
        caps.img.apply {
            imageResource = when (capsState) {
                CapsState.None -> R.drawable.ic_capslock_none
                CapsState.Once -> R.drawable.ic_capslock_once
                CapsState.Lock -> R.drawable.ic_capslock_lock
            }
        }
    }

    private fun updateLangSwitchKey(visible: Boolean) {
        lang.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateAlphabetKeys() {
        val layoutJson = getTextLayoutJsonForIme(ime?.uniqueName ?: "default")
        if (layoutJson != null) {
            textKeys.forEach {
                if (it.def !is KeyDef.Appearance.AltText) return@forEach
                val keyJson = layoutJson.flatten().find { key -> key.main == it.def.character }
                val displayText = if (keyJson != null ) {
                  DisplayTextResolver.resolve(
                    keyJson.displayText,
                    ime?.subMode?.label ?: "",
                    keyJson.main ?: ""
                  )
                } else {
                  it.def.character
                }
                // val displayText = keyJson?.displayText ?: keyJson?.main ?: it.def.character

                it.mainText.text = displayText?.let { str ->
                    if (keepLettersUppercase) {
                      keyJson?.main?.uppercase() ?: str.uppercase()
                    } else {
                      when(capsState) {
                        CapsState.None -> displayText.lowercase() ?: keyJson?.main?.lowercase() ?: str.lowercase()
                        else -> keyJson?.main?.uppercase() ?: str.uppercase()
                      }
                    }
                } ?: it.def.character
            }
        } else {
            textKeys.forEach {
                if (it.def !is KeyDef.Appearance.AltText) return
                it.mainText.text = it.def.displayText.let { str ->
                    if (str.length != 1 || !str[0].isLetter()) return@forEach
                    if (keepLettersUppercase) str.uppercase() else transformAlphabet(str)
                }
            }
        }
    }

    private fun updatePunctuationKeys() {
        val layoutJson = getTextLayoutJsonForIme(ime?.uniqueName ?: "default")
        if (layoutJson != null) {
            textKeys.forEach {
                if (it is AltTextKeyView) {
                    it.def as KeyDef.Appearance.AltText
                    val keyJson = layoutJson.flatten().find { key -> key.main == it.def.character }
                    val altText = keyJson?.alt ?: it.def.character
                    it.altText.text = transformPunctuation(altText)
                    it.mainText.text = it.altText.text
                    it.def.displayText = it.altText.text
                } else {
                    it.def as KeyDef.Appearance.Text
                    it.mainText.text = it.def.displayText.let { str ->
                        if (str[0].run { isLetter() || isWhitespace() }) return@forEach
                        transformPunctuation(str)
                    }
                }
            }
        } else {
            textKeys.forEach {
                if (it is AltTextKeyView) {
                    it.def as KeyDef.Appearance.AltText
                    it.altText.text = transformPunctuation(it.def.altText)
                } else {
                    it.def as KeyDef.Appearance.Text
                    it.mainText.text = it.def.displayText.let { str ->
                        if (str[0].run { isLetter() || isWhitespace() }) return@forEach
                        transformPunctuation(str)
                    }
                }
            }
        }
    }

}
