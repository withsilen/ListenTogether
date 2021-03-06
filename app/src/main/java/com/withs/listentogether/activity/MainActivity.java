package com.withs.listentogether.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.withs.listentogether.InstructionSocket;
import com.withs.listentogether.MyUtil;
import com.withs.listentogether.PlaylistSocket;
import com.withs.listentogether.R;
import com.withs.listentogether.adapter.WiFiGroupListAdapter;
import com.withs.listentogether.adapter.WiFiPeerListAdapter;
import com.withs.listentogether.receiver.WiFiDirectBroadcastReceiver;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;

//TODO 쓰레드 관련 리팩토링 필요
//TODO onCreate() 리팩토링 필요
//TODO 리스트뷰 및 리스트어댑터 리팩토링 필요
public class MainActivity extends Activity implements WifiP2pManager.PeerListListener {

    private static final String TAG = "MainActivity";

    // 명령소켓
    public InstructionSocket instructionSocket = InstructionSocket
            .getInstance();

    // 연결된 기기 리스트뷰 오너 식별용 변수
    public String ownerDeviceAddress;

    public Thread serverThread = null;
    public Thread clientThread = null;

    // WiFiP2p
    private WifiP2pManager mManager;
    private WifiP2pManager.Channel mChannel;
    private BroadcastReceiver mReceiver;

    private IntentFilter mIntentFilter;

    private boolean isGroupOwner = false;

    private WifiP2pInfo mInfo = null;
    private WifiP2pDevice mMyWifiP2pDevice;

    private Handler mHandler = new Handler();

    private AlertDialog.Builder mBuilder = null;

    private TextView mHintTextView;

    // 리스트뷰 어댑터들
    private ArrayAdapter<WifiP2pDevice> mConnectedListAdapter;
    private ArrayAdapter<WifiP2pDevice> mPeerListAdapter;

    // 리스트뷰
    private ListView mConnectedListView;
    private ListView mPeerListView;

    // 연결된기기와 피어들의 리스트
    private ArrayList<WifiP2pDevice> mListConnected = new ArrayList<WifiP2pDevice>();
    private ArrayList<WifiP2pDevice> mListPeers = new ArrayList<WifiP2pDevice>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 와이파이 다이렉트 초기화
        mManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        mChannel = mManager.initialize(this, getMainLooper(), null);
        mReceiver = new WiFiDirectBroadcastReceiver(mManager, mChannel, this);

        // 이전 연결을 없앤다
        instructionSocket.close();
        PlaylistSocket.getInstance().close();
        MyUtil.clearWifiP2pConnection(mManager, mChannel);

        // 인텐트필터 초기화
        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        mIntentFilter
                .addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        mIntentFilter
                .addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        // 연결된 기기 리스트뷰 어댑터 설정
        mConnectedListAdapter = new WiFiGroupListAdapter(this,
                R.layout.row_devices, mListConnected, this);
        mConnectedListView = (ListView) findViewById(R.id.connectedlist);
        mConnectedListView.setAdapter(mConnectedListAdapter);

        // 연결 가능한 기기 리스트뷰 어댑터 설정
        mPeerListAdapter = new WiFiPeerListAdapter(this, R.layout.row_devices,
                mListPeers);
        mPeerListView = (ListView) findViewById(R.id.peerlist);
        mPeerListView.setAdapter(mPeerListAdapter);

