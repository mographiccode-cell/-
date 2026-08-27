package com.mographiccode.deletedmessagevault

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class WhatsAppNotificationListener : NotificationListenerService() {
    private val allowedPackages = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val deletionPhrases = listOf(
        "this message was deleted",
        "you deleted this message",
        "تم حذف هذه الرسالة",
        "لقد تم حذف هذه الرسالة",
        "حذفت هذه الرسالة",
        "تم حذف الرسالة",
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName !in allowedPackages) return
        val db = LocalMessageDatabase(applicationContext)
        val notification = sbn.notification ?: return
        val extras = notification.extras

        var insertedMessagingStyle = false
        val messageBundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!messageBundles.isNullOrEmpty()) {
            val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(messageBundles)
            messages.forEach { message ->
                val text = message.text?.toString()?.trim().orEmpty()
                if (text.isBlank()) return@forEach
                val sender = getMessageSender(message)
                    ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                    ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                val postedAt = if (message.timestamp > 0) message.timestamp else sbn.postTime
                db.insertMessage(
                    packageName = sbn.packageName,
                    sender = sanitizeSender(sender),
                    body = text,
                    postedAt = postedAt,
                    notificationKey = sbn.key,
                    deletionSignal = isDeletionText(text),
                )
                insertedMessagingStyle = true
            }
        }

        if (!insertedMessagingStyle) {
            val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            val text = sequenceOf(
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

            if (text.isNotBlank() && !looksLikeSummaryOnly(text)) {
                db.insertMessage(
                    packageName = sbn.packageName,
                    sender = sanitizeSender(sender),
                    body = text,
                    postedAt = sbn.postTime,
                    notificationKey = sbn.key,
                    deletionSignal = isDeletionText(text),
                )
            }
        }

        Thread {
            try {
                Thread.sleep(2500)
                MediaScanner(applicationContext).scanRecent(90_000L)
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun getMessageSender(message: Notification.MessagingStyle.Message): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            message.senderPerson?.name?.toString()
        } else {
            @Suppress("DEPRECATION")
            message.sender?.toString()
        }
    }

    private fun isDeletionText(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return deletionPhrases.any { normalized == it || normalized.contains(it) }
    }

    private fun sanitizeSender(sender: String?): String? {
        val value = sender?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return value
            .replace(Regex("\\s*\\(\\d+ messages?\\)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(\\d+ رسائل?\\)$"), "")
            .trim()
    }

    private fun looksLikeSummaryOnly(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return normalized.matches(Regex("\\d+ new messages?")) ||
            normalized.matches(Regex("\\d+ رسائل? جديدة"))
    }
}
