plugins {
    id("tennyo.kmp.compose")
}

tennyoTargets {
    android(namespace = "app.tennyo.mobile.design") {
        androidResources {
            enable = true
        }
        withHostTestBuilder {}.configure {}
    }
    ios()
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
        }
    }
}

compose.resources {
    packageOfResClass = "app.tennyo.mobile.design.generated.resources"
}
