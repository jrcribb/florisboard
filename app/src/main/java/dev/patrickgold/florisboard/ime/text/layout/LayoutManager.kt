/*
 * Copyright (C) 2020 Patrick Goldinger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.text.layout

import dev.patrickgold.florisboard.ime.core.PrefHelper
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.extension.AssetManager
import dev.patrickgold.florisboard.ime.extension.AssetRef
import dev.patrickgold.florisboard.ime.extension.AssetSource
import dev.patrickgold.florisboard.ime.popup.PopupExtension
import dev.patrickgold.florisboard.ime.popup.PopupManager
import dev.patrickgold.florisboard.ime.text.key.*
import dev.patrickgold.florisboard.ime.text.keyboard.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

private data class LTN(
    val type: LayoutType,
    val name: String
)

private data class KMS(
    val keyboardMode: KeyboardMode,
    val subtype: Subtype
)

/**
 * Class which manages layout loading and caching.
 */
class LayoutManager {
    private val assetManager: AssetManager
        get() = AssetManager.default()

    private val layoutCache: HashMap<LTN, Deferred<Result<Layout>>> = hashMapOf()
    private val layoutCacheGuard: Mutex = Mutex(locked = false)
    private val extendedPopupsCache: HashMap<LTN, Deferred<Result<Layout>>> = hashMapOf()
    private val extendedPopupsCacheGuard: Mutex = Mutex(locked = false)
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val indexedLayoutRefs: EnumMap<LayoutType, MutableList<Pair<AssetRef, LayoutMetaOnly>>> = EnumMap(LayoutType::class.java)

    init {
        for (type in LayoutType.values()) {
            indexedLayoutRefs[type] = mutableListOf()
        }
        indexLayoutRefs()
    }

    /**
     * Loads the layout for the specified type and name.
     *
     * @return the [Layout] or null.
     */
    private fun loadLayoutAsync(ltn: LTN?): Deferred<Result<Layout>> = ioScope.async {
        if (ltn == null) {
            return@async Result.failure(NullPointerException("Invalid argument value for 'ltn': null"))
        }
        layoutCacheGuard.lock()
        val cached = layoutCache[ltn]
        if (cached != null) {
            layoutCacheGuard.unlock()
            return@async cached.await()
        } else {
            val layout = loadLayoutInternalAsync(ltn)
            layoutCache[ltn] = layout
            layoutCacheGuard.unlock()
            return@async layout.await()
        }
    }

    /**
     * Loads the layout for the specified type and name.
     *
     * @return the [Layout] or null.
     */
    private fun loadLayoutInternalAsync(ltn: LTN): Deferred<Result<Layout>> = ioScope.async {
        return@async assetManager.loadJsonAsset(
            ref = AssetRef(source = AssetSource.Assets, path = "ime/text/${ltn.type}/${ltn.name}.json")
        )
    }

    private fun loadExtendedPopups(subtype: Subtype? = null): PopupExtension {
        val langTagRef = AssetRef(
            source = AssetSource.Assets,
            path = PopupManager.POPUP_EXTENSION_PATH_REL + "/" + (subtype?.locale?.toLanguageTag() ?: "\$default") + ".json"
        )
        val langRef = AssetRef(
            source = AssetSource.Assets,
            path = PopupManager.POPUP_EXTENSION_PATH_REL + "/" + (subtype?.locale?.language ?: "\$default") + ".json"
        )
        return assetManager.loadJsonAsset<PopupExtension>(langTagRef).getOrElse {
            assetManager.loadJsonAsset<PopupExtension>(langRef).getOrElse {
                PopupExtension.empty()
            }
        }
    }

