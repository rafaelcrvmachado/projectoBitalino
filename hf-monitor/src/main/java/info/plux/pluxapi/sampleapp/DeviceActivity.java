package info.plux.pluxapi.sampleapp;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.*;
import android.util.Log;
import android.view.View;
import android.widget.*;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import org.openintents.openpgp.util.OpenPgpApi;
import org.openintents.openpgp.util.OpenPgpServiceConnection;

import info.plux.pluxapi.Communication;
import info.plux.pluxapi.bitalino.*;
import info.plux.pluxapi.bitalino.bth.OnBITalinoDataAvailable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;

import static info.plux.pluxapi.Constants.*;

public class DeviceActivity extends Activity implements OnBITalinoDataAvailable, View.OnClickListener {
    private final String TAG = this.getClass().getSimpleName();

    public final static String EXTRA_DEVICE = "info.plux.pluxapi.sampleapp.DeviceActivity.EXTRA_DEVICE";
    public final static String FRAME = "info.plux.pluxapi.sampleapp.DeviceActivity.Frame";

    private BluetoothDevice bluetoothDevice;

    private BITalinoCommunication bitalino;
    private boolean isBITalino2 = false;


    private Handler handler;

    private States currentState = States.DISCONNECTED;

    private boolean isUpdateReceiverRegistered = false;

    // settings
    private int sampleRate = 100; // Hz
    private int bufferSize = 10; //in seconds
    private int refreshSize = 1; // in seconds
    private double msys = -1.63;
    private double bsys = 581.1;
    private double mdia = -2.81;
    private double bdia = 628.04;
    private boolean encrypt = true;
    private OpenPgpServiceConnection mServiceConnection;

    private int frameCounter = sampleRate * refreshSize;
    private int refreshCounter = bufferSize / refreshSize; //5
    private LinkedList<Integer> ecgBuffer;
    private LinkedList<Integer> ppgBuffer;
    private LineGraphSeries<DataPoint> ecgSeries;
    private LineGraphSeries<DataPoint> ppgSeries;
    private String fileToWrite = "samplerate:" + sampleRate + "\n";
    

    /*
     * UI elements
     */
    private TextView nameTextView;
    private TextView stateTextView;

    //meu
    private GraphView ecgGraph;
    private GraphView ppgGraph;

    private Button connectButton;
    private Button disconnectButton;
    private Button startButton;
    private Button stopButton;
    //botões estão aqui

    private Button settingsButton;

    private TextView bpTextView;
    private TextView hrTextView;
    private TextView prTextView;
    private TextView rrTextView;

    private TextView resultsTextView;


