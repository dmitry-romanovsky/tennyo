plugins {
    id("tennyo.kmp.feature")
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            // System back inside the commit-and-diff sheet steps from a file back to its commit.
            implementation(libs.androidx.activity.compose)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
    }
}
