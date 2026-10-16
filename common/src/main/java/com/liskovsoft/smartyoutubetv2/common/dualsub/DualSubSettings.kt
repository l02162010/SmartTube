package com.liskovsoft.smartyoutubetv2.common.dualsub

import android.content.Context
import com.liskovsoft.smartyoutubetv2.common.R
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog

/**
 * Dual subtitle rows of the subtitle settings dialog.
 */
object DualSubSettings {
    @JvmStatic
    fun append(context: Context, settingsPresenter: AppDialogPresenter) {
        val data = DualSubData.instance(context)

        settingsPresenter.appendSingleSwitch(UiOptionItem.from(context.getString(R.string.dualsub_enable),
            { option -> data.isEnabled = option.isSelected }, data.isEnabled))

        settingsPresenter.appendRadioCategory(context.getString(R.string.dualsub_target_lang),
            DualSubData.TARGET_LANGS.map { lang ->
                UiOptionItem.from(DualSubData.langTitle(lang), { data.targetLang = lang }, lang == data.targetLang)
            })

        settingsPresenter.appendSingleSwitch(UiOptionItem.from(context.getString(R.string.dualsub_prefer_human),
            { option -> data.isPreferHumanTranslation = option.isSelected }, data.isPreferHumanTranslation))

        settingsPresenter.appendSingleSwitch(UiOptionItem.from(context.getString(R.string.dualsub_show_original),
            { option -> data.isShowOriginal = option.isSelected }, data.isShowOriginal))

        val sourceOptions = ArrayList<OptionItem>()
        sourceOptions.add(UiOptionItem.from(context.getString(R.string.dualsub_source_auto), { data.sourceLang = null }, data.sourceLang == null))
        DualSubData.SOURCE_LANGS.mapTo(sourceOptions) { lang ->
            UiOptionItem.from(DualSubData.langTitle(lang), { data.sourceLang = lang }, lang == data.sourceLang)
        }
        settingsPresenter.appendRadioCategory(context.getString(R.string.dualsub_source_lang), sourceOptions)

        val modelTitles = listOf(context.getString(R.string.dualsub_model_haiku), context.getString(R.string.dualsub_model_sonnet))
        settingsPresenter.appendRadioCategory(context.getString(R.string.dualsub_model),
            DualSubData.MODELS.mapIndexed { i, model ->
                UiOptionItem.from(modelTitles[i], { data.model = model }, model == data.model)
            })

        val keyState = data.maskedApiKey.ifEmpty { context.getString(R.string.dualsub_api_key_not_set) }
        settingsPresenter.appendSingleButton(UiOptionItem.from(context.getString(R.string.dualsub_api_key), keyState) {
            settingsPresenter.closeDialog()
            // Tip: type with the Google TV phone remote keyboard, or `adb shell input text '<key>'`
            SimpleEditDialog.showPassword(context, context.getString(R.string.dualsub_api_key), null) { newValue ->
                data.apiKey = newValue ?: ""
                true
            }
        })
    }
}
