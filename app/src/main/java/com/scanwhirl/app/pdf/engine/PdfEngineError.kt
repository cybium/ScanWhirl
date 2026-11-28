package com.scanwhirl.app.pdf.engine

/** Typed failures from the PDF engine. Nothing here logs document text or passwords. */
sealed class PdfEngineError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidPassword : PdfEngineError("Wrong or missing password")

    class MalformedDocument(detail: String) :
        PdfEngineError("This PDF cannot be read safely: $detail")

    class UnsupportedFeature(feature: String) :
        PdfEngineError("This PDF uses $feature, which ScanWhirl cannot edit")

    class LimitExceeded(what: String) : PdfEngineError("PDF budget exceeded: $what")

    class VerificationFailed(what: String) : PdfEngineError("Output could not be verified: $what")

    class Cancelled : PdfEngineError("Cancelled")

    class Io(cause: Throwable) : PdfEngineError("PDF I/O failed", cause)
}
