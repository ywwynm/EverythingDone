import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** 运行于 shell：只注入系统触摸事件，不访问应用进程、偏好或业务入口。 */
public final class ParticleGestureInput {
    public static void main(String[] args) throws Exception {
        if (args.length < 6 || (args.length - 3) % 3 != 0) {
            throw new IllegalArgumentException("startX startY UP|CANCEL (endX endY durationMs)+");
        }
        // Android 14 起注入入口搬到了 InputManagerGlobal；12/13 上只有 InputManager.getInstance()。
        // 华为 MatePad Pro 13.2（Android 12）上找不到前者，整条驱动会以 137 收场。
        Object manager;
        Method inject;
        try {
            Class<?> type = Class.forName("android.hardware.input.InputManagerGlobal");
            manager = type.getMethod("getInstance").invoke(null);
            inject = type.getMethod("injectInputEvent", InputEvent.class, int.class);
        } catch (ClassNotFoundException absent) {
            Class<?> type = Class.forName("android.hardware.input.InputManager");
            manager = type.getMethod("getInstance").invoke(null);
            inject = type.getMethod("injectInputEvent", InputEvent.class, int.class);
        }
        float x = Float.parseFloat(args[0]), y = Float.parseFloat(args[1]);
        long down = SystemClock.uptimeMillis();
        send(manager, inject, down, MotionEvent.ACTION_DOWN, x, y);
        try {
            for (int i = 3; i < args.length; i += 3) {
                float endX = Float.parseFloat(args[i]), endY = Float.parseFloat(args[i+1]);
                long duration = Long.parseLong(args[i+2]);
                if (duration < 8 || duration > 4000) throw new IllegalArgumentException("duration");
                long start = SystemClock.uptimeMillis();
                long deadline = start + duration;
                while (true) {
                    long now = SystemClock.uptimeMillis();
                    float phase = Math.min(1f, (float)(now-start)/duration);
                    send(manager, inject, down, MotionEvent.ACTION_MOVE,
                            x+(endX-x)*phase, y+(endY-y)*phase);
                    if (now >= deadline) break;
                    SystemClock.sleep(Math.min(8, deadline-now));
                }
                x = endX; y = endY;
            }
        } finally {
            send(manager, inject, down, args[2].equals("CANCEL") ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP, x, y);
        }
    }

    private static void send(Object manager, Method inject, long down, int action, float x, float y) throws Exception {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            // WAIT_FOR_FINISH，与系统 input 一样经 InputDispatcher 派发。
            if (!((Boolean)inject.invoke(manager, event, 2))) throw new IllegalStateException("Input rejected");
        } finally { event.recycle(); }
    }
}
