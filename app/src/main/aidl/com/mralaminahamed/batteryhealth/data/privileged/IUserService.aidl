// Shizuku's UserService surface: a small AIDL interface implemented by
// PrivilegedBatteryService and instantiated by Shizuku itself, by reflection, inside a
// process it starts under the shell UID -- never bound through the normal Android
// service manager, so this has no <service> entry in AndroidManifest.xml.
package com.mralaminahamed.batteryhealth.data.privileged;

interface IUserService {
    // dumpsys battery, verbatim. Empty string on any failure inside the shell process
    // (subprocess spawn failed, stream read failed) rather than an exception crossing
    // the Binder call -- the caller cannot distinguish "empty" from "failed" any more
    // precisely than that, and does not need to: DumpsysBatteryParser treats both the
    // same way, an absent dump.
    String dumpBattery();

    // Lets the client end this UserService's process explicitly rather than waiting for
    // Shizuku to reap it after unbind. Not currently called by ShizukuGateway (the
    // service is bound once for the app's lifetime), kept for symmetry and because an
    // AIDL UserService interface with no way to ever stop itself is a footgun for
    // whoever extends this later.
    void destroy();
}