    /**
     * Merges the specified layouts (LTNs) and returns the computed layout.
     * The computed layout may looks like this:
     *   e e e e e e e e e e      e = extension
     *   c c c c c c c c c c      c = main
     *    c c c c c c c c c       m = mod
     *   m c c c c c c c c m
     *   m m m m m m m m m m
     *
     * @param keyboardMode The keyboard mode for the returning [TextKeyboard].
     * @param subtype The subtype used for populating the extended popups.
     * @param main The main layout type and name.
     * @param modifier The modifier (mod) layout type and name.
     * @param extension The extension layout type and name.
     * @return a [TextKeyboard] object, regardless of the specified LTNs or errors.
     */
    private suspend fun mergeLayoutsAsync(
        keyboardMode: KeyboardMode,
        subtype: Subtype,
        main: LTN? = null,
        modifier: LTN? = null,
        extension: LTN? = null,
        prefs: PrefHelper,
        currencySet: CurrencySet
    ): TextKeyboard {
        val mainLayout = loadLayoutAsync(main).await().getOrNull()
        val modifierToLoad = if (mainLayout?.modifier != null) {
            LTN(LayoutType.CHARACTERS_MOD, mainLayout.modifier)
        } else {
            modifier
        }
        val modifierLayout = loadLayoutAsync(modifierToLoad).await().getOrNull()
        val extensionLayout = loadLayoutAsync(extension).await().getOrNull()

        val computedArrangement: ArrayList<Array<TextKey>> = arrayListOf()

        if (extensionLayout != null) {
            for (row in extensionLayout.arrangement) {
                val rowArray = Array(row.size) { TextKey(row[it]) }
                computedArrangement.add(rowArray)
            }
        }

        if (mainLayout != null && modifierLayout != null) {
            for (mainRowI in mainLayout.arrangement.indices) {
                val mainRow = mainLayout.arrangement[mainRowI]
                if (mainRowI + 1 < mainLayout.arrangement.size) {
                    val rowArray = Array(mainRow.size) { TextKey(mainRow[it]) }
                    computedArrangement.add(rowArray)
                } else {
                    // merge main and mod here
                    val rowArray = arrayListOf<TextKey>()
                    val firstModRow = modifierLayout.arrangement.firstOrNull()
                    for (modKey in (firstModRow ?: arrayOf())) {
                        if (modKey is TextKeyData && modKey.code == 0 || modKey is PopupAwareTextKeyData && modKey.code == 0) {
                            rowArray.addAll(mainRow.map { TextKey(it) })
                        } else {
                            rowArray.add(TextKey(modKey))
                        }
                    }
                    val temp = Array(rowArray.size) { rowArray[it] }
                    computedArrangement.add(temp)
                }
            }
            for (modRowI in 1 until modifierLayout.arrangement.size) {
                val modRow = modifierLayout.arrangement[modRowI]
                val rowArray = Array(modRow.size) { TextKey(modRow[it]) }
                computedArrangement.add(rowArray)
            }
        } else if (mainLayout != null && modifierLayout == null) {
            for (mainRow in mainLayout.arrangement) {
                val rowArray = Array(mainRow.size) { TextKey(mainRow[it]) }
                computedArrangement.add(rowArray)
            }
        } else if (mainLayout == null && modifierLayout != null) {
            for (modRow in modifierLayout.arrangement) {
                val rowArray = Array(modRow.size) { TextKey(modRow[it]) }
                computedArrangement.add(rowArray)
            }
        }

        /*// Add popup to keys
        if (keyboardMode == KeyboardMode.CHARACTERS || keyboardMode == KeyboardMode.NUMERIC_ADVANCED ||
            keyboardMode == KeyboardMode.SYMBOLS || keyboardMode == KeyboardMode.SYMBOLS2) {
            val extendedPopupsDefault = loadExtendedPopups()
            val extendedPopups = loadExtendedPopups(subtype)
            for (row in computedArrangement) {
                var kOffset = 0
                for ((k, key) in row.withIndex()) {
                    if (CurrencySet.isCurrencySlot(key.code)) {
                        val newKey = currencySet.getSlot(key.code) ?: KeyData.UNSPECIFIED
                        key.type = newKey.type
                        key.code = newKey.code
                        key.label = newKey.label
                    }
                    val lastKey = row.getOrNull(k - 1)
                    if (lastKey != null && lastKey.groupId == key.groupId && key.groupId != FlorisKeyData.GROUP_DEFAULT) {
                        kOffset++
                    }
                    val label = when (key.groupId) {
                        FlorisKeyData.GROUP_ENTER -> {
                            "~enter"
                        }
                        FlorisKeyData.GROUP_LEFT -> {
                            "~left"
                        }
                        FlorisKeyData.GROUP_RIGHT -> {
                            "~right"
                        }
                        else -> {
                            key.label
                        }
                    }
                    var popupSet: PopupSet<KeyData>? = null
                    val kv = key.variation
                    if (popupSet == null && kv == KeyVariation.PASSWORD) {
                        popupSet = extendedPopups.mapping[KeyVariation.PASSWORD]?.get(label) ?:
                                extendedPopupsDefault.mapping[KeyVariation.PASSWORD]?.get(label)
                    }
                    if (popupSet == null && (kv == KeyVariation.NORMAL || kv == KeyVariation.PASSWORD)) {
                        popupSet = extendedPopups.mapping[KeyVariation.NORMAL]?.get(label) ?:
                                extendedPopupsDefault.mapping[KeyVariation.NORMAL]?.get(label)
                    }
                    if (popupSet == null && kv == KeyVariation.EMAIL_ADDRESS) {
                        popupSet = extendedPopups.mapping[KeyVariation.EMAIL_ADDRESS]?.get(label) ?:
                                extendedPopupsDefault.mapping[KeyVariation.EMAIL_ADDRESS]?.get(label)
                    }
                    if (popupSet == null && (kv == KeyVariation.EMAIL_ADDRESS || kv == KeyVariation.URI)) {
                        popupSet = extendedPopups.mapping[KeyVariation.URI]?.get(label) ?:
                                extendedPopupsDefault.mapping[KeyVariation.URI]?.get(label)
                    }
                    if (popupSet == null) {
                        popupSet = extendedPopups.mapping[KeyVariation.ALL]?.get(label) ?:
                                extendedPopupsDefault.mapping[KeyVariation.ALL]?.get(label)
                    }
                    var keySpecificPopupSet: PopupSet<KeyData>? = null
                    if (label != key.label) {
                        keySpecificPopupSet = extendedPopups.mapping[KeyVariation.ALL]?.get(key.label) ?:
                            extendedPopupsDefault.mapping[KeyVariation.ALL]?.get(key.label)
                    }
                    key.popup.apply {
                        keySpecificPopupSet?.let { merge(it) }
                        popupSet?.let { merge(it) }
                    }
                    key.popup.forEach { popupKey ->
                        if (CurrencySet.isCurrencySlot(popupKey.code)) {
                            val newKey = currencySet.getSlot(popupKey.code) ?: KeyData.UNSPECIFIED
                            popupKey.type = newKey.type
                            popupKey.code = newKey.code
                            popupKey.label = newKey.label
                        }
                    }
                }
            }
        }*/

        /*// Add hints to keys
        if (keyboardMode == KeyboardMode.CHARACTERS) {
            val symbolsComputedArrangement = fetchComputedLayoutAsync(KeyboardMode.SYMBOLS, subtype, prefs, currencySet).await().arrangement
            val minRow = if (prefs.keyboard.numberRow) { 1 } else { 0 }
            for ((r, row) in computedArrangement.withIndex()) {
                if (r >= (3 + minRow) || r < minRow) {
                    continue
                }
                var kOffset = 0
                val symbolRow = symbolsComputedArrangement.getOrNull(r - minRow)
                if (symbolRow != null) {
                    for ((k, key) in row.withIndex()) {
                        val lastKey = row.getOrNull(k - 1)
                        if (lastKey != null && lastKey.groupId == key.groupId && key.groupId != FlorisKeyData.GROUP_DEFAULT) {
                            kOffset++
                        }
                        val symbol = symbolRow.getOrNull(k - kOffset)
                        if (r == minRow && key.type == KeyType.CHARACTER && symbol?.type == KeyType.NUMERIC) {
                            key.popup.hint = symbol
                        } else if (r > minRow && key.type == KeyType.CHARACTER && symbol?.type == KeyType.CHARACTER) {
                            key.popup.hint = symbol
                        }
                    }
                }
            }
        }*/

        val array = Array(computedArrangement.size) { computedArrangement[it] }
        return TextKeyboard(
            arrangement = array,
            mode = keyboardMode
        )
    }

