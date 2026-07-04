package dev.yatori.mobile.app.service

import dev.yatori.mobile.api.dto.EmailInform
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Sends HTML notification emails via SMTP — Android port of the console's EmailUtils.go SendMail.
 *
 * Must be called from a background thread (not main thread).
 */
object EmailSender {

    /**
     * Sends a notification email. Returns null on success, error message on failure.
     * Mirrors console SendMail(host, port, userName, password, toMail, content).
     */
    fun send(config: EmailInform, toAddresses: List<String>, content: String): String? {
        if (config.sw != 1) return null
        if (toAddresses.isEmpty()) return null
        val host = config.smtpHost.trim()
        val portStr = config.smtpPort.trim()
        val from = config.email.trim()
        val password = config.password

        if (host.isEmpty() || portStr.isEmpty() || from.isEmpty()) return "邮件配置不完整"
        val port = portStr.toIntOrNull() ?: return "SMTP 端口格式错误：$portStr"

        return runCatching {
            val props = buildSmtpProperties(host, port)
            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(from, password)
            })
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(from, "Yatori课程助手"))
                setRecipients(Message.RecipientType.TO, toAddresses.map { InternetAddress(it) }.toTypedArray())
                subject = "Yatori课程助手通知"
                setContent(buildEmailHtml("Yatori课程助手", content), "text/html; charset=utf-8")
            }
            Transport.send(msg)
            null
        }.getOrElse { it.message ?: "邮件发送失败" }
    }

    private fun buildSmtpProperties(host: String, port: Int): Properties = Properties().apply {
        put("mail.smtp.host", host)
        put("mail.smtp.port", port.toString())
        put("mail.smtp.auth", "true")
        // Use SSL for port 465, STARTTLS for 587 / others
        if (port == 465) {
            put("mail.smtp.socketFactory.port", "465")
            put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
            put("mail.smtp.ssl.enable", "true")
        } else {
            put("mail.smtp.starttls.enable", "true")
        }
        put("mail.smtp.ssl.trust", "*")  // mirror console InsecureSkipVerify
        put("mail.smtp.timeout", "15000")
        put("mail.smtp.connectiontimeout", "15000")
    }

    /** Mirrors console buildEmailHTML — HTML email with logo, title, content. */
    private fun buildEmailHtml(title: String, contentHtml: String): String {
        val logoUrl = "https://avatars.githubusercontent.com/u/185567923?s=1000&v=4"
        val safeTitle = title.htmlEscape()
        return """<!doctype html>
<html lang="zh-CN">
<head><meta charset="utf-8"><title>$safeTitle</title></head>
<body style="margin:0;padding:0;background:#f5f7fb;">
  <table role="presentation" cellpadding="0" cellspacing="0" width="100%" style="background:#f5f7fb;">
    <tr><td align="center" style="padding:32px 16px;">
      <table role="presentation" cellpadding="0" cellspacing="0" width="600"
             style="max-width:600px;background:#ffffff;border-radius:16px;box-shadow:0 6px 24px rgba(18,38,63,0.08);">
        <tr><td align="center" style="padding:28px 24px 8px 24px;">
          <img src="$logoUrl" width="88" height="88" alt="logo"
               style="display:block;border-radius:50%;width:88px;height:88px;border:2px solid #eef2f7;">
        </td></tr>
        <tr><td align="center" style="padding:0 24px 8px 24px;">
          <div style="font-family:system-ui,sans-serif;font-size:22px;font-weight:700;color:#111827;">$safeTitle</div>
        </td></tr>
        <tr><td style="padding:8px 24px 0 24px;">
          <div style="height:1px;background:linear-gradient(90deg,#e5e7eb,#f3f4f6,#e5e7eb);"></div>
        </td></tr>
        <tr><td style="padding:18px 24px 28px 24px;">
          <div style="font-family:system-ui,sans-serif;font-size:15px;color:#374151;line-height:1.8;">
            $contentHtml
          </div>
        </td></tr>
      </table>
      <table role="presentation" cellpadding="0" cellspacing="0" width="600" style="max-width:600px;">
        <tr><td align="center" style="padding:14px 8px 0 8px;color:#6b7280;font-family:system-ui,sans-serif;font-size:12px;">
          这是一封系统通知邮件，请勿直接回复。
        </td></tr>
      </table>
    </td></tr>
  </table>
</body>
</html>"""
    }

    private fun String.htmlEscape() = this
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
}
