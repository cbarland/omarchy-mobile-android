package dev.omamobile.host;
import android.view.Surface;
interface IAndroidShell {
    int uid() = 1;
    int create(int width, int height, in Surface surface, IBinder owner) = 2;
    String launch(int displayId, String component) = 3;
    void touch(int displayId, int action, float x, float y, long downTime) = 4;
    void key(int displayId, int keyCode, String text) = 5;
    void detach(int displayId) = 6;
    void resize(int displayId, int width, int height, in Surface surface) = 7;
    // Shizuku's standard user-service teardown transaction.
    void destroy() = 16777114;
}
