import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** 运行于 shell：只注入系统触摸事件，不访问应用进程、偏好或业务入口。 */
public final class HlsGestureInput {
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
        // PARTICLE_INPUT_ASYNC=1：异步注入（不等应用处理完）、每 4 ms 一个 MOVE，相当于 240 Hz 上报的触摸屏。
        // 缺省的同步注入每个事件都要等应用在下一帧消费掉才返回，注入节奏会被拴在应用帧率上（约 60 个 MOVE / 秒）。
        boolean async = "1".equals(System.getenv("PARTICLE_INPUT_ASYNC"));
        mode = async ? 0 : 2;
        long step = async ? 4 : 8;
        float x = Float.parseFloat(args[0]), y = Float.parseFloat(args[1]);
        long down = SystemClock.uptimeMillis();
        System.out.println("HLS_DOWN " + System.nanoTime() + " " + SystemClock.elapsedRealtimeNanos() + " " + down); send(manager, inject, down, MotionEvent.ACTION_DOWN, x, y);
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
                    SystemClock.sleep(Math.min(step, deadline-now));
                }
                x = endX; y = endY;
            }
        } finally {
            System.out.println("HLS_UP " + System.nanoTime() + " " + SystemClock.elapsedRealtimeNanos()); send(manager, inject, down, args[2].equals("CANCEL") ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP, x, y);
        }
        // 同一进程中再次按下，避免两次 adb/app_process 启动间隔掩盖短距离惯性急停。
        String stopAfter = System.getenv("HLS_STOP_AFTER_MS");
        if (stopAfter != null) {
            long delay = Long.parseLong(stopAfter);
            if (!args[2].equals("UP") || delay < 0 || delay > 1000) throw new IllegalArgumentException("stop delay");
            SystemClock.sleep(delay);
            long stopDown = SystemClock.uptimeMillis();
            System.out.println("HLS_STOP_DOWN " + System.nanoTime() + " " + SystemClock.elapsedRealtimeNanos());
            send(manager, inject, stopDown, MotionEvent.ACTION_DOWN, x, y);
            SystemClock.sleep(120);
            // 不触发清单项点击。
            send(manager, inject, stopDown, MotionEvent.ACTION_CANCEL, x, y);
            System.out.println("HLS_STOP_CANCEL " + System.nanoTime() + " " + SystemClock.elapsedRealtimeNanos());
        }
    }

    private static int mode = 2;

    private static void send(Object manager, Method inject, long down, int action, float x, float y) throws Exception {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            // 缺省 WAIT_FOR_FINISH（2），异步为 ASYNC（0）；都与系统 input 一样经 InputDispatcher 派发。
            if (!((Boolean)inject.invoke(manager, event, mode))) throw new IllegalStateException("Input rejected");
        } finally { event.recycle(); }
    }
}
