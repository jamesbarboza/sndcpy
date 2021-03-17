package com.rom1v.sndcpy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RecordService extends Service {

    private static final String TAG = "sndcpy";
    private static final String CHANNEL_ID = "sndcpy";
    private static final int NOTIFICATION_ID = 1;

    private static final String ACTION_RECORD = "com.rom1v.sndcpy.RECORD";
    private static final String ACTION_STOP = "com.rom1v.sndcpy.STOP";
    private static final String EXTRA_MEDIA_PROJECTION_DATA = "mediaProjectionData";

    private File audioCaptureDirectory;

    File recordedFile;
    FileOutputStream recordedFileStream;


    private static final int SAMPLE_RATE = 48000;
    //private static final int CHANNELS = 2;
    private static final int CHANNELS = 1;

    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private Thread recorderThread;

    public static void start(Context context, Intent data) {
        Intent intent = new Intent(context, RecordService.class);
        intent.setAction(ACTION_RECORD);
        intent.putExtra(EXTRA_MEDIA_PROJECTION_DATA, data);
        context.startForegroundService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Notification notification = createNotification(false);

        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_NONE);
        getNotificationManager().createNotificationChannel(channel);

        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (isRunning()) {
            return START_NOT_STICKY;
        }

        Intent data = intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_DATA);
        mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, data);
        if (mediaProjection != null) {
            try {
                startRecording();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        } else {
            Log.w(TAG, "Failed to capture audio");
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification createNotification(boolean established) {
        Notification.Builder notificationBuilder = new Notification.Builder(this, CHANNEL_ID);
        notificationBuilder.setContentTitle(getString(R.string.app_name));
        int textRes = established ? R.string.notification_forwarding : R.string.notification_waiting;
        notificationBuilder.setContentText(getText(textRes));
        notificationBuilder.setSmallIcon(R.drawable.ic_album_black_24dp);
        notificationBuilder.addAction(createStopAction());
        return notificationBuilder.build();
    }


    private Intent createStopIntent() {
        Intent intent = new Intent(this, RecordService.class);
        intent.setAction(ACTION_STOP);
        return intent;
    }

    private Notification.Action createStopAction() {
        Intent stopIntent = createStopIntent();
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_ONE_SHOT);
        Icon stopIcon = Icon.createWithResource(this, R.drawable.ic_close_24dp);
        String stopString = getString(R.string.action_stop);
        Notification.Action.Builder actionBuilder = new Notification.Action.Builder(stopIcon, stopString, stopPendingIntent);
        return actionBuilder.build();
    }

    private static AudioPlaybackCaptureConfiguration createAudioPlaybackCaptureConfig(MediaProjection mediaProjection) {
        AudioPlaybackCaptureConfiguration.Builder confBuilder = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection);
        confBuilder.addMatchingUsage(AudioAttributes.USAGE_MEDIA);
        confBuilder.addMatchingUsage(AudioAttributes.USAGE_GAME);
        confBuilder.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN);
        confBuilder.addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION);
        return confBuilder.build();
    }

    private static AudioFormat createAudioFormat() {
        AudioFormat.Builder builder = new AudioFormat.Builder();
        builder.setEncoding(AudioFormat.ENCODING_PCM_16BIT);
        builder.setSampleRate(SAMPLE_RATE);
        builder.setChannelMask(CHANNELS == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO);
        return builder.build();
    }

    private static AudioRecord createAudioRecord(MediaProjection mediaProjection) {
        AudioRecord.Builder builder = new AudioRecord.Builder();
        builder.setAudioFormat(createAudioFormat());
        builder.setBufferSizeInBytes(1024 * 1024);
        builder.setAudioPlaybackCaptureConfig(createAudioPlaybackCaptureConfig(mediaProjection));
        return builder.build();
    }

    private File createAudioFile() {
        audioCaptureDirectory = new File(getExternalFilesDir(null), "/AudioCaptures");
        if (!audioCaptureDirectory.exists()) {
            audioCaptureDirectory.mkdirs();
        }
        String timestamp = new SimpleDateFormat("dd-MM-yyyy-hh-mm-ss", Locale.US).format(new Date());
        String filename = "Capture-" + timestamp;
        Log.d(TAG,  "File saved at: " + audioCaptureDirectory.getAbsolutePath() );
        return new File(audioCaptureDirectory.getAbsolutePath() + "/" + filename + ".pcm");
    }


    private void startRecording() throws FileNotFoundException {
        final AudioRecord recorder = createAudioRecord(mediaProjection);

        recordedFile = createAudioFile();
        recordedFileStream = new FileOutputStream(recordedFile);

        recorderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    recorder.startRecording();
                    int BUFFER_MS = 15; // do not buffer more than BUFFER_MS milliseconds
                    byte[] buf = new byte[SAMPLE_RATE * CHANNELS * BUFFER_MS / 1000];
                    while (true) {
                        int r = recorder.read(buf, 0, buf.length);
                        recordedFileStream.write(buf, 0, r);
                    }

                } catch (IOException e) {
                    // ignore
                } finally {
                    recorder.stop();
                    stopSelf();
                    try {
                        recordedFileStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        recorderThread.start();
    }

    private boolean isRunning() {
        return recorderThread != null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopForeground(true);
        if (recorderThread != null) {
            recorderThread.interrupt();
            recorderThread = null;
        }
    }

    private NotificationManager getNotificationManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }
}