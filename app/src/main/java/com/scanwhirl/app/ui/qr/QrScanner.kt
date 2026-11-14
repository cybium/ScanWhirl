package com.scanwhirl.app.ui.qr

import android.app.Activity
import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** What a scan attempt produced. */
sealed class QrScanOutcome {
    data class Found(val rawValue: String, val kind: QrKind) : QrScanOutcome()

    /** The user backed out of the scanner: nothing is saved. */
    object Cancelled : QrScanOutcome()

    /** The scanner could not run (module not downloaded yet, no Play services, bad image). */
    data class Failed(val message: String) : QrScanOutcome()
}

/**
 * Seam around the Google code scanner (design §3 "QR code reader").
 *
 * The scanner is a Play-services UI that downloads its module on first use; Robolectric has
 * neither, so tests replace the implementation through [QrScanners.override].
 */
fun interface QrScanner {
    fun scan(activity: Activity, callback: (QrScanOutcome) -> Unit)
}

object QrScanners {

    /** Test seam: a fake scanner instead of the Play-services one. */
    @Volatile
    var override: QrScanner? = null

    fun get(context: Context): QrScanner = override ?: GoogleCodeScanner(context.applicationContext)
}

/**
 * The real scanner: the Google code scanner bottom sheet, QR plus the common 1D/2D formats,
 * with auto zoom. No camera permission is needed (the code lives in Play services) and no
 * image is ever sent anywhere.
 *
 * DEVICE-UNPROVEN: needs Play services and the scanner module on the phone.
 */
class GoogleCodeScanner(private val context: Context) : QrScanner {

    override fun scan(activity: Activity, callback: (QrScanOutcome) -> Unit) {
        try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_AZTEC,
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_CODE_93,
                    Barcode.FORMAT_CODABAR,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_ITF,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_PDF417
                )
                .enableAutoZoom()
                .build()
            GmsBarcodeScanning.getClient(activity, options)
                .startScan()
                .addOnSuccessListener { barcode ->
                    val value = barcode.rawValue
                    if (value.isNullOrBlank()) {
                        callback(QrScanOutcome.Failed(context.getString(com.scanwhirl.app.R.string.qr_empty_code)))
                    } else {
                        callback(QrScanOutcome.Found(value, kindOf(barcode.valueType)))
                    }
                }
                .addOnCanceledListener { callback(QrScanOutcome.Cancelled) }
                .addOnFailureListener { error ->
                    callback(
                        QrScanOutcome.Failed(
                            context.getString(
                                com.scanwhirl.app.R.string.qr_scanner_error,
                                error.message ?: error.javaClass.simpleName
                            )
                        )
                    )
                }
        } catch (e: Exception) {
            callback(
                QrScanOutcome.Failed(
                    context.getString(
                        com.scanwhirl.app.R.string.qr_scanner_error,
                        e.message ?: e.javaClass.simpleName
                    )
                )
            )
        }
    }

    private fun kindOf(valueType: Int): QrKind = when (valueType) {
        Barcode.TYPE_URL -> QrKind.URL
        Barcode.TYPE_WIFI -> QrKind.WIFI
        Barcode.TYPE_CONTACT_INFO -> QrKind.CONTACT
        Barcode.TYPE_PHONE -> QrKind.PHONE
        Barcode.TYPE_EMAIL -> QrKind.EMAIL
        Barcode.TYPE_SMS -> QrKind.SMS
        Barcode.TYPE_GEO -> QrKind.GEO
        else -> QrKind.TEXT
    }
}
