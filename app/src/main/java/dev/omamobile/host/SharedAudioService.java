package dev.omamobile.host;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

/** One bound process for the audio graph; display surfaces do not own playback. */
public final class SharedAudioService extends Service {
    static final int STATUS = 1;
    private final Messenger messenger = new Messenger(new Handler(android.os.Looper.getMainLooper(), message -> {
        if (message.what == STATUS && message.replyTo != null) {
            Messenger reply = message.replyTo;
            SharedAudioRuntime.get(this).status(text -> {
                Message result = Message.obtain(null, STATUS);
                Bundle data = new Bundle(); data.putString("status", text); result.setData(data);
                try { reply.send(result); }
                catch (RemoteException gone) { android.util.Log.d("OmamobileAudio", "Status caller disconnected"); }
            });
        }
        return true;
    }));
    @Override public IBinder onBind(Intent intent) {
        SharedAudioRuntime.get(this).start();
        return messenger.getBinder();
    }
    @Override public boolean onUnbind(Intent intent) {
        SharedAudioRuntime.get(this).stop();
        return false;
    }
}
