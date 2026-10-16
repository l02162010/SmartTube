package com.liskovsoft.smartyoutubetv2.common.dualsub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.liskovsoft.sharedutils.helpers.MessageHelpers
import com.liskovsoft.smartyoutubetv2.common.R
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Shows a QR code; the phone opens the page served by [KeyReceiverServer] and pastes the API key.
 */
object QrKeyDialog {
    private const val QR_SIZE_PX = 480

    @JvmStatic
    fun show(context: Context, onSaved: Runnable? = null) {
        val ip = lanAddress()

        if (ip == null) {
            MessageHelpers.showMessage(context, context.getString(R.string.dualsub_qr_no_network))
            return
        }

        val main = Handler(Looper.getMainLooper())
        lateinit var dialog: AlertDialog
        val server = KeyReceiverServer(onKey = { key ->
            main.post {
                DualSubData.instance(context).apiKey = key
                MessageHelpers.showMessage(context, context.getString(R.string.dualsub_qr_saved, key.takeLast(4)))
                dialog.dismiss()
                onSaved?.run()
            }
        })

        val port = try {
            server.start()
        } catch (e: Exception) {
            MessageHelpers.showMessage(context, e.message)
            return
        }

        val url = "http://$ip:$port/?t=${server.token}"
        val padding = (24 * context.resources.displayMetrics.density).toInt()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding / 2, padding, 0)
            addView(ImageView(context).apply { setImageBitmap(qrBitmap(url)) })
            addView(TextView(context).apply {
                text = context.getString(R.string.dualsub_qr_hint)
                gravity = Gravity.CENTER
                setPadding(0, padding / 2, 0, 0)
            })
        }

        dialog = AlertDialog.Builder(context, R.style.AppDialog)
            .setTitle(R.string.dualsub_qr_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener { server.stop() } // never leave the server running

        try {
            dialog.show()
        } catch (e: RuntimeException) {
            server.stop()
            MessageHelpers.showMessage(context, e.message)
        }
    }

    private fun qrBitmap(text: String): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX, mapOf(EncodeHintType.MARGIN to 2))
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix.get(i % matrix.width, i / matrix.width)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.RGB_565)
    }

    /** The TV's address on the home network (Wi-Fi or Ethernet) */
    private fun lanAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