    /**
     * Computes a layout for [keyboardMode] based on the given [subtype] and returns it.
     *
     * @param keyboardMode The keyboard mode for which the layout should be computed.
     * @param subtype The subtype which localizes the computed layout.
     */
    fun computeKeyboardAsync(
        keyboardMode: KeyboardMode,
        subtype: Subtype,
        prefs: PrefHelper,
        currencySet: CurrencySet
    ): Deferred<TextKeyboard> = ioScope.async {
        var main: LTN? = null
        var modifier: LTN? = null
        var extension: LTN? = null

        when (keyboardMode) {
            KeyboardMode.CHARACTERS -> {
                if (prefs.keyboard.numberRow) {
                    extension = LTN(LayoutType.NUMERIC_ROW, subtype.layoutMap.numericRow)
                }
                main = LTN(LayoutType.CHARACTERS, subtype.layoutMap.characters)
                modifier = LTN(LayoutType.CHARACTERS_MOD, "\$default")
            }
            KeyboardMode.EDITING -> {
                // Layout for this mode is defined in custom layout xml file.
            }
            KeyboardMode.NUMERIC -> {
                main = LTN(LayoutType.NUMERIC, subtype.layoutMap.numeric)
            }
            KeyboardMode.NUMERIC_ADVANCED -> {
                main = LTN(LayoutType.NUMERIC_ADVANCED, subtype.layoutMap.numericAdvanced)
            }
            KeyboardMode.PHONE -> {
                main = LTN(LayoutType.PHONE, subtype.layoutMap.phone)
            }
            KeyboardMode.PHONE2 -> {
                main = LTN(LayoutType.PHONE2, subtype.layoutMap.phone2)
            }
            KeyboardMode.SYMBOLS -> {
                extension = LTN(LayoutType.NUMERIC_ROW, subtype.layoutMap.numericRow)
                main = LTN(LayoutType.SYMBOLS, subtype.layoutMap.symbols)
                modifier = LTN(LayoutType.SYMBOLS_MOD, "\$default")
            }
            KeyboardMode.SYMBOLS2 -> {
                main = LTN(LayoutType.SYMBOLS2, subtype.layoutMap.symbols2)
                modifier = LTN(LayoutType.SYMBOLS2_MOD, "\$default")
            }
            KeyboardMode.SMARTBAR_CLIPBOARD_CURSOR_ROW -> {
                extension = LTN(LayoutType.EXTENSION, "clipboard_cursor_row")
            }
            KeyboardMode.SMARTBAR_NUMBER_ROW -> {
                extension = LTN(LayoutType.NUMERIC_ROW, subtype.layoutMap.numericRow)
            }
        }

        return@async mergeLayoutsAsync(keyboardMode, subtype, main, modifier, extension, prefs, currencySet)
    }

