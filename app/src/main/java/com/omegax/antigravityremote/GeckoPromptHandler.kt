package com.omegax.antigravityremote

import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AlertPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BeforeUnloadPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ButtonPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSession.PromptDelegate.SharePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.TextPrompt

class GeckoPromptHandler(
    private val activity: AppCompatActivity,
    private val launchFilePicker: (
        allowMultiple: Boolean,
        mimeTypes: Array<String>,
        callback: (List<Uri>) -> Unit
    ) -> Unit
) : PromptDelegate {

    private fun isActivityUsable(): Boolean = !activity.isFinishing && !activity.isDestroyed

    override fun onFilePrompt(
        session: GeckoSession,
        prompt: FilePrompt
    ): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        if (!isActivityUsable()) {
            result.complete(prompt.dismiss())
            return result
        }
        val allowMultiple = prompt.type == FilePrompt.Type.MULTIPLE
        val normalizedMimes = normalizeMimeTypes(prompt.mimeTypes)

        launchFilePicker(allowMultiple, normalizedMimes) { uris ->
            if (uris.isEmpty()) {
                result.complete(prompt.dismiss())
            } else if (allowMultiple) {
                result.complete(prompt.confirm(activity, uris.toTypedArray()))
            } else {
                result.complete(prompt.confirm(activity, uris.first()))
            }
        }
        return result
    }

    override fun onAlertPrompt(
        session: GeckoSession,
        prompt: AlertPrompt
    ): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        activity.runOnUiThread {
            if (!isActivityUsable()) {
                result.complete(prompt.dismiss())
                return@runOnUiThread
            }
            val safeTitle = SecurityPolicy.sanitizePromptText(prompt.title, 120)
                .ifBlank { "Antigravity" }
            val safeMsg = SecurityPolicy.sanitizePromptText(prompt.message)
            AlertDialog.Builder(activity, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                .setTitle(safeTitle)
                .setMessage(safeMsg)
                .setPositiveButton("Aceptar") { _, _ ->
                    result.complete(prompt.dismiss())
                }
                .setOnCancelListener {
                    result.complete(prompt.dismiss())
                }
                .show()
        }
        return result
    }

    override fun onBeforeUnloadPrompt(
        session: GeckoSession,
        prompt: BeforeUnloadPrompt
    ): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        activity.runOnUiThread {
            if (!isActivityUsable()) {
                result.complete(prompt.dismiss())
                return@runOnUiThread
            }
            AlertDialog.Builder(activity, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                .setTitle("¿Salir de esta página?")
                .setMessage("Es posible que los cambios sin guardar se pierdan.")
                .setPositiveButton("Salir") { _, _ ->
                    result.complete(prompt.confirm(AllowOrDeny.ALLOW))
                }
                .setNegativeButton("Quedarse") { _, _ ->
                    result.complete(prompt.confirm(AllowOrDeny.DENY))
                }
                .setOnCancelListener {
                    result.complete(prompt.confirm(AllowOrDeny.DENY))
                }
                .show()
        }
        return result
    }

    override fun onButtonPrompt(
        session: GeckoSession,
        prompt: ButtonPrompt
    ): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        activity.runOnUiThread {
            if (!isActivityUsable()) {
                result.complete(prompt.dismiss())
                return@runOnUiThread
            }
            val safeTitle = SecurityPolicy.sanitizePromptText(prompt.title, 120)
                .ifBlank { "Confirmar" }
            val safeMsg = SecurityPolicy.sanitizePromptText(prompt.message)
            AlertDialog.Builder(activity, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                .setTitle(safeTitle)
                .setMessage(safeMsg)
                .setPositiveButton("Aceptar") { _, _ ->
                    result.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE))
                }
                .setNegativeButton("Cancelar") { _, _ ->
                    result.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE))
                }
                .setOnCancelListener {
                    result.complete(prompt.dismiss())
                }
                .show()
        }
        return result
    }

    override fun onTextPrompt(
        session: GeckoSession,
        prompt: TextPrompt
    ): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        activity.runOnUiThread {
            if (!isActivityUsable()) {
                result.complete(prompt.dismiss())
                return@runOnUiThread
            }
            val container = FrameLayout(activity).apply {
                val pad = (20 * activity.resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
            }
            val editText = EditText(activity).apply {
                val initial = SecurityPolicy.sanitizePromptText(prompt.defaultValue, 1000)
                setText(initial)
                setSelection(text.length)
            }
            container.addView(editText)

            val safeTitle = SecurityPolicy.sanitizePromptText(prompt.title, 120)
                .ifBlank { "Introducir texto" }
            val safeMsg = SecurityPolicy.sanitizePromptText(prompt.message)

            AlertDialog.Builder(activity, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                .setTitle(safeTitle)
                .setMessage(safeMsg)
                .setView(container)
                .setPositiveButton("Aceptar") { _, _ ->
                    result.complete(prompt.confirm(editText.text.toString()))
                }
                .setNegativeButton("Cancelar") { _, _ ->
                    result.complete(prompt.dismiss())
                }
                .setOnCancelListener {
                    result.complete(prompt.dismiss())
                }
                .show()
        }
        return result
    }

    override fun onChoicePrompt(
        session: GeckoSession,
        prompt: ChoicePrompt
    ): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        val flatChoices = flattenChoices(prompt.choices)
        if (flatChoices.isEmpty()) {
            result.complete(prompt.dismiss())
            return result
        }

        activity.runOnUiThread {
            if (!isActivityUsable()) {
                result.complete(prompt.dismiss())
                return@runOnUiThread
            }
            val labels = flatChoices.map {
                SecurityPolicy.sanitizePromptText(it.label, 200)
            }.toTypedArray()
            val safeTitle = SecurityPolicy.sanitizePromptText(
                prompt.title?.takeIf { it.isNotBlank() } ?: prompt.message,
                120
            ).ifBlank { "Seleccionar" }

            val builder = AlertDialog.Builder(
                activity,
                androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert
            )
                .setTitle(safeTitle)
                .setOnCancelListener { result.complete(prompt.dismiss()) }

            if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
                val checked = BooleanArray(flatChoices.size) { flatChoices[it].selected }
                builder.setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                builder.setPositiveButton("Aceptar") { _, _ ->
                    val selected = flatChoices.filterIndexed { index, _ -> checked[index] }
                    result.complete(prompt.confirm(selected.toTypedArray()))
                }
                builder.setNegativeButton("Cancelar") { _, _ ->
                    result.complete(prompt.dismiss())
                }
            } else {
                val selectedIdx = flatChoices.indexOfFirst { it.selected }
                builder.setSingleChoiceItems(labels, selectedIdx) { dialog, which ->
                    dialog.dismiss()
                    result.complete(prompt.confirm(flatChoices[which]))
                }
                builder.setNegativeButton("Cancelar") { _, _ ->
                    result.complete(prompt.dismiss())
                }
            }
            builder.show()
        }
        return result
    }

    override fun onSharePrompt(
        session: GeckoSession,
        prompt: SharePrompt
    ): GeckoResult<PromptResponse> {
        if (!isActivityUsable()) {
            return GeckoResult.fromValue(prompt.dismiss())
        }
        val safeUri = prompt.uri?.takeIf { SecurityPolicy.isAllowedExternalScheme(it) }
        val shareText = buildString {
            val txt = SecurityPolicy.sanitizePromptText(prompt.text, 4000)
            if (txt.isNotBlank()) append(txt)
            if (!safeUri.isNullOrBlank()) {
                if (isNotEmpty()) append("\n")
                append(safeUri)
            }
        }
        if (shareText.isBlank()) {
            return GeckoResult.fromValue(prompt.dismiss())
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            val title = SecurityPolicy.sanitizePromptText(prompt.title, 120)
            if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        return try {
            activity.startActivity(Intent.createChooser(intent, "Compartir"))
            GeckoResult.fromValue(prompt.confirm(SharePrompt.Result.SUCCESS))
        } catch (_: Exception) {
            GeckoResult.fromValue(prompt.confirm(SharePrompt.Result.FAILURE))
        }
    }

    private fun flattenChoices(choices: Array<ChoicePrompt.Choice>?): List<ChoicePrompt.Choice> {
        if (choices.isNullOrEmpty()) return emptyList()
        val out = mutableListOf<ChoicePrompt.Choice>()
        for (c in choices) {
            if (c.separator) continue
            if (!c.items.isNullOrEmpty()) {
                out.addAll(flattenChoices(c.items))
            } else if (!c.disabled) {
                out.add(c)
            }
        }
        return out
    }

    private fun normalizeMimeTypes(raw: Array<String>?): Array<String> {
        if (raw.isNullOrEmpty()) return arrayOf("*/*")
        val resolved = mutableSetOf<String>()
        val map = MimeTypeMap.getSingleton()
        for (entry in raw) {
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) continue
            when {
                trimmed == "*/*" -> return arrayOf("*/*")
                trimmed.contains("/") -> resolved.add(trimmed)
                trimmed.startsWith(".") -> {
                    val mime = map.getMimeTypeFromExtension(trimmed.removePrefix(".").lowercase())
                    if (mime != null) resolved.add(mime) else return arrayOf("*/*")
                }
                else -> return arrayOf("*/*")
            }
        }
        return if (resolved.isEmpty()) arrayOf("*/*") else resolved.toTypedArray()
    }
}
