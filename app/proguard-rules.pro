# ScanWhirl R8 rules for the minified store build (-PciUnsignedRelease / Play).
#
# The libraries we ship already carry consumer rules (androidx.biometric,
# play-services-basement, firebase-components, material, androidx.core). This
# file only adds what R8 cannot see: classes reached by name (reflection, JNI,
# manifest metadata, layout XML) and the two optional-dependency warnings.
# Test builds (-PtestBuild) do not run R8, so none of this affects them.

# ---- ML Kit ---------------------------------------------------------------

# ML Kit registers its recognizers through manifest metadata written by
# play-services-mlkit-text-recognition-common:
#   <meta-data android:name="com.google.firebase.components:...TextRegistrar"
#              android:value="com.google.firebase.components.ComponentRegistrar"/>
# firebase-components' consumer rule keeps the registrar classes and their
# names; the constructor Firebase instantiates by reflection is not covered.
-keepclassmembers class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}

# DynamiteModule resolves this descriptor by name and reads both fields
# reflectively; it selects which native module version is loaded.
-keep class com.google.android.gms.dynamite.descriptors.** {
    public static final java.lang.String MODULE_ID;
    public static final int MODULE_VERSION;
}

# The bundled Devanagari recognizer (com.google.mlkit:text-recognition-devanagari)
# runs inside our process: its native vision pipeline creates these classes and
# calls their methods from C++ by the names compiled into the .so. The AAR's
# consumer rules only cover native methods and the protobuf field access, so the
# pipeline and its 1000-odd protobuf classes must not be renamed or removed.
-keep class com.google.android.libraries.vision.visionkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_bundled_common.** { *; }
-keep class com.google.mlkit.vision.text.pipeline.** { *; }
-keep class com.google.mlkit.vision.text.bundled.** { *; }

# ---- Our own lookups by name ----------------------------------------------

# QR history stores QrKind.name() in qr_history.txt and QrActivity receives the
# name as an Intent extra. R8 renames enum constants, which would make
# QrKind.valueOf(<stored name>) fail (history silently loses its type after an
# app update). Every ScanWhirl enum is tiny, so all of them keep their names.
-keepclassmembers enum com.scanwhirl.app.** { *; }

# Custom views are inflated from layouts by class name (PageGridView,
# AdaptiveHeaderRow, CropView, ZoomableImageView, StampCanvasView, ...); the
# two-argument constructor is called by the inflater, never from Kotlin, so R8
# cannot see the reference. Screen classes themselves are manifest components
# and are kept automatically through aapt_rules.
-keepclassmembers class com.scanwhirl.app.** extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# ---- Diagnostics ----------------------------------------------------------

# mapping.txt is uploaded with every release build, so keeping the source file
# and line numbers in stack traces is what makes a de-obfuscated crash report
# readable. Costs a few KB.
-keepattributes SourceFile,LineNumberTable

# ---- Optional references --------------------------------------------------

# ML Kit and Play services mention optional modules (analytics, auth, protobuf,
# conscrypt, ...) that this app does not ship. R8 full mode fails the build on
# those unresolved references; nothing calls them at runtime.
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# ---- PDFBox-Android (W7a) ------------------------------------------------

# The AAR's POM declares Bouncy Castle, but the dependency is excluded and the
# encryption paths are never called (the app's pure-JCA PdfEncryptor/PdfDecryptor
# handle all passwords). R8 still sees those dangling references and must not
# fail the build on them.
-dontwarn org.bouncycastle.**
-dontwarn com.tom_roush.pdfbox.pdmodel.encryption.**

# W7a engine is not yet called from any screen (W7b wiring is deferred), so R8
# would remove the whole package from the minified APK. Keep it for the
# instrumented runtime check and the later UI integration.
-keep class com.scanwhirl.app.pdf.engine.** { *; }

# The AAR resolves fonts, filters and the resource loader from asset names and
# service-style reflection. Keeping only the two reflective entry points is not
# enough: R8 strips PDDocument and the smoke crashes with NoClassDefFoundError.
# Keep the library intact; the size workflow re-measures this cost on every push.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }

# Kotlin runtime helpers used by the engine's compiled Kotlin classes.
-keep class kotlin.jvm.internal.Intrinsics { *; }
-keep class kotlin.jvm.internal.Lambda { *; }

# PDFBox-Android's JPX filter references the optional Gemalto JP2 decoder, which
# is deliberately not shipped (no INTERNET/JCenter dependency). JPX input fails
# closed at runtime; R8 must not fail the build on those unresolved references.
-dontwarn com.gemalto.jp2.**
