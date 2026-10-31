
package az.uniz.colses;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.widget.*;

public class MainActivity extends Activity {

    private static final String BUS = "bus16_outside_shout_out";
    private static final String TAG = "UniZColSes";
    private static final int RATE = 48000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView status;
    private TextView micLevel;
    private AudioManager manager;
    private AudioFocusRequest focus;

    private volatile boolean running = false;
    private Thread worker;
    private Button[] starts;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        manager = (AudioManager) getSystemService(AUDIO_SERVICE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(28, 24, 28, 24);
        layout.setBackgroundColor(0xff10202c);

        TextView title = new TextView(this);
        title.setText("UNI-Z ÇÖL SƏS");
        title.setTextSize(28);
        title.setTextColor(0xffffffff);
        layout.addView(title);

        status = new TextView(this);
        status.setTextSize(19);
        status.setTextColor(0xffffffff);
        status.setPadding(0, 20, 0, 20);
        layout.addView(status);

        micLevel = new TextView(this);
        micLevel.setText("Mikrofon: gözləyir");
        micLevel.setTextSize(17);
        micLevel.setTextColor(0xff9bd4ff);
        layout.addView(micLevel);

        starts = new Button[4];

        String[] labels = {
            "Qısa siqnal",
            "İki siqnal",
            "Xəbərdarlıq",
            "CANLI DANIŞ"
        };

        for (int i = 0; i < 4; i++) {
            final int mode = i;

            Button button = new Button(this);
            button.setText(labels[i]);
            button.setTextSize(18);

            layout.addView(
                button,
                new LinearLayout.LayoutParams(-1, 85)
            );

            button.setOnClickListener(v -> start(mode));
            starts[i] = button;
        }

        Button stop = new Button(this);
        stop.setText("DAYANDIR");
        stop.setTextSize(23);
        stop.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(0xffa52d3b)
        );

        layout.addView(
            stop,
            new LinearLayout.LayoutParams(-1, 90)
        );

        stop.setOnClickListener(v -> stop());

        TextView note = new TextView(this);
        note.setText(
            "Yalnız avtomobil dayanmış vəziyyətdə istifadə edin.\n" +
            "Canlı danışıq maksimum 30 saniyədir."
        );
        note.setTextSize(15);
        note.setTextColor(0xffcccccc);
        note.setPadding(0, 20, 0, 0);
        layout.addView(note);

        setContentView(layout);

        AudioDeviceInfo device = findBus();

