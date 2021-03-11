package dorkbox.gradle

/**
 * contains the different SWT maven configurations that we support
 */
enum class SwtType(val id: String, val is64bit: Boolean) {
    // SEE: https://repo1.maven.org/maven2/org/eclipse/platform/

    // windows
    // org.eclipse.swt.win32.win32.x86
    // org.eclipse.swt.win32.win32.x86_64

    // linux
    // org.eclipse.swt.gtk.linux.x86
    // org.eclipse.swt.gtk.linux.x86_64

    // macoxs
    // org.eclipse.swt.cocoa.macosx.x86_64

    // This is really SWT version 4.xx? no idea how the internal versions are tracked
    // 4.4 is the oldest version that works with us, and the release of SWT is sPecIaL!
    // version 3.108.0 is the MOST RECENT version supported by x86. Newer versions no longer support x86
    LINUX_32("org.eclipse.swt.gtk.linux.x86", false),
    LINUX_64("org.eclipse.swt.gtk.linux.x86_64", true),
    MAC_64("org.eclipse.swt.cocoa.macosx.x86_64", true),
    WIN_32("org.eclipse.swt.win32.win32.x86", false),
    WIN_64("org.eclipse.swt.win32.win32.x86_64", true);


    fun fullId(version: String): String {
        return "org.eclipse.platform:$id:$version"
    }
}
