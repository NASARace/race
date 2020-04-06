package gov.nasa.race.swing;

import com.apple.eawt.FullScreenUtilities;
import com.apple.eawt.Application;
import java.awt.Window;

/**
 * utilities to work around bugs of Java 9+ implementations on macOS, namely:
 *
 * (1) "ScreenDevice fullscreen API freezes display"
 *
 * The supposedly portable way to programatically set fullscreen mode is
 *
 *     import java.awt.{GraphicsDevice,GraphicsEnvironment..}
 *     ..
 *     val device = GraphicsEnvironment.getLocalGraphicsEnvironment.getDefaultScreenDevice
 *     if (device.isFullScreenSupported) device.setFullScreenWindow(window) // null to reset
 *     ..
 *
 * However, this fails miserably on Java 9+ and (at least) macOS 10.14.6. It enters fullscreen
 * mode but popups/Dialogs don't work anymore and freeze the keyboard, requiring a full reboot
 * to get out of user-lockout. 
 *
 * The old com.apple.eawt.* still works but obviously clients would not compile outside of macOS builds,
 * and also cannot resort to reflection on Java 9+ since the required com.apple.eawt classes are not exported by
 * their module and cause warnings at startup time that cross module reflection will be forbidden in
 * future releases.
 *
 * Since the helper class does only compile on macOS we cannot include it into the normal RACE source tree
 * and hence turn this into a unmanaged, pre-built dependency that is only referenced if we are running on macOS 
 *
 * TODO - revisit in future Java releases to see if portable macOS fullscreen support is fixed. Remember cmd+opt+esc
 *
 * build with:
 *   javac --add-exports java.desktop/com.apple.eawt=ALL-UNNAMED --target 9 --source 9 -d . MacOSHelper.java
 *   jar --create --file macoshelper.jar gov
 *   rm -rf gov
 */
public class MacOSHelper {
  
  public static void enableMacOSFullScreen (Window window) {
    FullScreenUtilities.setWindowCanFullScreen(window,true);
  }

  public static void requestToggleMacOSFullScreen (Window window) {
    Application.getApplication().requestToggleFullScreen(window);
  }
}