    /**
     * Called when the application is destroyed. Used to cancel any pending coroutines.
     */
    fun onDestroy() {
        ioScope.cancel()
    }

    fun getMetaFor(type: LayoutType, name: String): LayoutMetaOnly? {
        return indexedLayoutRefs[type]?.first { it.second.name == name }?.second
    }

    fun getMetaNameListFor(type: LayoutType): List<String> {
        return indexedLayoutRefs[type]?.map { it.second.name } ?: listOf()
    }

    fun getMetaLabelListFor(type: LayoutType): List<String> {
        return indexedLayoutRefs[type]?.map { it.second.label } ?: listOf()
    }

    private fun indexLayoutRefs() {
        for (type in LayoutType.values()) {
            indexedLayoutRefs[type]?.clear()
            assetManager.listAssets<LayoutMetaOnly>(
                AssetRef(AssetSource.Assets, "ime/text/$type")
            ).onSuccess { assetList ->
                indexedLayoutRefs[type]?.let { indexedList ->
                    for ((ref, layoutMeta) in assetList) {
                        indexedList.add(Pair(ref, layoutMeta))
                    }
                    indexedList.sortBy { it.second.name }
                    if (type == LayoutType.CHARACTERS) {
                        // Move selected layouts to the top of the list
                        for (layoutName in listOf("azerty", "qwertz", "qwerty")) {
                            val index: Int = indexedList.indexOfFirst { it.second.name == layoutName }
                            if (index > 0) {
                                indexedList.add(0, indexedList.removeAt(index))
                            }
                        }
                    }
                }
            }
        }
    }
}
