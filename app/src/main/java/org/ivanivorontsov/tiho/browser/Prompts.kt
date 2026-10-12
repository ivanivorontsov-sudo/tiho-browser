package org.ivanivorontsov.tiho.browser

import android.content.Intent
import android.net.Uri
import org.ivanivorontsov.tiho.security.Homograph
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse

/** Web page prompts (alert/confirm/prompt/select/file/auth/date/color/popup/beforeunload) → Compose dialogs. */
class Prompts(private val b: Browser) {
    private fun site(tab: Tab): String = try { Homograph.display(Uri.parse(tab.url.value).host) } catch (_: Exception) { null } ?: "Страница"

    fun delegate(tab: Tab) = object : PromptDelegate {
        override fun onAlertPrompt(session: GeckoSession, p: PromptDelegate.AlertPrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            b.show(UiDialog.Message(p.title?.takeIf { it.isNotBlank() } ?: site(tab), p.message ?: "") { r.complete(p.dismiss()) })
            return r
        }
        override fun onButtonPrompt(session: GeckoSession, p: PromptDelegate.ButtonPrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            b.show(UiDialog.Message(p.title?.takeIf { it.isNotBlank() } ?: site(tab), p.message ?: "", "OK", "Отмена") { ok ->
                r.complete(p.confirm(if (ok) PromptDelegate.ButtonPrompt.Type.POSITIVE else PromptDelegate.ButtonPrompt.Type.NEGATIVE))
            })
            return r
        }
        override fun onTextPrompt(session: GeckoSession, p: PromptDelegate.TextPrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            b.show(UiDialog.Input(p.title?.takeIf { it.isNotBlank() } ?: site(tab), p.message ?: "", listOf(UiDialog.Field("", p.defaultValue ?: ""))) { v ->
                r.complete(if (v == null) p.dismiss() else p.confirm(v[0]))
            })
            return r
        }
        override fun onAuthPrompt(session: GeckoSession, p: PromptDelegate.AuthPrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            val onlyPassword = p.authOptions.flags and PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
            val fields = if (onlyPassword) listOf(UiDialog.Field("Пароль", "", true))
            else listOf(UiDialog.Field("Имя пользователя", p.authOptions.username ?: ""), UiDialog.Field("Пароль", "", true))
            // Show the origin that actually asks for the password (may be a third-party iframe / subresource), not the tab's site.
            val asker = try { Homograph.display(Uri.parse(p.authOptions.uri).host) } catch (_: Exception) { null }
            val tabSite = site(tab)
            val title = if (asker != null && asker != tabSite) "Вход: $asker (на странице $tabSite)" else "Вход: ${asker ?: tabSite}"
            val note = if (asker != null && asker != tabSite) "⚠ Пароль запрашивает другой сайт — $asker, а не $tabSite.\n\n" else ""
            b.show(UiDialog.Input(title, note + (p.message ?: ""), fields) { v ->
                r.complete(when { v == null -> p.dismiss(); onlyPassword -> p.confirm(v[0]); else -> p.confirm(v[0], v[1]) })
            })
            return r
        }
        override fun onChoicePrompt(session: GeckoSession, p: PromptDelegate.ChoicePrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            val flat = ArrayList<Triple<String, String, Boolean>>()
            fun add(cs: Array<PromptDelegate.ChoicePrompt.Choice>?, depth: Int) {
                for (c in cs.orEmpty()) {
                    if (c.separator) continue
                    if (c.items != null) { flat += Triple("", c.label ?: "", false); add(c.items, depth + 1) }
                    else flat += Triple(c.id, "  ".repeat(depth) + (c.label ?: ""), !c.disabled)
                }
            }
            add(p.choices, 0)
            val sel = HashSet<String>()
            fun collect(cs: Array<PromptDelegate.ChoicePrompt.Choice>?) { for (c in cs.orEmpty()) { if (c.selected) sel += c.id; collect(c.items) } }
            collect(p.choices)
            b.show(UiDialog.Choice(p.title?.takeIf { it.isNotBlank() } ?: (p.message ?: "Выбор"), flat, p.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE, sel) { v ->
                r.complete(when {
                    v == null -> p.dismiss()
                    p.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE -> p.confirm(v.toTypedArray())
                    v.isEmpty() -> p.dismiss()
                    else -> p.confirm(v[0])
                })
            })
            return r
        }
        override fun onDateTimePrompt(session: GeckoSession, p: PromptDelegate.DateTimePrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            val hint = when (p.type) {
                PromptDelegate.DateTimePrompt.Type.DATE -> "ГГГГ-ММ-ДД"; PromptDelegate.DateTimePrompt.Type.TIME -> "ЧЧ:ММ"
                PromptDelegate.DateTimePrompt.Type.MONTH -> "ГГГГ-ММ"; PromptDelegate.DateTimePrompt.Type.WEEK -> "ГГГГ-Wнн"
                else -> "ГГГГ-ММ-ДДTЧЧ:ММ"
            }
            b.show(UiDialog.Input(site(tab), "Формат: $hint", listOf(UiDialog.Field(hint, p.defaultValue ?: ""))) { v ->
                r.complete(if (v == null) p.dismiss() else p.confirm(v[0]))
            })
            return r
        }
        override fun onColorPrompt(session: GeckoSession, p: PromptDelegate.ColorPrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            b.show(UiDialog.Input(site(tab), "Цвет в формате #rrggbb", listOf(UiDialog.Field("#rrggbb", p.defaultValue ?: "#000000"))) { v ->
                r.complete(if (v == null || !Regex("^#[0-9a-fA-F]{6}$").matches(v[0])) p.dismiss() else p.confirm(v[0].lowercase()))
            })
            return r
        }
        override fun onFilePrompt(session: GeckoSession, p: PromptDelegate.FilePrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            val multiple = p.type == PromptDelegate.FilePrompt.Type.MULTIPLE
            val types = p.mimeTypes?.filter { it.contains('/') }.orEmpty()
            b.pickFiles(types, multiple) { uris ->
                val ctx = b.activity
                r.complete(when {
                    uris.isNullOrEmpty() || ctx == null -> p.dismiss()
                    multiple -> p.confirm(ctx, uris.toTypedArray())
                    else -> p.confirm(ctx, uris[0])
                })
            }
            return r
        }
        override fun onPopupPrompt(session: GeckoSession, p: PromptDelegate.PopupPrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            b.show(UiDialog.Message("Всплывающее окно", "${site(tab)} хочет открыть новое окно:\n${p.targetUri ?: ""}", "Разрешить", "Блокировать") { ok ->
                r.complete(p.confirm(if (ok) AllowOrDeny.ALLOW else AllowOrDeny.DENY))
            })
            return r
        }
        override fun onBeforeUnloadPrompt(session: GeckoSession, p: PromptDelegate.BeforeUnloadPrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            b.show(UiDialog.Message("Покинуть страницу?", "Введённые данные могут не сохраниться.", "Покинуть", "Остаться") { ok ->
                r.complete(p.confirm(if (ok) AllowOrDeny.ALLOW else AllowOrDeny.DENY))
            })
            return r
        }
        override fun onRepostConfirmPrompt(session: GeckoSession, p: PromptDelegate.RepostConfirmPrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            b.show(UiDialog.Message("Отправить форму повторно?", "Страница повторит отправку данных (например, платёж).", "Отправить", "Отмена", danger = true) { ok ->
                r.complete(p.confirm(if (ok) AllowOrDeny.ALLOW else AllowOrDeny.DENY))
            })
            return r
        }
        override fun onSharePrompt(session: GeckoSession, p: PromptDelegate.SharePrompt): GeckoResult<PromptResponse> {
            val text = listOfNotNull(p.title, p.text, p.uri).joinToString("\n")
            b.activity?.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Поделиться"))
            return GeckoResult.fromValue(p.confirm(PromptDelegate.SharePrompt.Result.SUCCESS))
        }
    }
}