        if (device == null) {
            status.setText("Çöl səs çıxışı tapılmadı.");
            for (Button b : starts) b.setEnabled(false);
        } else {
            status.setText("Çöl səs çıxışı hazırdır.");
        }
    }

    private AudioDeviceInfo findBus() {
        AudioDeviceInfo[] devices =
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);

        for (AudioDeviceInfo d : devices) {
            Log.i(TAG,
                "OUTPUT id=" + d.getId() +
                " address=" + d.getAddress()
            );

            if (BUS.equals(d.getAddress())) {
                return d;
            }
        }

        return null;
    }

    private AudioDeviceInfo findMic() {
        AudioDeviceInfo[] devices =
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS);

        AudioDeviceInfo fallback = null;

        for (AudioDeviceInfo d : devices) {
            Log.i(TAG,
                "INPUT id=" + d.getId() +
                " type=" + d.getType() +
                " address=" + d.getAddress()
            );

            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                return d;
            }

            if (fallback == null &&
                d.getType() != AudioDeviceInfo.TYPE_TELEPHONY) {
                fallback = d;
            }
        }

        return fallback;
    }

    private void show(String message) {
        handler.post(() -> status.setText(message));
    }

    private void start(int mode) {
        if (worker != null && worker.isAlive()) {
            show("Əvvəl DAYANDIR düyməsini basın.");
            return;
        }

        if (mode == 3 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO},
                10
            );
            return;
        }

        running = true;

        for (Button b : starts) {
            b.setEnabled(false);
        }

        worker = new Thread(
            () -> play(mode),
            "outside-audio"
        );

        worker.start();
    }

    private void stop() {
        running = false;
        show("Dayandırılır...");
    }

    private void write(AudioTrack track, short[] data) {
        int offset = 0;

        while (offset < data.length && running) {
            int n = track.write(
                data,
                offset,
                data.length - offset,
                AudioTrack.WRITE_BLOCKING
            );

            if (n <= 0) {
                throw new IllegalStateException(
                    "Səs ötürülmədi: " + n
                );
            }

            offset += n;
        }
    }

    private short limitSample(float value) {
        // Soft limiter: avoids severe digital clipping.
        float limited = (float) Math.tanh(value / 28000f);
        return (short) (limited * 30000f);
    }

    private void play(int mode) {
        AudioTrack track = null;
        AudioRecord recorder = null;

        String result = "Dayandırıldı.";

        try {
            AudioDeviceInfo output = findBus();

            if (output == null) {
                throw new IllegalStateException(
                    "Çöl səs çıxışı yoxdur."
                );
            }

            AudioAttributes attributes =
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(
                        AudioAttributes.CONTENT_TYPE_SPEECH
                    )
                    .build();

            focus = new AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> {
                    if (change < 0) running = false;
                })
                .build();

            if (manager.requestAudioFocus(focus)
                != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                throw new IllegalStateException(
                    "Səs sistemi hazır deyil."
                );
            }

            int outputBuffer = Math.max(
                19200,
                AudioTrack.getMinBufferSize(
                    RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
            );

            track = new AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    new AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setChannelMask(
                            AudioFormat.CHANNEL_OUT_STEREO
                        )
                        .setEncoding(
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        .build()
                )
                .setBufferSizeInBytes(outputBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException(
                    "Səs çıxışı açıla bilmədi."
                );
            }

            if (!track.setPreferredDevice(output)) {
                throw new IllegalStateException(
                    "Çöl çıxışı seçilə bilmədi."
                );
            }

            // Maximum Android AudioTrack software volume.
            track.setVolume(1.0f);

            short[] stereo = new short[1920];
            short[] mono = new short[960];

            track.play();

            // Check the actual route before sending sound.
            for (int i = 0; i < 20 && running; i++) {
                write(track, stereo);
            }

            AudioDeviceInfo routed = track.getRoutedDevice();

            if (routed == null ||
                !BUS.equals(routed.getAddress())) {
                throw new IllegalStateException(
                    "Səs çöl dinamikə yönəlmədi."
                );
            }

            if (mode == 3 && running) {
                int inputBuffer = Math.max(
                    19200,
                    AudioRecord.getMinBufferSize(
                        RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                );

                recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    inputBuffer
                );

                if (recorder.getState()
                    != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException(
                        "Mikrofon açıla bilmədi."
                    );
                }

                AudioDeviceInfo input = findMic();

                if (input != null) {
                    boolean selected =
                        recorder.setPreferredDevice(input);

                    Log.i(TAG,
                        "MIC selected=" + selected +
                        " address=" + input.getAddress()
                    );
                }

                recorder.startRecording();

                if (recorder.getRecordingState()
                    != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IllegalStateException(
                        "Mikrofon yazmağa başlamadı."
                    );
                }

                AudioDeviceInfo activeMic =
                    recorder.getRoutedDevice();

                Log.i(TAG,
                    "ACTIVE MIC=" +
                    (activeMic == null
                        ? "null"
                        : activeMic.getAddress())
                );
            }

            show(mode == 3
                ? "Canlı danışıq başladı."
                : "Çöl siqnalı göndərilir."
            );

            long frames = 0;
            int blocks = mode == 3 ? 1500 : 150;

            for (int block = 0;
                 block < blocks && running;
                 block++) {

                routed = track.getRoutedDevice();

                if (routed == null ||
                    !BUS.equals(routed.getAddress())) {
                    throw new IllegalStateException(
                        "Çöl səs bağlantısı kəsildi."
                    );
                }

                if (mode == 3) {
                    int n = recorder.read(
                        mono,
                        0,
                        mono.length,
                        AudioRecord.READ_BLOCKING
                    );

                    if (n < 0) {
                        throw new IllegalStateException(
                            "Mikrofon oxuma xətası: " + n
                        );
                    }

                    long sum = 0;

                    for (int i = 0; i < n; i++) {
                        sum += Math.abs((int) mono[i]);
                    }

                    int level = n > 0
                        ? (int) (sum / n)
                        : 0;

                    if (block % 15 == 0) {
                        final int shownLevel = level;

                        handler.post(() ->
                            micLevel.setText(
                                "Mikrofon səviyyəsi: " +
                                shownLevel
                            )
                        );
                    }

                    for (int i = 0; i < 960; i++) {
                        short sample = 0;

                        if (i < n) {
                            // Moderate microphone amplification
                            // with digital limiting.
                            sample = limitSample(
                                mono[i] * 2.0f
                            );
                        }

                        stereo[2 * i] = sample;
                        stereo[2 * i + 1] = sample;
                    }

                } else {
                    for (int i = 0; i < 960; i++, frames++) {
                        double time = frames / (double) RATE;

                        double phase = mode == 0
                            ? time
                            : time % (mode == 1 ? 1.2 : 0.6);

                        boolean on;

                        if (mode == 0) {
                            on = time < 0.5;
                        } else if (mode == 1) {
                            on = time < 1.2 &&
                                (phase < 0.25 ||
                                 (phase > 0.5 &&
                                  phase < 0.75));
                        } else {
                            on = phase < 0.25;
                        }

                        double envelope = Math.min(
                            1,
                            Math.min(
                                (phase % 0.25) * 100,
                                (0.25 - phase % 0.25) * 100
                            )
                        );

                        short sample = (short) (
                            on
                                ? 7000 *
                                  Math.max(0, envelope) *
                                  Math.sin(
                                      2 * Math.PI *
                                      (mode == 2 ? 660 : 880) *
                                      time
                                  )
                                : 0
                        );

                        stereo[2 * i] = sample;
                        stereo[2 * i + 1] = sample;
                    }
                }

                write(track, stereo);
            }

            result = "Sınaq bitdi.";

        } catch (Exception e) {
            Log.e(TAG, "Audio error", e);

            result = "XƏTA: " + e.getMessage();

        } finally {
            if (recorder != null) {
                try {
                    if (recorder.getRecordingState()
                        == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop();
                    }
                } catch (Exception ignored) {}

                recorder.release();
            }

            if (track != null) {
                try {
                    track.pause();
                    track.flush();
                    track.stop();
                } catch (Exception ignored) {}

                track.release();
            }

            if (focus != null) {
                manager.abandonAudioFocusRequest(focus);
                focus = null;
            }

            running = false;

            show(result);

            handler.post(() -> {
                boolean available = findBus() != null;

                for (Button b : starts) {
                    b.setEnabled(available);
                }
            });

            Log.i(TAG, "STOPPED " + result);
        }
    }

    @Override
    protected void onStop() {
        running = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] results
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            results
        );

        if (requestCode == 10) {
            if (results.length > 0 &&
                results[0] == PackageManager.PERMISSION_GRANTED) {
                start(3);
            } else {
                show("Mikrofon icazəsi verilməyib.");
            }
        }
    }
}
