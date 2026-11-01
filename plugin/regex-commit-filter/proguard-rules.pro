# SPDX-License-Identifier: LGPL-2.1-or-later
-dontobfuscate

-keepattributes SourceFile,LineNumberTable

-processkotlinnullchecks remove

# kotlinx.serialization generated serializers are referenced reflectively
-keepclassmembers class org.fcitx.fcitx5.android.plugin.regex_commit.core.** {
    *** Companion;
}
-keepclasseswithmembers class org.fcitx.fcitx5.android.plugin.regex_commit.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
