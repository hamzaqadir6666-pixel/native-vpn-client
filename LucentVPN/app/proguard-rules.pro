# ---------------------------------------------------------------------------
# ics-openvpn engine
#
# The native openvpn3 core calls back into Java by name, and VpnProfile is
# serialized to disk by ProfileManager, so neither may be renamed or stripped.
# ---------------------------------------------------------------------------
-keep class de.blinkt.openvpn.** { *; }
-keep interface de.blinkt.openvpn.** { *; }
-keepclassmembers class de.blinkt.openvpn.VpnProfile { *; }
-keepnames class de.blinkt.openvpn.VpnProfile
-keep class de.blinkt.openvpn.core.NativeUtils { *; }
-keep class de.blinkt.openvpn.core.OpenVPNService { *; }

# VpnProfile is written with java.io serialization by ProfileManager.
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# AIDL stubs used to bind to the tunnel service.
-keep class * implements android.os.IInterface { *; }

# Our own VpnService subclass is referenced from the manifest only.
-keep class com.lucentvpn.android.vpn.** { *; }

-dontwarn de.blinkt.openvpn.**
-dontwarn org.jetbrains.annotations.**
