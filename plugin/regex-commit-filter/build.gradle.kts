/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.plugin-app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
    id("org.fcitx.fcitx5.android.fcitx-component")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.regex_commit"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.plugin.regex_commit"

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                targets("regexcommit")
            }
        }
    }

    buildFeatures {
        resValues = true
    }

    buildTypes {
        release {
            resValue("string", "app_name", "@string/app_name_release")
            proguardFile("proguard-rules.pro")
        }
        debug {
            resValue("string", "app_name", "@string/app_name_debug")
        }
    }

    packaging {
        jniLibs {
            excludes += setOf(
                "**/libc++_shared.so",
                "**/libFcitx5*"
            )
        }
    }
}

dependencies {
    implementation(project(":lib:fcitx5"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.re2j)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    testImplementation(libs.junit)
}
