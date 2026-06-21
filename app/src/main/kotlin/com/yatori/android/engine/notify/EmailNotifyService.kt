package com.yatori.android.engine.notify

import com.yatori.android.domain.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * 邮件通知服务
 * 等价移植 Go utils/EmailUtils.go SendMail
 */
@Singleton
class EmailNotifyService @Inject constructor() {

    /**
     * 发送任务完成通知邮件
     * 等价 Go SendMail(host, port, userName, password, toMail, content)
     */
    suspend fun sendCompletion(
        settings: AppSettings,
        toEmails: List<String>,
        platform: String,
        account: String
    ) {
        if (!settings.emailSw || toEmails.isEmpty()) return
        val content = "账号：[$account]</br>平台：[$platform]</br>通知：所有课程已执行完毕"
        send(settings, toEmails, "Yatori课程助手通知", content)
    }

    suspend fun send(
        settings: AppSettings,
        toEmails: List<String>,
        subject: String,
        htmlBody: String
    ) = withContext(Dispatchers.IO) {
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.host", settings.smtpHost)
            put("mail.smtp.port", settings.smtpPort.toString())
            put("mail.smtp.ssl.trust", settings.smtpHost)
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(settings.smtpUser, settings.smtpPassword)
        })
        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(settings.smtpUser, "Yatori课程助手", "UTF-8"))
            setRecipients(Message.RecipientType.TO,
                toEmails.map { InternetAddress(it) }.toTypedArray())
            setSubject(subject, "UTF-8")
            setContent(buildHtml(htmlBody), "text/html; charset=UTF-8")
        }
        Transport.send(msg)
    }

    /** 简单 HTML 模板，等价 Go buildEmailHTML */
    private fun buildHtml(content: String) = """
        <!DOCTYPE html><html><body style="font-family:sans-serif;background:#f5f5f5;padding:24px">
        <div style="max-width:480px;margin:auto;background:#fff;border-radius:12px;padding:24px">
        <h2 style="color:#4F6EF7">🎓 Yatori 课程助手</h2>
        <p style="color:#333;line-height:1.8">$content</p>
        <hr style="border:none;border-top:1px solid #eee">
        <p style="color:#999;font-size:12px">此邮件由 Yatori Android 自动发送</p>
        </div></body></html>
    """.trimIndent()
}
