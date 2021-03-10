package dorkbox.gradle

/**
 * contains the different SWT maven configurations that we support
 */
enum class SwtType(val id: String) {
    // SEE: https://repo1.maven.org/maven2/org/eclipse/platform/

    // windows
    // org.eclipse.swt.win32.win32.x86
    // org.eclipse.swt.win32.win32.x86_64

    // linux
    // org.eclipse.swt.gtk.linux.x86
    // org.eclipse.swt.gtk.linux.x86_64

    // macoxs
    // org.eclipse.swt.cocoa.macosx.x86_64

    LINUX_32("org.eclipse.swt.gtk.linux.x86"),
    LINUX_64("org.eclipse.swt.gtk.linux.x86_64"),
    MAC_64("org.eclipse.swt.cocoa.macosx.x86_64"),
    WIN_32("org.eclipse.swt.win32.win32.x86"),
    WIN_64("org.eclipse.swt.win32.win32.x86_64");


    fun fullId(version: String): String {
        return "org.eclipse.platform:$id:$version"
    }
}