/** Site permissions: geolocation / camera / mic ask; notifications, persistent storage, audible autoplay denied. */
class Permissions(private val b: Browser) {
    private fun host(u: String?) = try { Homograph.display(Uri.parse(u).host) } catch (_: Exception) { null } ?: "Сайт"

    fun delegate(tab: Tab) = object : PermissionDelegate {
        override fun onContentPermissionRequest(session: GeckoSession, perm: PermissionDelegate.ContentPermission): GeckoResult<Int> {
            val allow = PermissionDelegate.ContentPermission.VALUE_ALLOW
            val deny = PermissionDelegate.ContentPermission.VALUE_DENY
            val what = when (perm.permission) {
                PermissionDelegate.PERMISSION_GEOLOCATION -> "ваше местоположение"
                PermissionDelegate.PERMISSION_STORAGE_ACCESS -> "доступ к своим cookie на ${host(perm.thirdPartyOrigin)} (вход через сторонний сайт)"
                PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> if (b.app.prefs.drm) "воспроизведение защищённого видео (DRM)" else return GeckoResult.fromValue(deny)
                PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS, PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS -> "доступ к устройствам в вашей локальной сети"
                PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> return GeckoResult.fromValue(allow)
                else -> return GeckoResult.fromValue(deny)
            }
            val r = GeckoResult<Int>()
            b.show(UiDialog.Message("Разрешение", "${host(perm.uri)} запрашивает $what.", "Разрешить", "Запретить") { ok -> r.complete(if (ok) allow else deny) })
            return r
        }

        override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<String>?, callback: PermissionDelegate.Callback) {
            val p = permissions.orEmpty()
            if (p.isEmpty()) { callback.grant(); return }
            b.requestPermissions(p.map { it }.toTypedArray()) { ok -> if (ok) callback.grant() else callback.reject() }
        }

        override fun onMediaPermissionRequest(session: GeckoSession, uri: String, video: Array<PermissionDelegate.MediaSource>?, audio: Array<PermissionDelegate.MediaSource>?, callback: PermissionDelegate.MediaCallback) {
            val v = video?.firstOrNull(); val a = audio?.firstOrNull()
            val what = listOfNotNull(v?.let { "камеру" }, a?.let { "микрофон" }).joinToString(" и ")
            if (what.isEmpty()) { callback.reject(); return }
            b.show(UiDialog.Message("Разрешение", "${host(uri)} запрашивает $what.", "Разрешить", "Запретить") { ok -> if (ok) callback.grant(v, a) else callback.reject() })
        }
    }
}
