// Plugin versions are declared here (and applied in :app) so that the
// ics-openvpn submodule, which applies the same plugins without a version,
// resolves against the exact same plugin classpath.
plugins {
    id("com.android.application") version "8.8.0" apply false
    id("com.android.library") version "8.8.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.1.0" apply false
}
