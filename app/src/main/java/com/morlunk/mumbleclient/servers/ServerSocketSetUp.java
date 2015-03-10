package com.morlunk.mumbleclient.servers;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.morlunk.jumble.model.Server;
import com.morlunk.mumbleclient.R;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * A simple {@link Fragment} subclass.
 * Activities that contain this fragment must implement the
 * {@link ServerSocketSetUp.OnFragmentInteractionListener} interface
 * to handle interaction events.
 * Use the {@link ServerSocketSetUp#newInstance} factory method to
 * create an instance of this fragment.
 */
public class ServerSocketSetUp extends Fragment {
    // TODO: Rename parameter arguments, choose names that match
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_PARAM2 = "param2";

    // TODO: Rename and change types of parameters
    private String mParam1;
    private String mParam2;

    private OnFragmentInteractionListener mListener;

    // Represents name of the server --Added by Carabes
    public static final String PROTOCOL_SCHEME_RFCOMM = "btspp";

    private Handler m_handler = new Handler();

    // Get Default Adapter
    private BluetoothAdapter m_bluetooth = BluetoothAdapter.getDefaultAdapter();
    // Server
    private BluetoothServerSocket m_serverSocket;

    // Client request
    public String btClientRequest;

    // Client Request notification
    public boolean masterRequest = false;

    Semaphore masterRequestSem = new Semaphore(1, true);

    // Thread-Listen
    private Thread m_BTserverThread= new Thread() {
        public void run() {
            listenBTclientReq();
        };
    };

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param param1 Parameter 1.
     * @param param2 Parameter 2.
     * @return A new instance of fragment ServerSocketSetUp.
     */
    // TODO: Rename and change types and number of parameters
    public static ServerSocketSetUp newInstance(String param1, String param2) {
        ServerSocketSetUp fragment = new ServerSocketSetUp();
        Bundle args = new Bundle();
        args.putString(ARG_PARAM1, param1);
        args.putString(ARG_PARAM2, param2);
        fragment.setArguments(args);
        return fragment;
    }

    public ServerSocketSetUp() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mParam1 = getArguments().getString(ARG_PARAM1);
            mParam2 = getArguments().getString(ARG_PARAM2);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_server_socket_set_up, container, false);
    }

    // TODO: Rename method, update argument and hook method into UI event
    public void onButtonPressed(Uri uri) {
        if (mListener != null) {
            mListener.onFragmentInteraction(uri);
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            mListener = (OnFragmentInteractionListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement OnFragmentInteractionListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }

    /**
     * This interface must be implemented by activities that contain this
     * fragment to allow an interaction in this fragment to be communicated
     * to the activity and potentially other fragments contained in that
     * activity.
     * <p/>
     * See the Android Training lesson <a href=
     * "http://developer.android.com/training/basics/fragments/communicating.html"
     * >Communicating with Other Fragments</a> for more information.
     */
    public interface OnFragmentInteractionListener {
        // TODO: Update argument type and name
        public void onFragmentInteraction(Uri uri);
    }

    protected void listenBTclientReq() {
        try{
            // Create BT Service
            m_serverSocket = m_bluetooth.listenUsingRfcommWithServiceRecord(PROTOCOL_SCHEME_RFCOMM,
                    UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"));
            // Jonny's UUID
            // 00001101-0000-1000-8000-00805f9b34fb

            //apss UUID
            //a60f35f0-b93a-11de-8a39-08002009c666

            // Accept Client request
            BluetoothSocket socket = m_serverSocket.accept();

            // Process the Client Request
            if(socket != null){
                InputStream inputStream = socket.getInputStream();
                final OutputStream outputStream = socket.getOutputStream();
                final PrintWriter pw = new PrintWriter(new OutputStreamWriter(outputStream));
                int read = -1;
                final byte[] bytes = new byte[2048];
                // final byte[] sendBytes = new byte[2048];
                for (; (read = inputStream.read(bytes)) > -1;){
                    final int count = read;
                    m_handler.post(new Runnable() {
                        @Override
                        public void run() {
                            StringBuilder b = new StringBuilder();
                            for(int i =0; i < count; ++i){
                                if(i > 0){
                                    b.append(' ');
                                }
                                String s = Integer.toString(bytes[i] & 0xFF);
                                if(s.length() < 2){
                                    b.append('0');
                                }
                                b.append(s);
                            }
                            // Request stored in String
                            btClientRequest = b.toString();
                            masterRequest = true;
                        }
                    });
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
