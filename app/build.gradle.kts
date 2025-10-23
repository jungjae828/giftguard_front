// app/build.gradle.kts (모듈 수준)

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.android.libraries.mapsplatform.secrets.gradle.plugin)

    // ⭐ 수정된 별칭으로 적용
    alias(libs.plugins.composecompiler)
}

android {
    namespace = "com.example.giftguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.giftguard"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // `kotlinOptions` 블록은 삭제되었습니다. (JVM Target 경고 해결)

    buildFeatures {
        viewBinding = true
        compose = true
    }
}

// ⭐ kotlin 블록은 android 블록과 같은 최상위 레벨에 위치해야 합니다.
kotlin {
    compilerOptions {
        // Kotlin 2.0+ 컴파일러 옵션 설정
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}


dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.play.services.maps)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:text-recognition-korean:16.0.0")
    // 🚨 지오펜싱(Location API) 의존성 추가 🚨
    implementation("com.google.android.gms:play-services-location:21.0.1")
// 최신 버전 확인 권장
    // Kotlin Coroutines 사용을 위한 종속성 (서비스 코드에서 사용됨)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3") // 또는 최신 버전

// Retrofit 라이브러리 추가 (최신 버전 확인 필요)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
// Gson 변환기 추가 (JSON 데이터를 Kotlin/Java 객체로 자동 변환)
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
// 선택 사항: 통신 로그를 보기 위한 OkHttp Logging Interceptor
// implementation 'com.squareup.okhttp3:logging-interceptor:4.9.0'
    implementation("com.squareup.retrofit2:converter-scalars:2.9.0") //
    implementation("com.google.mlkit:vision-common:17.3.0")
}