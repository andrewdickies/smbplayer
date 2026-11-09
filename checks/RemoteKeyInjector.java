import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.lang.reflect.Method;

/** ADB shell test helper, run with app_process; never packaged in the player APK. */
public final class RemoteKeyInjector {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("keyCode repeatCount");
        int key = Integer.parseInt(args[0]);
        int repeat = Integer.parseInt(args[1]);
        if ((key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) || repeat < 0)
            throw new IllegalArgumentException("Only left/right with nonnegative repeat counts are supported");
        Class<?> inputManager = Class.forName("android.hardware.input.InputManager");
        Object manager = inputManager.getMethod("getInstance").invoke(null);
        Method inject = inputManager.getMethod("injectInputEvent", InputEvent.class, int.class);
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, repeat, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD);
        KeyEvent up = new KeyEvent(now, now + 1, KeyEvent.ACTION_UP, key, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD);
        if (!Boolean.TRUE.equals(inject.invoke(manager, down, 2))) throw new IllegalStateException("Key down rejected");
        if (!Boolean.TRUE.equals(inject.invoke(manager, up, 2))) throw new IllegalStateException("Key up rejected");
        System.out.println("Injected remote repeat " + repeat);
    }
}