    private float alpha = 0.25f;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().hasExtra(EXTRA_DEVICE)) {
            bluetoothDevice = getIntent().getParcelableExtra(EXTRA_DEVICE);
        }

        setContentView(R.layout.activity_main);

        initView();
        setUIElements();
        mServiceConnection = new OpenPgpServiceConnection(this, "org.sufficientlysecure.keychain");
        mServiceConnection.bindToService();

        handler = new Handler(getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                Bundle bundle = msg.getData();
                BITalinoFrame frame = bundle.getParcelable(FRAME);

                Log.d(TAG, frame.toString());

                if (frame != null) { //BITalino
                    ecgBuffer.addLast(frame.getAnalog(1));
                    ecgBuffer.removeFirst();
                    ppgBuffer.addLast(frame.getAnalog(5));
                    ppgBuffer.removeFirst();
                    if (frameCounter == sampleRate*refreshSize) {
                        refreshUi();
                        frameCounter = 0;
                    }
                    frameCounter++;
//                    resultsTextView.setText(frame.toString());
                }
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();

        registerReceiver(updateReceiver, makeUpdateIntentFilter());
        isUpdateReceiverRegistered = true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (isUpdateReceiverRegistered) {
            unregisterReceiver(updateReceiver);
            isUpdateReceiverRegistered = false;
        }

        if (bitalino != null) {
            bitalino.closeReceivers();
            try {
                bitalino.disconnect();
            } catch (BITalinoException e) {
                e.printStackTrace();
            }
        }
        if (mServiceConnection != null) {
            mServiceConnection.unbindFromService();
        }
    }

    /*
     * UI elements
     */
    private void initView() {
        nameTextView = (TextView) findViewById(R.id.device_name_text_view);

        stateTextView = (TextView) findViewById(R.id.state_text_view);

        connectButton = (Button) findViewById(R.id.connect_button);
        disconnectButton = (Button) findViewById(R.id.disconnect_button);
        startButton = (Button) findViewById(R.id.start_button);
        stopButton = (Button) findViewById(R.id.stop_button);

        //meu
        ecgGraph = (GraphView) findViewById(R.id.ecggraph);
        ppgGraph = (GraphView) findViewById(R.id.ppggraph);
        ecgGraph.getGridLabelRenderer().setVerticalLabelsVisible(false);
        ecgGraph.getGridLabelRenderer().setHorizontalLabelsVisible(false);
        ppgGraph.getGridLabelRenderer().setVerticalLabelsVisible(false);
        ppgGraph.getGridLabelRenderer().setHorizontalLabelsVisible(false);

        bpTextView = (TextView) findViewById(R.id.bpTv);
        hrTextView = (TextView) findViewById(R.id.hrTv);
        prTextView = (TextView) findViewById(R.id.prTv);
        rrTextView = (TextView) findViewById(R.id.rrTv);

        settingsButton = (Button) findViewById(R.id.settings_button);
        resultsTextView = (TextView) findViewById(R.id.results_text_view);
    }

    private void setUIElements() {
        if (bluetoothDevice.getName() == null) {
            nameTextView.setText("BITalino");
        } else {
            nameTextView.setText(bluetoothDevice.getName());
        }
        stateTextView.setText(currentState.name());

        Communication communication = Communication.getById(bluetoothDevice.getType());
        Log.d(TAG, "Communication: " + communication.name());
        if (communication.equals(Communication.DUAL)) {
            communication = Communication.BTH;
        }

        bitalino = new BITalinoCommunicationFactory().getCommunication(communication, this, this);

        connectButton.setOnClickListener(this);
        disconnectButton.setOnClickListener(this);
        startButton.setOnClickListener(this);
        stopButton.setOnClickListener(this);
        settingsButton.setOnClickListener(this);
    }

    /*
     * Local Broadcast
     */
    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (ACTION_STATE_CHANGED.equals(action)) {
                String identifier = intent.getStringExtra(IDENTIFIER);
                States state = States.getStates(intent.getIntExtra(EXTRA_STATE_CHANGED, 0));

                Log.i(TAG, identifier + " -> " + state.name());

                stateTextView.setText(state.name());

                switch (state) {
                    case NO_CONNECTION:
                        break;
                    case LISTEN:
                        break;
                    case CONNECTING:
                        break;
                    case CONNECTED:
                        break;
                    case ACQUISITION_TRYING:
                        break;
                    case ACQUISITION_OK:
                        break;
                    case ACQUISITION_STOPPING:
                        break;
                    case DISCONNECTED:
                        break;
                    case ENDED:
                        break;

                }
            } else if (ACTION_DATA_AVAILABLE.equals(action)) {
                if (intent.hasExtra(EXTRA_DATA)) {
                    Parcelable parcelable = intent.getParcelableExtra(EXTRA_DATA);
                    if (parcelable.getClass().equals(BITalinoFrame.class)) { //BITalino
                        BITalinoFrame frame = (BITalinoFrame) parcelable;
                        resultsTextView.setText(frame.toString());
                    }
                }
            } else if (ACTION_COMMAND_REPLY.equals(action)) {
                String identifier = intent.getStringExtra(IDENTIFIER);

                if (intent.hasExtra(EXTRA_COMMAND_REPLY) && (intent.getParcelableExtra(EXTRA_COMMAND_REPLY) != null)) {
                    Parcelable parcelable = intent.getParcelableExtra(EXTRA_COMMAND_REPLY);
                    if (parcelable.getClass().equals(BITalinoState.class)) { //BITalino
                        Log.d(TAG, ((BITalinoState) parcelable).toString());
                        resultsTextView.setText(parcelable.toString());
                    } else if (parcelable.getClass().equals(BITalinoDescription.class)) { //BITalino
                        isBITalino2 = ((BITalinoDescription) parcelable).isBITalino2();
                        resultsTextView.setText("isBITalino2: " + isBITalino2 + "; FwVersion: " + String.valueOf(((BITalinoDescription) parcelable).getFwVersion()));
                    }
                }
            }
        }
    };

    private IntentFilter makeUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_STATE_CHANGED);
        intentFilter.addAction(ACTION_DATA_AVAILABLE);
        intentFilter.addAction(ACTION_EVENT_AVAILABLE);
        intentFilter.addAction(ACTION_DEVICE_READY);
        intentFilter.addAction(ACTION_COMMAND_REPLY);
        return intentFilter;
    }

    /*
     * Callbacks
     */

    @Override
    public void onBITalinoDataAvailable(BITalinoFrame bitalinoFrame) {
        Message message = handler.obtainMessage();
        Bundle bundle = new Bundle();
        bundle.putParcelable(FRAME, bitalinoFrame);
        message.setData(bundle);
        handler.sendMessage(message);
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.connect_button:
                try {
                    bitalino.connect(bluetoothDevice.getAddress());
                } catch (BITalinoException e) {
                    e.printStackTrace();
                }
                break;
            case R.id.disconnect_button:
                try {
                    bitalino.disconnect();
                } catch (BITalinoException e) {
                    e.printStackTrace();
                }
                break;
            case R.id.start_button:
                try {
                    bitalino.start(new int[]{1, 5}, sampleRate);
                } catch (BITalinoException e) {
                    e.printStackTrace();
                }
                ecgBuffer = new LinkedList<>();
                ppgBuffer = new LinkedList<>();
                for (int i = 0; i < sampleRate * bufferSize; i++) {
                    ecgBuffer.add(0);
                    ppgBuffer.add(0);
                }
                break;
            case R.id.stop_button:
                try {
                    bitalino.stop();
                    writeFile(this, "bit"+System.currentTimeMillis(),fileToWrite);
                } catch (BITalinoException e) {
                    e.printStackTrace();
                }
                break;
            case R.id.settings_button:
                try {
                    bitalino.state();
                } catch (BITalinoException e) {
                    e.printStackTrace();
                }
                break;
        }
    }

    private void refreshUi() {
        ecgSeries = new LineGraphSeries<DataPoint>();
        for (int i = 0; i < ecgBuffer.size(); i++) {
            DataPoint d = new DataPoint((i) / 1, ecgBuffer.get(i) / 1);
            ecgSeries.appendData(d, true, 60000);

        }
        ecgGraph.removeAllSeries();
        ecgGraph.addSeries(ecgSeries);

        ppgSeries = new LineGraphSeries<DataPoint>();

        for (int i = 0; i < ppgBuffer.size(); i++) {
            DataPoint d = new DataPoint((i) / 1, ppgBuffer.get(i) / 1);
            ppgSeries.appendData(d, true, 60000);
            if (refreshCounter == bufferSize / refreshSize) {
                fileToWrite = fileToWrite + ppgBuffer.get(i) + "\n";
            }

        }
        if (refreshCounter == bufferSize / refreshSize) {
            refreshCounter = 0;
        } else {
            refreshCounter++;
        }
        ppgGraph.removeAllSeries();
        ppgGraph.addSeries(ppgSeries);
        calcPTT();
        calcHR();
        calcPR();


    }

    private void calcPR() {
        List<Integer> ppgPeaks = getPeaks(ppgBuffer);
        int sumPR = 0;
        int count = 0;
        if (ppgPeaks.size() > 0) {
            for (int i = ppgPeaks.size() - 2; i > 0; i--) {
                sumPR = sumPR + (ppgPeaks.get(i) - ppgPeaks.get(i - 1));
                count++;
            }
            if (count > 0) {
                int avgPR = sampleRate * 60 * sumPR / count;
                hrTextView.setText(avgPR + " bpm");
            }

        }

    }
    private void calcHR() {
        List<Integer> ecgPeaks = getPeaks(ecgBuffer);
        int sumHR = 0;
        int count = 0;
        if (ecgPeaks.size() > 0) {
            for (int i = ecgPeaks.size() - 2; i > 0; i--) {
                sumHR = sumHR + (ecgPeaks.get(i) - ecgPeaks.get(i - 1));
                count++;
            }
            if (count > 0) {
                int avgPR = sampleRate * 60 * sumHR / count;
                prTextView.setText(avgPR + " bpm");
            }

        }

    }

    private void calcPTT() {
        List<Integer> ecgPeaks = getPeaks(ecgBuffer);
        List<Integer> ppgPeaks = getPeaks(ppgBuffer);
        if (ecgPeaks.size() < 2 || ppgPeaks.size() < 2) {
        } else {
            int k = ppgPeaks.get(ppgPeaks.size() - 2); //last index of a peak
            int l = ecgPeaks.get(ecgPeaks.size() - 2);
            if (k < l) {
                k = ppgPeaks.get(ppgPeaks.size() - 1);
            }
            int ptt = (k - l) / (1000 * sampleRate);
            rrTextView.setText(ptt + " ms");
            bpTextView.setText(ptt*mdia+bdia+"/"+ptt*msys+bdia+" mmHg");
        }


    }

    private List<Integer> getPeaks(LinkedList<Integer> buffer) {
        int A = buffer.get(indexOfMax(buffer, buffer.size() - 1, buffer.size() - 1 - 2 * sampleRate));
        int threshold = 0;
        if (A < 1000 && A > 10) {
            threshold = A;
        }
        int start = bufferSize * sampleRate - 1;
        int end = (int) (start - 0.2 * sampleRate);
        int maxA = 0;
        int indexA = -1;
        List<Integer> Peaks = new ArrayList<Integer>();
        while (end > 0) {
            end = (int) (start - 0.2 * sampleRate);
            if (end < 0) {
                end = 0;
            }
            for (int i = start - 1; i >= end; i--) {
                indexA = indexOfMax(buffer, start, end);
                if (indexA == buffer.size() - 1) break;
                if (indexA < 0) break;
                maxA = buffer.get(indexA);
                if (maxA < 1.25 * threshold && maxA > 0.75 * threshold && maxA > buffer.get(indexA + 1) && maxA > buffer.get(indexA - 1)) {
                    threshold = maxA;
                    start = indexA;
                    Peaks.add(indexA);
                    break;
                }
            }
            start = end - 1;
        }
        return Peaks;
    }

    private int indexOfMax(LinkedList<Integer> buffer, int start, int end) {
        int max = 0;
        int index = -1;
        for (int i = start; i >= end; i--) {
            if (buffer.get(i) > max) {
                max = buffer.get(i);
                index = i;
            }
        }
        return index;
    }

    public void writeFile(Context Context, String sFileName, String sBody) {

        if (encrypt){
            Intent data = new Intent();
            data.setAction(OpenPgpApi.ACTION_ENCRYPT);
            data.putExtra(OpenPgpApi.EXTRA_USER_IDS, new String[]{"rafaelcrvmachado@gmail.com"});
            data.putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true);

            try {
                InputStream is = new ByteArrayInputStream(sBody.getBytes("UTF-8"));
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                OpenPgpApi api = new OpenPgpApi(this, mServiceConnection.getService());
                Intent result = api.executeApi(data, is, os);
                sBody = result.getDataString();
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
        File dir = new File(Context.getFilesDir(), "bitalino");
        if (!dir.exists()) {
            dir.mkdir();
        }

        try {
            File gpxfile = new File(dir, sFileName);
            FileWriter writer = new FileWriter(gpxfile);
            writer.append(sBody);
            writer.flush();
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