        // 연결 가능한 기기 리스트뷰 클릭리스너 등록 - 연결 처리
        mPeerListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {

            @Override
            public void onItemClick(AdapterView<?> parent, View view,
                                    int position, long id) {
                // 와이파이다이렉트 연결 설정
                WifiP2pConfig config = new WifiP2pConfig();
                WifiP2pDevice device = mListPeers.get(position);
                config.deviceAddress = device.deviceAddress;
                config.groupOwnerIntent = 15;

                // 와이파이다이렉트 연결 시도
                mManager.connect(mChannel, config, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        MyUtil.showShortToast(MainActivity.this, R.string.main_toast_requesting);
                    }
                    @Override
                    public void onFailure(int reason) {
                        setFailureReasonForConnect(reason);
                    }
                });
            }

        });

        initUI();

        //TODO 곡 카운트
        setCountText();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mReceiver, mIntentFilter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        instructionSocket.close();
        MyUtil.clearWifiP2pConnection(mManager, mChannel);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        switch (id) {
            case R.id.action_discover:
                discoverPeers();
                break;
            default:
                break;
        }

        return super.onOptionsItemSelected(item);
    }

    public void showStartButton() {
        if (isGroupOwner == true) {
            findViewById(R.id.main_button_start).setVisibility(View.VISIBLE);
        } else {
            findViewById(R.id.main_button_start).setVisibility(View.GONE);
        }
    }

    private void initUI() {
        int screenWidth = MyUtil.getScreenWidthPixels(this);
        int halfWidth = screenWidth * 8 / 16;

        //가운데 이미지 및 힌트의 세로를 화면 가로크기의 반으로
        ViewGroup.LayoutParams layoutParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, halfWidth);
        findViewById(R.id.main_main_image).setLayoutParams(layoutParams);
        findViewById(R.id.main_layout_top).setLayoutParams(layoutParams);

        // 걸어가는 고양이의 크기를 가로크기의 반으로
        RelativeLayout.LayoutParams layoutParams2 = new RelativeLayout.LayoutParams(halfWidth, halfWidth);
        layoutParams2.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        layoutParams2.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        layoutParams2.addRule(RelativeLayout.ALIGN_PARENT_END);
        findViewById(R.id.main_cats).setLayoutParams(layoutParams2);
    }

    public void startPlaybackActivity(View view) {
        sendStartMessage();
    }

    public void setIsGroupOwner(boolean groupOwner) {
        isGroupOwner = groupOwner;
    }

    public void setWiFiP2pInfo(WifiP2pInfo info) {
        mInfo = info;
    }

    public void setMyWiFiP2pDevice(WifiP2pDevice device) {
        mMyWifiP2pDevice = device;
    }

    public void setMyDeviceStatus(WifiP2pDevice myDevice) {
        if (myDevice != null) {
            // 내 기기가 그룹오너면 아이콘 변경
            if (isGroupOwner == true) {

                ImageView imageView = (ImageView) findViewById(R.id.my_icon);
                imageView.setImageResource(R.drawable.owner);

            } else if (myDevice.status == WifiP2pDevice.CONNECTED) {

                ImageView imageView = (ImageView) findViewById(R.id.my_icon);
                imageView.setImageResource(R.drawable.connected);

            } else {
                //TODO 싱글아이콘 변경
                ImageView imageView = (ImageView) findViewById(R.id.my_icon);
                imageView.setImageResource(R.drawable.single1);
            }

            TextView textView = (TextView) findViewById(R.id.my_name);
            textView.setText(myDevice.deviceName);

            textView = (TextView) findViewById(R.id.my_status);

            String status = MyUtil.getDeviceStatus(myDevice.status);
            textView.setText(status);

        }

    }

    public void setMyOwnerStatus() {

        if (isGroupOwner == true) {

            ImageView imageView = (ImageView) findViewById(R.id.my_icon);
            TextView textView = (TextView) findViewById(R.id.my_status);

            imageView.setImageResource(R.drawable.owner);
            textView.setText("GROUP OWNER");

        }

    }

    public void setGroupStatus() {

        mManager.requestGroupInfo(mChannel, new WifiP2pManager.GroupInfoListener() {

            @Override
            public void onGroupInfoAvailable(WifiP2pGroup group) {

                if (group != null) {

                    ownerDeviceAddress = group.getOwner().deviceAddress;
                    setMyOwnerStatus();
                    setConnectedList(group);

                } else {

                    mListConnected.clear();
                    mConnectedListAdapter.notifyDataSetChanged();

                }

            }

        });

        setMyDeviceStatus(mMyWifiP2pDevice);

    }

    public void setConnectedList(WifiP2pGroup group) {

        mListConnected.clear();

        if (isGroupOwner == false) {
            mListConnected.add(group.getOwner());
        }

        mListConnected.addAll(group.getClientList());
        mConnectedListAdapter.notifyDataSetChanged();

    }

    public void setPeerList(WifiP2pDeviceList peerList) {

        mListPeers.clear();
        mListPeers.addAll(peerList.getDeviceList());
        mPeerListAdapter.notifyDataSetChanged();

    }

    private void setFailureReasonForConnect(int reason) {
        switch (reason) {
            case WifiP2pManager.BUSY:
                MyUtil.showShortToast(MainActivity.this, R.string.main_failure_reason_busy);
                break;
            case WifiP2pManager.ERROR:
                MyUtil.showShortToast(MainActivity.this, R.string.main_failure_reason_error);
                break;
            case WifiP2pManager.P2P_UNSUPPORTED:
                MyUtil.showShortToast(MainActivity.this, R.string.main_failure_reason_p2p_unsupported);
                break;
        }
    }

    private void setFailureReasonForDiscover(int reason) {
        switch (reason) {
            case WifiP2pManager.BUSY:
                showWifiP2pDialog();
                break;
            case WifiP2pManager.ERROR:
                MyUtil.showShortToast(MainActivity.this, R.string.main_failure_reason_error);
                break;
            case WifiP2pManager.P2P_UNSUPPORTED:
                MyUtil.showShortToast(MainActivity.this, R.string.main_failure_reason_p2p_unsupported);
                break;
        }
    }

    public void setHintText(int id) {
        if (mHintTextView == null) {
            mHintTextView = (TextView) findViewById(R.id.main_text_hint);
        }
        mHintTextView.setText(id);
    }

    public void showWifiP2pDialog() {
        if (mBuilder == null) {
            DialogInterface.OnClickListener onClickListener = new DialogInterface.OnClickListener() {

                @Override
                public void onClick(DialogInterface dialog, int which) {
                    switch (which) {
                        case DialogInterface.BUTTON_POSITIVE:
                            WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
                            wifi.setWifiEnabled(true);
                            break;
                        case DialogInterface.BUTTON_NEUTRAL:
                            Intent intent = new Intent(Settings.ACTION_SETTINGS);
                            startActivity(intent);
                            break;
                        case DialogInterface.BUTTON_NEGATIVE:
                            break;
                    }
                }
            };

            mBuilder = new AlertDialog.Builder(this)
                    .setCancelable(true)
                    .setTitle(R.string.main_dialog_title)
                    .setMessage(R.string.main_dialog_message)
                    .setPositiveButton(R.string.main_dialog_positive,
                            onClickListener)
                    .setNeutralButton(R.string.main_dialog_neutral,
                            onClickListener)
                    .setNegativeButton(R.string.main_dialog_negative,
                            onClickListener);
        }

        mBuilder.show();
    }

    private void discoverPeers() {
        mManager.discoverPeers(mChannel, new WifiP2pManager.ActionListener() {

            @Override
            public void onSuccess() {
                MyUtil.showShortToast(MainActivity.this, R.string.main_toast_searching);
            }

            @Override
            public void onFailure(int reason) {
                setFailureReasonForDiscover(reason);
            }
        });
    }

    private void startPlaybackActivity() {
        Intent intent = new Intent(MainActivity.this, PlaybackActivity.class);
        startActivity(intent);
    }

    //TODO 리팩토링 필요
    private void sendStartMessage() {

        if (isGroupOwner == false) {
            return;
        }

        // 연결된 기기들의 소켓에게 재생 액티비티 시작 메시지를 전달
        for (int i = 0; i < instructionSocket.getListCustomSockets().size(); i++) {
            try {
                instructionSocket.getListCustomSockets().get(i)
                        .getDataOutputStream()
                        .writeBytes("StartPlaybackActivity\r\n");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // 재생 액티비티 시작
        startPlaybackActivity();

    }

    public void startServerThread() {
        serverThread = new Thread(new ServerThread());
        serverThread.start();
    }

    public void startClientThread() {
        clientThread = new Thread(new ClientThread());
        clientThread.start();
    }

    @Override
    public void onPeersAvailable(WifiP2pDeviceList peers) {
        setPeerList(peers);
    }

    private class ServerThread implements Runnable {

        @Override
        public void run() {

            ServerSocket serverSocket = null;

            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(8920));
            } catch (IOException e) {
                e.printStackTrace();
            }

            Socket socket = null;

            try {
                socket = MyUtil.initSocket(serverSocket.accept());
                serverSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            instructionSocket.addSocket(socket);

            mHandler.post(new Runnable() {

                @Override
                public void run() {

                    MyUtil.showShortToast(MainActivity.this, R.string.main_toast_connected_server);

                    setHintText(R.string.main_hint_connected_server);

                    showStartButton();

                }

            });

        }

    }

    private class ClientThread implements Runnable {

        @Override
        public void run() {

            Socket socket = MyUtil.makeConnectedSocket(
                    mInfo.groupOwnerAddress.getHostAddress(), 8920);

            instructionSocket.addSocket(socket);

            mHandler.post(new Runnable() {

                @Override
                public void run() {

                    MyUtil.showShortToast(MainActivity.this, R.string.main_toast_connected_client);

                    setHintText(R.string.main_hint_connected_client);

                }

            });

            String readMessage = null;

            try {
                readMessage = instructionSocket.getListCustomSockets().get(0)
                        .getBufferedReader().readLine();
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (readMessage.equals("StartPlaybackActivity")) {

                startPlaybackActivity();

            }

        }

    }

    //TODO
    private void setCountText() {

        new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    Socket socket = new Socket();
                    socket.setReuseAddress(true);
                    socket.connect(new InetSocketAddress("listentogether.iptime.org", 8920), 0);
//					socket.connect(new InetSocketAddress("localhost", 8920), 0);

//					System.out.println("연결성공");

                    DataOutputStream dos = new DataOutputStream(socket.getOutputStream());

                    dos.writeBytes("getCount\r\n");

//					System.out.println("getCount");

                    BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    final String result = br.readLine();

//					System.out.println(result);

                    mHandler.post(new Runnable() {

                        @Override
                        public void run() {
                            TextView tv = (TextView) findViewById(R.id.main_text_count);
//                            tv.setText("Listen Together를 통해 " + result + "곡이 공유됐습니다.");
                            tv.setText(R.string.main_count_one + result + R.string.main_count_two);
                        }
                    });


                } catch (UnknownHostException e) {
                    // TODO Auto-generated catch block
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                }
            }
        }).start();

    }
}
