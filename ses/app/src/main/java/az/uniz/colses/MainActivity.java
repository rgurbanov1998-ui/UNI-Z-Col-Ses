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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private volatile boolean running;
    private Thread worker;
    private AudioManager manager;
    private AudioFocusRequest focus;
    private Button[] starts;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        manager = (AudioManager)getSystemService(AUDIO_SERVICE);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40,24,40,24);
        layout.setBackgroundColor(0xff10202c);
        TextView title = new TextView(this);
        title.setText("UNI-Z Çöl Səs · Sınaq"); title.setTextSize(28);
        layout.addView(title);
        status = new TextView(this); status.setTextSize(20);
        status.setPadding(0,16,0,16); layout.addView(status);
        LinearLayout row = new LinearLayout(this); layout.addView(row);
        starts = new Button[4];
        String[] labels = {"Qısa siqnal", "İki siqnal", "Xəbərdarlıq", "Canlı danış"};
        for (int i=0;i<4;i++) {
            final int mode=i;
            Button b=new Button(this); b.setText(labels[i]); b.setTextSize(18);
            row.addView(b,new LinearLayout.LayoutParams(0,100,1));
            b.setOnClickListener(v -> start(mode)); starts[i]=b;
        }
        Button stop=new Button(this); stop.setText("DAYANDIR"); stop.setTextSize(24);
        stop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffa52d3b));
        layout.addView(stop,new LinearLayout.LayoutParams(-1,90));
        stop.setOnClickListener(v -> stop());
        TextView note=new TextView(this);
        note.setText("Yalnız dayanmış avtomobildə. Səslər 3 saniyə, canlı danışıq ən çox 30 saniyə.\nFiziki çöl səsi hələ təsdiqlənməyib.");
        note.setTextSize(16); note.setPadding(0,16,0,0); layout.addView(note);
        setContentView(layout);
        AudioDeviceInfo device=findBus();
        status.setText(device==null ? "Çöl danışıq çıxışı əlçatan deyil." : "Çöl danışıq çıxışı tapıldı. Sınağa hazırdır.");
        if(device==null) for(Button b:starts)b.setEnabled(false);
        if(getIntent().getBooleanExtra("probe",false)) handler.postDelayed(()->start(4),400);
    }
    private AudioDeviceInfo findBus() {
        for(AudioDeviceInfo d:manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            Log.i(TAG,"output id="+d.getId()+" address="+d.getAddress());
            if(BUS.equals(d.getAddress()))return d;
        }
        return null;
    }
    private void show(String text) { handler.post(()->status.setText(text)); }
    private void start(int mode) {
        if(worker!=null && worker.isAlive()) { show("Əvvəlcə DAYANDIR düyməsini basın."); return; }
        if(mode==3 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10); return;
        }
        running=true;
        for(Button b:starts)b.setEnabled(false);
        worker=new Thread(()->play(mode),"outside-audio"); worker.start();
    }
    private void stop() { running=false; show("Dayandırılır…"); }
    private void play(int mode) {
        AudioTrack track=null; AudioRecord recorder=null;
        String result="Dayandırıldı.";
        try {
            AudioDeviceInfo device=findBus();
            if(device==null)throw new IllegalStateException("Çöl çıxışı əlçatan deyil.");
            AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).setOnAudioFocusChangeListener(change->{if(change<0)running=false;}).build();
            if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw new IllegalStateException("Səs sistemi hazır deyil.");
            int size=Math.max(19200,AudioTrack.getMinBufferSize(48000,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT));
            track=new AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build();
            if(track.getState()!=AudioTrack.STATE_INITIALIZED || !track.setPreferredDevice(device))throw new IllegalStateException("Sistem çöl çıxışını qəbul etmədi.");
            track.setVolume(0.18f);
            short[] stereo=new short[1920];
            track.play();
            // Establish and verify the route with silence before emitting audio.
            for(int i=0;i<20 && running;i++)write(track,stereo);
            AudioDeviceInfo routed=track.getRoutedDevice();
            Log.i(TAG,"ROUTE="+(routed==null?"null":routed.getAddress()));
            if(routed==null || !BUS.equals(routed.getAddress()))throw new IllegalStateException("Sistem səsi çöl kanalına yönəltmədi.");
            if(mode==4) { result="Səssiz marşrut testi keçdi; fiziki səs təsdiqlənməyib."; return; }
            if(mode==3 && running) {
                int rs=Math.max(9600,AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT));
                recorder=new AudioRecord(MediaRecorder.AudioSource.MIC,48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,rs);
                if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Mikrofon açıla bilmədi.");
                recorder.startRecording();
                if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("Mikrofon istifadəsinə icazə verilmədi.");
            }
            show(mode==3?"Canlı danışıq · DAYANDIR ilə bitirin":"Siqnal çöl kanalına göndərilir…");
            short[] mono=new short[960]; long frames=0; int limit=mode==3?1500:150;
            for(int block=0;block<limit && running;block++) {
                routed=track.getRoutedDevice();
                if(routed==null || !BUS.equals(routed.getAddress()))throw new IllegalStateException("Çöl səs bağlantısı kəsildi.");
                if(mode==3) {
                    int n=recorder.read(mono,0,mono.length,AudioRecord.READ_BLOCKING);
                    if(n<0)throw new IllegalStateException("Mikrofon oxunmadı.");
                    for(int i=0;i<960;i++)stereo[2*i]=stereo[2*i+1]=i<n?mono[i]:0;
                } else {
                    for(int i=0;i<960;i++,frames++) {
                        double time=frames/48000.0;
                        double phase=mode==0?time:time%(mode==1?1.2:0.6);
                        boolean on=mode==0?time<0.5:mode==1?(time<1.2&&(phase<0.25||(phase>0.5&&phase<0.75))):phase<0.25;
                        double envelope=Math.min(1,Math.min((phase%0.25)*100,(0.25-phase%0.25)*100));
                        short s=(short)(on?7000*Math.max(0,envelope)*Math.sin(2*Math.PI*(mode==2?660:880)*time):0);
                        stereo[2*i]=stereo[2*i+1]=s;
                    }
                }
                write(track,stereo);
            }
            result="Dayandırıldı. Çöldə eşidilmə ayrıca təsdiqlənməlidir.";
        } catch(Exception e) { Log.e(TAG,"audio failed",e); result="İşə düşmədi: "+e.getMessage(); }
        finally {
            if(recorder!=null){try{recorder.stop();}catch(Exception ignored){} recorder.release();}
            if(track!=null){try{track.pause();track.flush();track.stop();}catch(Exception ignored){}track.release();}
            if(focus!=null)manager.abandonAudioFocusRequest(focus);
            running=false; show(result); handler.post(()->{for(Button b:starts)b.setEnabled(findBus()!=null);});
            Log.i(TAG,"STOPPED "+result);
        }
    }
    private void write(AudioTrack track,short[] data) {
        int off=0;
        while(off<data.length && running) {
            int n=track.write(data,off,data.length-off,AudioTrack.WRITE_BLOCKING);
            if(n<=0)throw new IllegalStateException("Səs ötürülmədi: "+n);
            off+=n;
        }
    }
    @Override protected void onStop(){running=false;super.onStop();}
    @Override protected void onDestroy(){running=false;super.onDestroy();}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==10){if(g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)start(3);else show("Mikrofon icazəsi verilməyib.");}}
}
